package com.exe.morphia3d.service.payment.payos;

import com.exe.morphia3d.domain.payment.PaymentAttemptStatus;
import com.exe.morphia3d.entity.PaymentAttempt;
import com.exe.morphia3d.entity.PayosPaymentLink;
import com.exe.morphia3d.entity.PayosWebhookReceipt;
import com.exe.morphia3d.entity.PayosReconciliationQuery;
import com.exe.morphia3d.entity.Order;
import com.exe.morphia3d.enums.PaymentProvider;
import com.exe.morphia3d.enums.PayosPaymentProvenance;
import com.exe.morphia3d.repository.PaymentAttemptRepository;
import com.exe.morphia3d.repository.OrderRepository;
import com.exe.morphia3d.repository.PayosPaymentLinkRepository;
import com.exe.morphia3d.repository.PayosWebhookReceiptRepository;
import com.exe.morphia3d.repository.PayosReconciliationQueryRepository;
import com.exe.morphia3d.service.PaymentAttemptStateService;
import com.exe.morphia3d.service.PaymentApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Durable payOS identity/link storage. It never performs provider I/O. */
@Service
@ConditionalOnBean(PayosSensitiveDataCipher.class)
public class PayosPaymentPersistenceService {
    private final PaymentAttemptRepository attempts;
    private final OrderRepository orders;
    private final PayosPaymentLinkRepository links;
    private final PayosWebhookReceiptRepository receipts;
    private final PayosReconciliationQueryRepository reconciliationQueries;
    private final JdbcTemplate jdbc;
    private final PayosSensitiveDataCipher cipher;
    private final PaymentAttemptStateService stateService;
    private final Clock clock;

    public PayosPaymentPersistenceService(PaymentAttemptRepository attempts, OrderRepository orders, PayosPaymentLinkRepository links,
            PayosWebhookReceiptRepository receipts, PayosReconciliationQueryRepository reconciliationQueries,
            JdbcTemplate jdbc, PayosSensitiveDataCipher cipher, PaymentAttemptStateService stateService, Clock clock) {
        this.attempts = attempts;
        this.orders = orders;
        this.links = links;
        this.receipts = receipts;
        this.reconciliationQueries = reconciliationQueries;
        this.jdbc = jdbc;
        this.cipher = cipher;
        this.stateService = stateService;
        this.clock = clock;
    }

    /** Reserves orderCode and all immutable request identity before any provider create request. */
    @Transactional
    public PayosPaymentLink prepare(UUID attemptId, String merchantChannelId, String description, Instant expiresAt) {
        PaymentAttempt attempt = attempts.findByAttemptIdForUpdate(attemptId)
                .orElseThrow(() -> notFound("PAYMENT_ATTEMPT_NOT_FOUND"));
        if (attempt.getProvider() != PaymentProvider.PAYOS || attempt.getAmountVnd() == null || attempt.getAmountVnd() <= 0)
            throw conflict("PAYOS_ATTEMPT_NOT_READY");
        if (merchantChannelId == null || merchantChannelId.isBlank() || merchantChannelId.length() > 128
                || description == null || description.isBlank() || description.length() > 9
                || expiresAt == null || !expiresAt.isAfter(clock.instant())) throw badRequest("PAYOS_LINK_REQUEST_INVALID");

        var existing = links.findByAttemptIdForUpdate(attemptId);
        if (existing.isPresent()) {
            PayosPaymentLink link = existing.get();
            if (!Objects.equals(link.getMerchantChannelId(), merchantChannelId)
                    || !Objects.equals(link.getAmountVnd(), attempt.getAmountVnd())
                    || !Objects.equals(link.getDescription(), description)) throw conflict("PAYOS_IDENTITY_CONFLICT");
            return link;
        }
        long orderCode = jdbc.queryForObject("SELECT nextval('payos_order_code_seq')", Long.class);
        Instant now = clock.instant();
        Instant persistedExpiry = expiresAt.truncatedTo(ChronoUnit.MICROS);
        if (!persistedExpiry.isAfter(clock.instant())) throw badRequest("PAYOS_LINK_REQUEST_INVALID");
        PayosPaymentLink link = PayosPaymentLink.builder().attemptId(attemptId).provider(PaymentProvider.PAYOS)
                // Offline/test foundation only. LIVE can only be assigned by a future verified merchant binding.
                .provenance(PayosPaymentProvenance.TEST_ONLY)
                .orderCode(orderCode).merchantChannelId(merchantChannelId).amountVnd(attempt.getAmountVnd())
                .requestFingerprint(fingerprint(attempt, merchantChannelId, description, expiresAt))
                .description(description).linkState("PREPARED").currency("VND").expiresAt(persistedExpiry)
                .createdAt(now).updatedAt(now).build();
        // Flush before returning: callers may only dispatch after this transaction commits.
        return links.saveAndFlush(link);
    }

    /** One-shot claim. A crash after this point is ambiguous and therefore never retried as create. */
    @Transactional
    public boolean claimCreate(UUID attemptId) {
        PaymentAttempt observed = attempts.findById(attemptId)
                .orElseThrow(() -> notFound("PAYMENT_ATTEMPT_NOT_FOUND"));
        Order order = orders.findByIdForUpdate(observed.getOrderId())
                .orElseThrow(() -> notFound("PAYMENT_ORDER_NOT_FOUND"));
        PaymentAttempt attempt = attempts.findByAttemptIdForUpdate(attemptId)
                .orElseThrow(() -> notFound("PAYMENT_ATTEMPT_NOT_FOUND"));
        PayosPaymentLink link = links.findByAttemptIdForUpdate(attemptId)
                .orElseThrow(() -> notFound("PAYOS_LINK_IDENTITY_NOT_FOUND"));
        if (!"PREPARED".equals(link.getLinkState())) return false;
        if (!link.getExpiresAt().isAfter(clock.instant())) {
            link.setLinkState("EXPIRED"); link.setUpdatedAt(clock.instant());
            return false;
        }
        if (attempt.getStatus() != PaymentAttemptStatus.INITIATED || !stateService.claimSubmission(attemptId)) return false;
        link.setLinkState("SUBMITTING");
        link.setUpdatedAt(clock.instant());
        return true;
    }

    @Transactional
    public void markCreateUnknown(UUID attemptId) {
        PaymentAttempt observed = attempts.findById(attemptId)
                .orElseThrow(() -> notFound("PAYMENT_ATTEMPT_NOT_FOUND"));
        orders.findByIdForUpdate(observed.getOrderId())
                .orElseThrow(() -> notFound("PAYMENT_ORDER_NOT_FOUND"));
        attempts.findByAttemptIdForUpdate(attemptId)
                .orElseThrow(() -> notFound("PAYMENT_ATTEMPT_NOT_FOUND"));
        PayosPaymentLink link = lockLink(attemptId);
        if ("SUBMITTING".equals(link.getLinkState())) {
            stateService.markSubmissionUnknown(attemptId, "PAYOS_CREATE_OUTCOME_UNKNOWN",
                    "Payment link creation outcome requires reconciliation");
            link.setLinkState("UNKNOWN"); link.setUpdatedAt(clock.instant());
        }
    }

    @Transactional
    public void markCreateRejected(UUID attemptId, String safeCode) {
        PaymentAttempt observed = attempts.findById(attemptId)
                .orElseThrow(() -> notFound("PAYMENT_ATTEMPT_NOT_FOUND"));
        orders.findByIdForUpdate(observed.getOrderId())
                .orElseThrow(() -> notFound("PAYMENT_ORDER_NOT_FOUND"));
        attempts.findByAttemptIdForUpdate(attemptId)
                .orElseThrow(() -> notFound("PAYMENT_ATTEMPT_NOT_FOUND"));
        PayosPaymentLink link = lockLink(attemptId);
        if ("SUBMITTING".equals(link.getLinkState())) {
            stateService.recordSubmission(attemptId, new com.exe.morphia3d.service.PaymentProviderSubmissionResult(
                    com.exe.morphia3d.service.PaymentProviderSubmissionResult.Outcome.REJECTED,
                    null, null, null, safeCode, "payOS rejected payment link creation"));
            link.setLinkState("REJECTED");
            link.setUpdatedAt(clock.instant());
        }
    }

    @Transactional
    public void recordCreated(UUID attemptId, CreatedLink created, Set<String> allowedHosts) {
        PayosPaymentLink link = lockLink(attemptId);
        if (!"SUBMITTING".equals(link.getLinkState())) throw conflict("PAYOS_LINK_NOT_SUBMITTING");
        if (created == null || created.orderCode() != link.getOrderCode() || created.amountVnd() != link.getAmountVnd()
                || !validId(created.paymentLinkId()) || !allowedHttpsUrl(created.checkoutUrl(), allowedHosts)
                || created.qrData() == null || created.qrData().isBlank() || created.recipientAccountNumber() == null
                || created.recipientAccountNumber().isBlank()
                || !Objects.equals(link.getExpiresAt(), created.expiresAt() == null ? null : created.expiresAt().truncatedTo(ChronoUnit.MICROS))
                || created.providerResponseDigest() == null || !created.providerResponseDigest().matches("[0-9a-f]{64}"))
            throw conflict("PAYOS_LINK_RESPONSE_MISMATCH");
        Instant now = clock.instant();
        var url = cipher.encrypt(created.checkoutUrl(), aad(attemptId, "checkout-url"));
        var qr = cipher.encrypt(created.qrData(), aad(attemptId, "qr-data"));
        var account = cipher.encrypt(created.recipientAccountNumber(), aad(attemptId, "recipient-account"));
        if (url.keyVersion() != qr.keyVersion() || qr.keyVersion() != account.keyVersion()
                || url.keyVersion() > Short.MAX_VALUE) throw conflict("PAYOS_ENCRYPTION_KEY_VERSION_INVALID");
        link.setPaymentLinkId(created.paymentLinkId());
        link.setCheckoutUrlCiphertext(url.ciphertext()); link.setQrPayloadCiphertext(qr.ciphertext());
        link.setReceivingBankBin(created.bankBin()); link.setReceivingAccountNumberCiphertext(account.ciphertext());
        link.setReceivingAccountName(created.accountName()); link.setEncryptionKeyVersion((short) url.keyVersion());
        link.setProviderResponseDigest(created.providerResponseDigest()); link.setLinkCreatedAt(now);
        link.setLinkState("LINK_CREATED"); link.setUpdatedAt(now);
        stateService.recordSubmission(attemptId, new com.exe.morphia3d.service.PaymentProviderSubmissionResult(
                com.exe.morphia3d.service.PaymentProviderSubmissionResult.Outcome.ACCEPTED,
                created.paymentLinkId(), null, null, null, null));
        matchEarlyPaidReceipts(link);
    }

    @Transactional
    public boolean expireIfDue(UUID attemptId) {
        PayosPaymentLink link = lockLink(attemptId);
        if (link.getExpiresAt().isAfter(clock.instant()) || "EXPIRED".equals(link.getLinkState())) return false;
        if ("PREPARED".equals(link.getLinkState()) || "LINK_CREATED".equals(link.getLinkState())) {
            link.setLinkState("EXPIRED"); link.setUpdatedAt(clock.instant()); return true;
        }
        return false; // UNKNOWN remains unresolved for reconciliation.
    }

    /** Stores signed provider evidence; only a final, fully matching PAID event can be MATCHED. */
    @Transactional
    public PayosWebhookReceipt recordVerifiedWebhook(PayosProtocol.VerifiedWebhook evidence) {
        if (evidence == null || evidence.signatureVerifiedAt() == null || evidence.merchantChannelId() == null
                || evidence.bankReference() == null || evidence.eventDigest() == null
                || !evidence.eventDigest().matches("[0-9a-f]{64}") || evidence.orderCode() < 0
                || evidence.amountVnd() < 0) throw badRequest("PAYOS_VERIFIED_EVIDENCE_INVALID");

        // Serialize all deliveries sharing a bank reference, including concurrent distinct payloads.
        jdbc.query("SELECT pg_advisory_xact_lock(hashtext(?), hashtext(?))", rs -> { },
                evidence.merchantChannelId(), evidence.bankReference());
        var duplicate = receipts.findByMerchantChannelIdAndBankReferenceOrderByReceivedAtAsc(
                evidence.merchantChannelId(), evidence.bankReference()).stream()
                .filter(row -> row.getEventDigest().equals(evidence.eventDigest())).findFirst();
        if (duplicate.isPresent()) return duplicate.get();

        var link = links.findByMerchantChannelIdAndOrderCode(evidence.merchantChannelId(), evidence.orderCode()).orElse(null);
        UUID receiptId = UUID.randomUUID();
        String disposition;
        UUID attemptId = link == null ? null : link.getAttemptId();
        boolean matchingIdentity = link != null && Objects.equals(link.getPaymentLinkId(), evidence.paymentLinkId())
                && Objects.equals(link.getAmountVnd(), evidence.amountVnd())
                && "VND".equals(evidence.currency())
                && link.getReceivingAccountNumberCiphertext() != null
                && evidence.recipientAccountNumber() != null
                && cipher.decrypt(new PayosSensitiveDataCipher.EncryptedValue(link.getEncryptionKeyVersion(),
                    link.getReceivingAccountNumberCiphertext()), aad(link.getAttemptId(), "recipient-account"))
                    .equals(evidence.recipientAccountNumber());
        boolean finalPaid = evidence.state() == com.exe.morphia3d.service.payment.provider.ProviderPaymentState.SUCCEEDED
                && "PAID".equals(evidence.providerStatus());
        boolean matching = matchingIdentity && finalPaid;
        if (!matchingIdentity) attemptId = null; // preserve anomalous signed evidence without attaching another order's attempt.
        boolean alreadyMatched = receipts.findByMerchantChannelIdAndBankReferenceOrderByReceivedAtAsc(
                evidence.merchantChannelId(), evidence.bankReference()).stream()
                .anyMatch(row -> "MATCHED".equals(row.getDisposition()));
        disposition = alreadyMatched ? "CONFLICT" : matching ? "MATCHED" : "RECONCILIATION_REQUIRED";
        Instant now = clock.instant();
        String recipient = evidence.recipientAccountNumber() == null ? "" : evidence.recipientAccountNumber();
        var encryptedRecipient = cipher.encrypt(recipient, aad(receiptId, "receipt-recipient"));
        String receiptCurrency = evidence.currency() != null && evidence.currency().matches("[A-Z]{3,8}")
                ? evidence.currency() : "UNKNOWN";
        String receiptLinkId = evidence.paymentLinkId() != null && evidence.paymentLinkId().length() <= 128
                && !evidence.paymentLinkId().isBlank() ? evidence.paymentLinkId() : null;
        return receipts.saveAndFlush(PayosWebhookReceipt.builder().receiptId(receiptId)
                .merchantChannelId(evidence.merchantChannelId()).bankReference(evidence.bankReference())
                .eventDigest(evidence.eventDigest()).attemptId(attemptId).orderCode(evidence.orderCode())
                .paymentLinkId(receiptLinkId).amountVnd(evidence.amountVnd())
                .recipientAccountCiphertext(encryptedRecipient.ciphertext())
                .encryptionKeyVersion((short) encryptedRecipient.keyVersion())
                .providerStatus(evidence.providerStatus()).disposition(disposition).signatureVerified(true).currency(receiptCurrency)
                .signatureVerifiedAt(evidence.signatureVerifiedAt()).receivedAt(now).processedAt(now).build());
    }

    @Transactional
    public PayosReconciliationQuery storeReconciliationQuery(PayosProtocol.VerifiedPaymentLinkQuery evidence) {
        if (evidence == null || evidence.attemptId() == null || evidence.merchantChannelId() == null
                || evidence.merchantChannelId().isBlank() || evidence.queryRequestId() == null
                || evidence.queryRequestId().isBlank() || evidence.queryRequestId().length() > 128
                || evidence.orderCode() <= 0 || evidence.verifiedAt() == null)
            throw badRequest("PAYOS_RECONCILIATION_EVIDENCE_INVALID");
        var link = links.findByAttemptIdForUpdate(evidence.attemptId())
                .orElseThrow(() -> notFound("PAYOS_LINK_NOT_FOUND"));
        boolean identity = link.getOrderCode() == evidence.orderCode()
                && link.getMerchantChannelId().equals(evidence.merchantChannelId())
                && Objects.equals(link.getPaymentLinkId(), evidence.paymentLinkId())
                && Objects.equals(link.getAmountVnd(), evidence.amountVnd());
        var prior = reconciliationQueries.findByMerchantChannelIdAndQueryRequestId(
                evidence.merchantChannelId(), evidence.queryRequestId());
        if (prior.isPresent()) {
            PayosReconciliationQuery query = prior.get();
            if (!query.getAttemptId().equals(evidence.attemptId())
                    || !Objects.equals(query.getResponseDigest(), evidence.responseDigest()))
                throw conflict("PAYOS_RECONCILIATION_REQUEST_ID_REUSED");
            return query;
        }
        String safeOutcome = identity ? evidence.outcome() : "UNKNOWN";
        if ("SUCCEEDED".equals(safeOutcome) && (!"PAID".equals(evidence.providerStatus())
                || evidence.amountPaidVnd() != link.getAmountVnd())) safeOutcome = "UNKNOWN";
        if (!Set.of("PENDING", "SUCCEEDED", "FAILED", "UNKNOWN").contains(safeOutcome)) safeOutcome = "UNKNOWN";
        Instant completedAt = "PENDING".equals(safeOutcome) ? null : evidence.verifiedAt();
        return reconciliationQueries.saveAndFlush(PayosReconciliationQuery.builder().queryId(UUID.randomUUID())
                .attemptId(evidence.attemptId()).merchantChannelId(link.getMerchantChannelId())
                .queryRequestId(evidence.queryRequestId()).orderCode(link.getOrderCode())
                .paymentLinkId(link.getPaymentLinkId()).amountVnd(link.getAmountVnd()).requestedAt(evidence.verifiedAt())
                .completedAt(completedAt).outcome(safeOutcome)
                .providerStatus(evidence.providerStatus()).amountPaidVnd(evidence.amountPaidVnd() < 0 ? null : evidence.amountPaidVnd())
                .responseDigest(evidence.responseDigest()).signatureVerified(true).build());
    }

    @Transactional
    public LinkView readOwned(UUID attemptId, Integer ownerId) {
        PaymentAttempt attempt = attempts.findByAttemptIdAndOwnerId(attemptId, ownerId)
                .orElseThrow(() -> notFound("PAYMENT_ATTEMPT_NOT_FOUND"));
        PayosPaymentLink link = lockLink(attempt.getAttemptId());
        if (!link.getExpiresAt().isAfter(clock.instant())) expireIfDue(attemptId);
        if (attempt.getStatus() != PaymentAttemptStatus.PENDING
                || !"LINK_CREATED".equals(link.getLinkState())
                || link.getProvenance() != PayosPaymentProvenance.LIVE
                || !link.getExpiresAt().isAfter(clock.instant())
                || link.getCheckoutUrlCiphertext() == null || link.getQrPayloadCiphertext() == null
                || link.getReceivingAccountNumberCiphertext() == null) {
            return new LinkView(link.getOrderCode(), link.getPaymentLinkId(), link.getAmountVnd(), link.getLinkState(),
                    link.getExpiresAt(), provenanceName(link), null, null, null, null, null);
        }
        int version = link.getEncryptionKeyVersion();
        return new LinkView(link.getOrderCode(), link.getPaymentLinkId(), link.getAmountVnd(), link.getLinkState(),
                link.getExpiresAt(), provenanceName(link), cipher.decrypt(new PayosSensitiveDataCipher.EncryptedValue(version, link.getCheckoutUrlCiphertext()), aad(attemptId, "checkout-url")),
                cipher.decrypt(new PayosSensitiveDataCipher.EncryptedValue(version, link.getQrPayloadCiphertext()), aad(attemptId, "qr-data")),
                cipher.decrypt(new PayosSensitiveDataCipher.EncryptedValue(version, link.getReceivingAccountNumberCiphertext()), aad(attemptId, "recipient-account")),
                link.getReceivingBankBin(), link.getReceivingAccountName());
    }

    private String provenanceName(PayosPaymentLink link) {
        return link.getProvenance() == null ? "UNKNOWN" : link.getProvenance().name();
    }

    @Transactional(readOnly = true)
    public List<PayosWebhookReceipt> matchedReceipts(UUID attemptId) {
        return receipts.findByAttemptIdOrderByReceivedAtDesc(attemptId).stream()
                .filter(receipt -> "MATCHED".equals(receipt.getDisposition()))
                .toList();
    }

    @Transactional(readOnly = true)
    public PayosProtocol.ExpectedPayment expectedPayment(UUID attemptId, String merchantChannelId) {
        PayosPaymentLink link = links.findById(attemptId)
                .orElseThrow(() -> notFound("PAYOS_LINK_NOT_FOUND"));
        if (!Objects.equals(link.getMerchantChannelId(), merchantChannelId)
                || link.getPaymentLinkId() == null || link.getReceivingAccountNumberCiphertext() == null)
            throw conflict("PAYOS_QUERY_IDENTITY_UNAVAILABLE");
        String account = cipher.decrypt(new PayosSensitiveDataCipher.EncryptedValue(link.getEncryptionKeyVersion(),
                link.getReceivingAccountNumberCiphertext()), aad(attemptId, "recipient-account"));
        return new PayosProtocol.ExpectedPayment(attemptId, link.getOrderCode(), link.getAmountVnd(),
                link.getDescription(), link.getPaymentLinkId(), account);
    }

    private PayosPaymentLink lockLink(UUID attemptId) {
        return links.findByAttemptIdForUpdate(attemptId).orElseThrow(() -> notFound("PAYOS_LINK_NOT_FOUND"));
    }

    private void matchEarlyPaidReceipts(PayosPaymentLink link) {
        for (PayosWebhookReceipt receipt : receipts.findByMerchantChannelIdAndOrderCodeAndDispositionOrderByReceivedAtAsc(
                link.getMerchantChannelId(), link.getOrderCode(), "RECONCILIATION_REQUIRED")) {
            boolean exactMatch = receipt.isSignatureVerified() && "PAID".equals(receipt.getProviderStatus())
                    && "VND".equals(receipt.getCurrency()) && Objects.equals(receipt.getAmountVnd(), link.getAmountVnd())
                    && Objects.equals(receipt.getPaymentLinkId(), link.getPaymentLinkId())
                    && receipt.getRecipientAccountCiphertext() != null
                    && cipher.decrypt(new PayosSensitiveDataCipher.EncryptedValue(receipt.getEncryptionKeyVersion(),
                        receipt.getRecipientAccountCiphertext()), aad(receipt.getReceiptId(), "receipt-recipient"))
                        .equals(cipher.decrypt(new PayosSensitiveDataCipher.EncryptedValue(link.getEncryptionKeyVersion(),
                        link.getReceivingAccountNumberCiphertext()), aad(link.getAttemptId(), "recipient-account")));
            if (!exactMatch) continue;
            boolean bankReferenceAlreadySettled = receipts
                    .findByMerchantChannelIdAndBankReferenceOrderByReceivedAtAsc(receipt.getMerchantChannelId(),
                            receipt.getBankReference()).stream()
                    .anyMatch(other -> !other.getReceiptId().equals(receipt.getReceiptId())
                            && "MATCHED".equals(other.getDisposition()));
            if (bankReferenceAlreadySettled) {
                receipt.setDisposition("CONFLICT");
            } else {
                receipt.setAttemptId(link.getAttemptId());
                receipt.setDisposition("MATCHED");
            }
            receipt.setProcessedAt(clock.instant());
        }
    }
    private String fingerprint(PaymentAttempt a, String channel, String description, Instant expiry) {
        String input = a.getAttemptId() + "|" + a.getOrderId() + "|" + a.getAmountVnd() + "|" + channel + "|" + description + "|" + expiry;
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }
    private String aad(UUID id, String field) { return "payos:v1:" + id + ":" + field; }
    private boolean validId(String id) { return id != null && !id.isBlank() && id.length() <= 128; }
    private boolean allowedHttpsUrl(String value, Set<String> hosts) {
        if (value == null || hosts == null || hosts.isEmpty()) return false;
        try { URI uri = URI.create(value); return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null
                && uri.getUserInfo() == null && uri.getFragment() == null && hosts.stream().anyMatch(h -> h.equalsIgnoreCase(uri.getHost())); }
        catch (IllegalArgumentException invalid) { return false; }
    }
    private PaymentApiException notFound(String code) { return new PaymentApiException(404, code, "PayOS payment data was not found"); }
    private PaymentApiException badRequest(String code) { return new PaymentApiException(400, code, "PayOS link request is invalid"); }
    private PaymentApiException conflict(String code) { return new PaymentApiException(409, code, "PayOS payment state requires review"); }

    public record CreatedLink(long orderCode, long amountVnd, String paymentLinkId, String checkoutUrl, String qrData,
                              String bankBin, String recipientAccountNumber, String accountName, Instant expiresAt,
                              String providerResponseDigest) { }
    public record LinkView(long orderCode, String paymentLinkId, long amountVnd, String state, Instant expiresAt,
                           String provenance,
                           String checkoutUrl, String qrData, String recipientAccountNumber, String bankBin,
                           String accountName) { }
}
