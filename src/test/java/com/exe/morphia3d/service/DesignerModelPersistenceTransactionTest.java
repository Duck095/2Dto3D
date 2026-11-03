package com.exe.morphia3d.service;

import com.exe.morphia3d.dto.request.DesignerModelRequest;
import com.exe.morphia3d.dto.response.ModelResponse;
import com.exe.morphia3d.entity.MediaAsset;
import com.exe.morphia3d.entity.User;
import com.exe.morphia3d.enums.MediaAssetState;
import com.exe.morphia3d.enums.MediaKind;
import com.exe.morphia3d.enums.MediaProvider;
import com.exe.morphia3d.enums.Role;
import com.exe.morphia3d.mapper.ModelMapper;
import com.exe.morphia3d.repository.ColorRepository;
import com.exe.morphia3d.repository.MediaAssetRepository;
import com.exe.morphia3d.repository.Model3dRepository;
import com.exe.morphia3d.repository.UserRepository;
import com.exe.morphia3d.security.UserPrincipal;
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
    @Mock private TransactionStatus status;

    private DesignerModelPersistenceService persistence;
    private UserPrincipal principal;
    private DesignerModelRequest metadata;
    private MediaAssetOperationLease operation;
    private List<MediaAsset> readyAssets;

    @BeforeEach
    void setUp() {
        persistence = new DesignerModelPersistenceService(transactions, assets, models, users, colors, mapper, lifecycle);
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
}
