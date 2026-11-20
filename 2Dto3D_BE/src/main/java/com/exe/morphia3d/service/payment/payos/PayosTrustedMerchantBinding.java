package com.exe.morphia3d.service.payment.payos;

import java.time.Instant;
import java.util.UUID;

/** Non-secret view of a verified merchant binding. Secret material is never loaded here. */
public record PayosTrustedMerchantBinding(
        UUID bindingId,
        int version,
        String channelId,
        String clientId,
        String apiKeySecretRef,
        String checksumKeySecretRef,
        String secretVersionRef,
        String receivingBankBin,
        String receivingAccountFingerprint,
        String verificationEvidenceRef,
        String approvedBy,
        Instant verifiedAt) {
}
