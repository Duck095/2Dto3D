package com.exe.morphia3d.service;

import com.exe.morphia3d.dto.request.DesignerModelRequest;
import com.exe.morphia3d.dto.response.ModelResponse;
import com.exe.morphia3d.entity.MediaAsset;
import com.exe.morphia3d.entity.Model3d;
import com.exe.morphia3d.entity.User;
import com.exe.morphia3d.enums.MediaAssetState;
import com.exe.morphia3d.enums.MediaKind;
import com.exe.morphia3d.enums.MediaProvider;
import com.exe.morphia3d.enums.Role;
import com.exe.morphia3d.mapper.ModelMapper;
import com.exe.morphia3d.repository.ColorRepository;
import com.exe.morphia3d.repository.MediaAssetRepository;
import com.exe.morphia3d.repository.Model3dRepository;
import com.exe.morphia3d.repository.ProductGlbStructureSnapshotRepository;
import com.exe.morphia3d.repository.UserRepository;
import com.exe.morphia3d.security.UserPrincipal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.exe.morphia3d.entity.ProductGlbStructureSnapshot;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DesignerModelPersistenceTransactionTest {

    @Mock private PlatformTransactionManager transactions;
    @Mock private MediaAssetRepository assets;
    @Mock private Model3dRepository models;
    @Mock private UserRepository users;
    @Mock private ColorRepository colors;
    @Mock private ModelMapper mapper;
    @Mock private MediaAssetLifecycleService lifecycle;
    @Mock private ProductGlbStructureSnapshotRepository structureSnapshots;
    @Mock private EntityManager entityManager;
    @Mock private TransactionStatus status;

    private DesignerModelPersistenceService persistence;
    private UserPrincipal principal;
    private DesignerModelRequest metadata;
    private MediaAssetOperationLease operation;
    private List<MediaAsset> readyAssets;

    @BeforeEach
    void setUp() {
        persistence = new DesignerModelPersistenceService(transactions, assets, models, users, colors, mapper,
                lifecycle, structureSnapshots, entityManager);
        User user = User.builder().id(42).name("Designer").email("designer@example.com")
                .password("encoded").role(Role.DESIGNER).build();
        principal = new UserPrincipal(user);
        metadata = new DesignerModelRequest();
        metadata.setName("Desk lamp");
        metadata.setSlug("desk-lamp");
        UUID operationId = UUID.randomUUID();
        operation = new MediaAssetOperationLease(operationId, UUID.randomUUID(), "owner", List.of(11L, 12L));
        readyAssets = List.of(
                MediaAsset.builder().id(11L).operationId(operationId).mediaKind(MediaKind.MODEL_3D)
                        .provider(MediaProvider.CLOUDINARY).publicId("product-media/models/" + operationId + "/11.glb")
                        .state(MediaAssetState.READY).build(),
                MediaAsset.builder().id(12L).operationId(operationId).mediaKind(MediaKind.IMAGE)
                        .provider(MediaProvider.CLOUDINARY).publicId("product-media/images/" + operationId + "/12")
                        .state(MediaAssetState.READY).build());
        when(transactions.getTransaction(any(DefaultTransactionDefinition.class))).thenReturn(status);
        when(users.findById(42)).thenReturn(Optional.of(user));
        when(assets.findAllById(operation.mediaAssetIds())).thenReturn(readyAssets);
        when(models.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(mapper.toResponse(any())).thenReturn(ModelResponse.builder().id(55).build());
    }

    @Test
    void marksPersistenceFailureBeforeCommitAsRolledBack() {
        RuntimeException saveFailure = new IllegalStateException("save failed");
        when(models.saveAndFlush(any())).thenThrow(saveFailure);

        assertThatThrownBy(() -> persistence.persist(principal, metadata, operation))
                .isInstanceOf(MediaPersistenceException.class)
                .satisfies(failure -> org.assertj.core.api.Assertions.assertThat(
                        ((MediaPersistenceException) failure).isSafeToCompensate()).isTrue());

        verify(transactions).rollback(status);
    }

    @Test
    void marksCommitAcknowledgmentFailureAsAmbiguousAndDoesNotClaimRollback() {
        doThrow(new TransactionSystemException("commit acknowledgment lost"))
                .when(transactions).commit(status);

        assertThatThrownBy(() -> persistence.persist(principal, metadata, operation))
                .isInstanceOf(MediaPersistenceException.class)
                .satisfies(failure -> org.assertj.core.api.Assertions.assertThat(
                        ((MediaPersistenceException) failure).isSafeToCompensate()).isFalse());

        verify(transactions).commit(status);
        org.mockito.Mockito.verify(transactions, org.mockito.Mockito.never()).rollback(status);
    }

    @Test
    void snapshotInsertAndFlushUseSavedProductAndTrustedActorInsideTransaction() throws Exception {
        when(models.saveAndFlush(any())).thenAnswer(invocation -> {
            Model3d model = invocation.getArgument(0);
            model.setId(55);
            return model;
        });
        JsonNode structure = new ObjectMapper().readTree("{\"defaultSceneIndex\":-1,\"scenes\":[],\"nodes\":[],"
                + "\"meshes\":[],\"materials\":[],\"textures\":[],\"images\":[],\"primitiveOccurrences\":[]}");

        persistence.persistWithSnapshot(principal, metadata, operation,
                ProductGlbStructureSnapshot.CURRENT_PARSER_VERSION, structure);

        org.mockito.ArgumentCaptor<ProductGlbStructureSnapshot> snapshot =
                org.mockito.ArgumentCaptor.forClass(ProductGlbStructureSnapshot.class);
        verify(structureSnapshots).save(snapshot.capture());
        org.assertj.core.api.Assertions.assertThat(snapshot.getValue().getProductId()).isEqualTo(55);
        org.assertj.core.api.Assertions.assertThat(snapshot.getValue().getMediaAssetId()).isEqualTo(11L);
        org.assertj.core.api.Assertions.assertThat(snapshot.getValue().getSnapshotRevision()).isEqualTo(1L);
        org.assertj.core.api.Assertions.assertThat(snapshot.getValue().getCreatedBy()).isEqualTo(42);
        verify(entityManager).flush();
        verify(transactions).commit(status);
    }

    @Test
    void snapshotInsertFailureRollsBackProductAndAttachmentTransaction() throws Exception {
        when(models.saveAndFlush(any())).thenAnswer(invocation -> {
            Model3d model = invocation.getArgument(0);
            model.setId(55);
            return model;
        });
        when(structureSnapshots.findFirstByProductIdAndMediaAssetIdOrderBySnapshotRevisionDesc(55, 11L))
                .thenReturn(Optional.empty());
        RuntimeException insertFailure = new IllegalStateException("snapshot insert failed");
        when(structureSnapshots.save(any())).thenThrow(insertFailure);
        JsonNode structure = new ObjectMapper().readTree("{\"defaultSceneIndex\":-1,\"scenes\":[],\"nodes\":[],"
                + "\"meshes\":[],\"materials\":[],\"textures\":[],\"images\":[],\"primitiveOccurrences\":[]}");

        assertThatThrownBy(() -> persistence.persistWithSnapshot(principal, metadata, operation,
                ProductGlbStructureSnapshot.CURRENT_PARSER_VERSION, structure))
                .isInstanceOf(MediaPersistenceException.class)
                .satisfies(failure -> org.assertj.core.api.Assertions.assertThat(
                        ((MediaPersistenceException) failure).isSafeToCompensate()).isTrue());

        verify(lifecycle).attach(operation, operation.mediaAssetIds());
        verify(transactions).rollback(status);
        org.mockito.Mockito.verify(transactions, org.mockito.Mockito.never()).commit(status);
    }
}
