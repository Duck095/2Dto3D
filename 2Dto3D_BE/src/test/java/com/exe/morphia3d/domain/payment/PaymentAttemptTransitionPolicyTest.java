package com.exe.morphia3d.domain.payment;

import com.exe.morphia3d.enums.OrderStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentAttemptTransitionPolicyTest {

    private final PaymentAttemptTransitionPolicy policy = new PaymentAttemptTransitionPolicy();

    @Test
    void successfulPendingAttemptAdvancesOnlyAPendingOrder() {
        PaymentAttemptDecision decision = policy.decide(
                PaymentAttemptStatus.PENDING,
                PaymentAttemptEvent.SUCCESS,
                OrderStatus.PENDING);

        assertThat(decision.status()).isEqualTo(PaymentAttemptStatus.SUCCEEDED);
        assertThat(decision.disposition()).isEqualTo(PaymentAttemptDisposition.APPLIED);
        assertThat(decision.advanceOrderToProcessing()).isTrue();
    }

    @Test
    void lateSuccessAfterCancellationRequiresReconciliationWithoutReopeningOrder() {
        PaymentAttemptDecision decision = policy.decide(
                PaymentAttemptStatus.PENDING,
                PaymentAttemptEvent.SUCCESS,
                OrderStatus.CANCELED);

        assertThat(decision.status()).isEqualTo(PaymentAttemptStatus.SUCCEEDED);
        assertThat(decision.disposition()).isEqualTo(PaymentAttemptDisposition.RECONCILIATION_REQUIRED);
        assertThat(decision.advanceOrderToProcessing()).isFalse();
    }

    @Test
    void successForAnAttemptWhenOrderIsAlreadyInFulfillmentRequiresReconciliation() {
        PaymentAttemptDecision decision = policy.decide(
                PaymentAttemptStatus.PENDING,
                PaymentAttemptEvent.SUCCESS,
                OrderStatus.PROCESSING);

        assertThat(decision.status()).isEqualTo(PaymentAttemptStatus.SUCCEEDED);
        assertThat(decision.disposition()).isEqualTo(PaymentAttemptDisposition.RECONCILIATION_REQUIRED);
        assertThat(decision.advanceOrderToProcessing()).isFalse();
    }

    @Test
    void duplicateSuccessfulCallbackDoesNotChangeAttemptOrOrder() {
        PaymentAttemptDecision decision = policy.decide(
                PaymentAttemptStatus.SUCCEEDED,
                PaymentAttemptEvent.SUCCESS,
                OrderStatus.PENDING);

        assertThat(decision.status()).isEqualTo(PaymentAttemptStatus.SUCCEEDED);
        assertThat(decision.disposition()).isEqualTo(PaymentAttemptDisposition.DUPLICATE);
        assertThat(decision.advanceOrderToProcessing()).isFalse();
    }

    @Test
    void failedCallbackAfterSuccessCannotDowngradeAttempt() {
        PaymentAttemptDecision decision = policy.decide(
                PaymentAttemptStatus.SUCCEEDED,
                PaymentAttemptEvent.FAILURE,
                OrderStatus.PROCESSING);

        assertThat(decision.status()).isEqualTo(PaymentAttemptStatus.SUCCEEDED);
        assertThat(decision.disposition()).isEqualTo(PaymentAttemptDisposition.IGNORED_OUT_OF_ORDER);
        assertThat(decision.advanceOrderToProcessing()).isFalse();
    }

    @Test
    void verifiedSuccessForFailedAttemptRequiresReconciliationWithoutResettingTerminalState() {
        PaymentAttemptDecision decision = policy.decide(
                PaymentAttemptStatus.FAILED,
                PaymentAttemptEvent.SUCCESS,
                OrderStatus.CANCELED);

        assertThat(decision.status()).isEqualTo(PaymentAttemptStatus.FAILED);
        assertThat(decision.disposition()).isEqualTo(PaymentAttemptDisposition.RECONCILIATION_REQUIRED);
        assertThat(decision.advanceOrderToProcessing()).isFalse();
    }

    @Test
    void appliesPendingFailureAndExpiryTransitions() {
        assertThat(policy.decide(PaymentAttemptStatus.PENDING, PaymentAttemptEvent.FAILURE, OrderStatus.PENDING))
                .isEqualTo(new PaymentAttemptDecision(
                        PaymentAttemptStatus.FAILED, PaymentAttemptDisposition.APPLIED, false));
        assertThat(policy.decide(PaymentAttemptStatus.PENDING, PaymentAttemptEvent.EXPIRE, OrderStatus.PENDING))
                .isEqualTo(new PaymentAttemptDecision(
                        PaymentAttemptStatus.EXPIRED, PaymentAttemptDisposition.APPLIED, false));
    }

    @Test
    void appliesOnlyApprovedRefundTransitions() {
        assertThat(policy.decide(
                PaymentAttemptStatus.SUCCEEDED, PaymentAttemptEvent.PARTIAL_REFUND, OrderStatus.SUCCEEDED))
                .isEqualTo(new PaymentAttemptDecision(
                        PaymentAttemptStatus.PARTIALLY_REFUNDED, PaymentAttemptDisposition.APPLIED, false));
        assertThat(policy.decide(
                PaymentAttemptStatus.PARTIALLY_REFUNDED, PaymentAttemptEvent.FULL_REFUND, OrderStatus.SUCCEEDED))
                .isEqualTo(new PaymentAttemptDecision(
                        PaymentAttemptStatus.REFUNDED, PaymentAttemptDisposition.APPLIED, false));
        assertThat(policy.decide(
                PaymentAttemptStatus.SUCCEEDED, PaymentAttemptEvent.FULL_REFUND, OrderStatus.SUCCEEDED))
                .isEqualTo(new PaymentAttemptDecision(
                        PaymentAttemptStatus.REFUNDED, PaymentAttemptDisposition.APPLIED, false));
    }

    @Test
    void terminalAttemptsCannotBeReusedOrMovedByOutOfOrderEvents() {
        assertThat(policy.decide(PaymentAttemptStatus.FAILED, PaymentAttemptEvent.FAILURE, OrderStatus.PENDING))
                .isEqualTo(new PaymentAttemptDecision(
                        PaymentAttemptStatus.FAILED, PaymentAttemptDisposition.DUPLICATE, false));
        assertThat(policy.decide(PaymentAttemptStatus.EXPIRED, PaymentAttemptEvent.FAILURE, OrderStatus.PENDING))
                .isEqualTo(new PaymentAttemptDecision(
                        PaymentAttemptStatus.EXPIRED, PaymentAttemptDisposition.IGNORED_OUT_OF_ORDER, false));
        assertThat(policy.decide(PaymentAttemptStatus.PENDING, PaymentAttemptEvent.PARTIAL_REFUND, OrderStatus.PENDING))
                .isEqualTo(new PaymentAttemptDecision(
                        PaymentAttemptStatus.PENDING, PaymentAttemptDisposition.IGNORED_OUT_OF_ORDER, false));
    }

    @Test
    void dispatchCrashRecoveryKeepsAmbiguousSubmissionUnknownAndDoesNotAllowRetry() {
        PaymentAttemptDecision claimed = policy.decide(
                PaymentAttemptStatus.INITIATED, PaymentAttemptEvent.SUBMISSION_CLAIMED, OrderStatus.PENDING);
        PaymentAttemptDecision ambiguous = policy.decide(
                claimed.status(), PaymentAttemptEvent.AMBIGUOUS_RESULT, OrderStatus.PENDING);
        PaymentAttemptDecision duplicateSuccess = policy.decide(
                ambiguous.status(), PaymentAttemptEvent.SUCCESS, OrderStatus.PENDING);

        assertThat(claimed.status()).isEqualTo(PaymentAttemptStatus.SUBMITTING);
        assertThat(ambiguous.status()).isEqualTo(PaymentAttemptStatus.UNKNOWN);
        assertThat(duplicateSuccess.status()).isEqualTo(PaymentAttemptStatus.SUCCEEDED);
        assertThat(policy.blocksNewAttempt(ambiguous.status())).isTrue();
    }

    @Test
    void legacyReviewCannotBeResolvedByUntrustedNormalizedEvents() {
        assertThat(policy.decide(PaymentAttemptStatus.LEGACY_REVIEW, PaymentAttemptEvent.SUCCESS, OrderStatus.PENDING))
                .isEqualTo(new PaymentAttemptDecision(
                        PaymentAttemptStatus.LEGACY_REVIEW, PaymentAttemptDisposition.RECONCILIATION_REQUIRED, false));
        assertThat(policy.blocksNewAttempt(PaymentAttemptStatus.LEGACY_REVIEW)).isTrue();
    }

    @Test
    void repeatedDispatchClaimCannotSubmitTheSameAttemptTwice() {
        PaymentAttemptDecision firstClaim = policy.decide(
                PaymentAttemptStatus.INITIATED, PaymentAttemptEvent.SUBMISSION_CLAIMED, OrderStatus.PENDING);
        PaymentAttemptDecision secondClaim = policy.decide(
                firstClaim.status(), PaymentAttemptEvent.SUBMISSION_CLAIMED, OrderStatus.PENDING);

        assertThat(firstClaim.status()).isEqualTo(PaymentAttemptStatus.SUBMITTING);
        assertThat(secondClaim.status()).isEqualTo(PaymentAttemptStatus.SUBMITTING);
        assertThat(secondClaim.disposition()).isEqualTo(PaymentAttemptDisposition.IGNORED_OUT_OF_ORDER);
    }

    @Test
    void verifiedIpnBeforeCreateResponseCannotBeDowngradedByLateAcceptedResponse() {
        PaymentAttemptDecision ipn = policy.decide(
                PaymentAttemptStatus.SUBMITTING, PaymentAttemptEvent.SUCCESS, OrderStatus.PENDING);
        PaymentAttemptDecision lateCreateResponse = policy.decide(
                ipn.status(), PaymentAttemptEvent.PROVIDER_ACCEPTED, OrderStatus.PROCESSING);

        assertThat(ipn.status()).isEqualTo(PaymentAttemptStatus.SUCCEEDED);
        assertThat(lateCreateResponse.status()).isEqualTo(PaymentAttemptStatus.SUCCEEDED);
        assertThat(lateCreateResponse.disposition()).isEqualTo(PaymentAttemptDisposition.IGNORED_OUT_OF_ORDER);
    }

    @Test
    void verifiedLateSuccessForCanceledAttemptRetainsRetryLockUntilReconciliation() {
        PaymentAttemptDecision lateSuccess = policy.decide(
                PaymentAttemptStatus.CANCELED, PaymentAttemptEvent.SUCCESS, OrderStatus.CANCELED);

        assertThat(lateSuccess.status()).isEqualTo(PaymentAttemptStatus.CANCELED);
        assertThat(lateSuccess.disposition()).isEqualTo(PaymentAttemptDisposition.RECONCILIATION_REQUIRED);
        assertThat(policy.blocksNewAttempt(lateSuccess.status(), true)).isTrue();
        assertThat(policy.blocksNewAttempt(lateSuccess.status(), false)).isFalse();
    }
}
