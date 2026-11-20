package com.exe.morphia3d.service.impl;

import com.exe.morphia3d.entity.Model3d;
import com.exe.morphia3d.entity.User;
import com.exe.morphia3d.enums.ModelType;
import com.exe.morphia3d.mapper.ModelMapper;
import com.exe.morphia3d.repository.ColorRepository;
import com.exe.morphia3d.repository.Model3dRepository;
import com.exe.morphia3d.repository.UserRepository;
import com.exe.morphia3d.utils.ModelUrlResolver;
import com.exe.morphia3d.feature.FeatureGate;
import com.exe.morphia3d.service.admin3d.Admin3dRestrictedMutationGateway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.access.AccessDeniedException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertThrows;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class Model3dServiceImplTest {

    @Mock private Model3dRepository model3dRepository;
    @Mock private UserRepository userRepository;
    @Mock private ColorRepository colorRepository;
    @Mock private ModelUrlResolver modelUrlResolver;
    @Mock private ModelMapper modelMapper;
    @Mock private FeatureGate featureGate;
    @Mock private Admin3dRestrictedMutationGateway restrictedMutations;
    @InjectMocks private Model3dServiceImpl modelService;

    @Test
    void aiModelReadRejectsAnotherUser() {
        Model3d model = Model3d.builder()
                .id(7)
                .sourceType(ModelType.AI_MODEL)
                .user(User.builder().id(1).name("Owner").email("owner@example.com").password("encoded").build())
                .build();
        when(model3dRepository.findById(7)).thenReturn(Optional.of(model));

        assertThrows(AccessDeniedException.class,
                () -> modelService.getAIModelById(2, 7));
    }
}
