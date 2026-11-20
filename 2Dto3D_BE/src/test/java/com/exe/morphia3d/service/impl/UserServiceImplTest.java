package com.exe.morphia3d.service.impl;

import com.exe.morphia3d.enums.UserStatus;
import com.exe.morphia3d.repository.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserServiceImplTest {

    @Test
    void missingAccountThrowsNotFoundInsteadOfSavingNull() {
        UserRepository userRepository = mock(UserRepository.class);
        UserServiceImpl userService = new UserServiceImpl(userRepository);
        when(userRepository.findById(404)).thenReturn(Optional.empty());

        assertThrows(EntityNotFoundException.class,
                () -> userService.updateStatus(404, UserStatus.UNACTIVE));

        verify(userRepository, never()).save(any());
    }
}
