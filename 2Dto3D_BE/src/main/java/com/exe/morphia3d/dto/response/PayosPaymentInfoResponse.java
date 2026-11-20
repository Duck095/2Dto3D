package com.exe.morphia3d.dto.response;

import java.time.Instant;

/** Owner-scoped payment-link details; sensitive URL/QR values are null unless the link is payable now. */
public record PayosPaymentInfoResponse(long orderCode, String paymentLinkId, String checkoutUrl,
                                       String qrPayload, Instant expiresAt, String linkState,
                                       String provenance, boolean testOnly) {
    public PayosPaymentInfoResponse(long orderCode, String paymentLinkId, String checkoutUrl,
            String qrPayload, Instant expiresAt, String linkState) {
        this(orderCode, paymentLinkId, checkoutUrl, qrPayload, expiresAt, linkState, "UNKNOWN", true);
    }
}
