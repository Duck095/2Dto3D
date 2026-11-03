package com.exe.morphia3d.service;

import com.exe.morphia3d.enums.StaffStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StaffStatusTransitionTest {

    @Test
    void allowsOnlyApprovedLifecycleTransitions() {
        assertThat(StaffManagementService.isAllowedTransition(StaffStatus.PENDING, StaffStatus.ACTIVE)).isTrue();
        assertThat(StaffManagementService.isAllowedTransition(StaffStatus.PENDING, StaffStatus.TERMINATED)).isTrue();
        assertThat(StaffManagementService.isAllowedTransition(StaffStatus.ACTIVE, StaffStatus.SUSPENDED)).isTrue();
        assertThat(StaffManagementService.isAllowedTransition(StaffStatus.ACTIVE, StaffStatus.TERMINATED)).isTrue();
        assertThat(StaffManagementService.isAllowedTransition(StaffStatus.SUSPENDED, StaffStatus.ACTIVE)).isTrue();
        assertThat(StaffManagementService.isAllowedTransition(StaffStatus.SUSPENDED, StaffStatus.TERMINATED)).isTrue();
    }

    @Test
    void terminatedIsTerminalAndNoOpOrUnlistedTransitionsAreRejected() {
        for (StaffStatus target : StaffStatus.values()) {
            assertThat(StaffManagementService.isAllowedTransition(StaffStatus.TERMINATED, target)).isFalse();
        }
        assertThat(StaffManagementService.isAllowedTransition(StaffStatus.PENDING, StaffStatus.PENDING)).isFalse();
        assertThat(StaffManagementService.isAllowedTransition(StaffStatus.ACTIVE, StaffStatus.PENDING)).isFalse();
        assertThat(StaffManagementService.isAllowedTransition(StaffStatus.SUSPENDED, StaffStatus.PENDING)).isFalse();
    }
}
