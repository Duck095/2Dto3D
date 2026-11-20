package com.exe.morphia3d.service;

import com.exe.morphia3d.entity.PaymentAttempt;

public record PaymentAttemptCreationResult(PaymentAttempt attempt, boolean created) {
}
