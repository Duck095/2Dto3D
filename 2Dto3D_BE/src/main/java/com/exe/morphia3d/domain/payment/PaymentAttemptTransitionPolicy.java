package com.exe.morphia3d.domain.payment;

import com.exe.morphia3d.enums.OrderStatus;

import java.util.Objects;

/** Pure payment-attempt state policy. It does not persist state, execute refunds, or change an order. */
public final class PaymentAttemptTransitionPolicy {

    public PaymentAttemptDecision decide(
            PaymentAttemptStatus currentStatus,
            PaymentAttemptEvent event,
            OrderStatus orderStatus) {
        Objects.requireNonNull(currentStatus, "currentStatus is required");
        Objects.requireNonNull(event, "event is required");
        Objects.requireNonNull(orderStatus, "orderStatus is required");

        if (currentStatus == PaymentAttemptStatus.LEGACY_REVIEW) {
            return decision(currentStatus, PaymentAttemptDisposition.RECONCILIATION_REQUIRED);
        }

        if (event == PaymentAttemptEvent.SUCCESS) {
            return successDecision(currentStatus, orderStatus);
        }

        return switch (event) {
            case SUBMISSION_CLAIMED -> currentStatus == PaymentAttemptStatus.INITIATED
                    ? decision(PaymentAttemptStatus.SUBMITTING, PaymentAttemptDisposition.APPLIED)
                    : decision(currentStatus, PaymentAttemptDisposition.IGNORED_OUT_OF_ORDER);
            case PROVIDER_ACCEPTED -> currentStatus == PaymentAttemptStatus.SUBMITTING
                    ? decision(PaymentAttemptStatus.PENDING, PaymentAttemptDisposition.APPLIED)
                    : decision(currentStatus, PaymentAttemptDisposition.IGNORED_OUT_OF_ORDER);
            case AMBIGUOUS_RESULT -> currentStatus == PaymentAttemptStatus.SUBMITTING
                    ? decision(PaymentAttemptStatus.UNKNOWN, PaymentAttemptDisposition.APPLIED)
                    : decision(currentStatus, PaymentAttemptDisposition.IGNORED_OUT_OF_ORDER);
            case FAILURE -> currentStatus == PaymentAttemptStatus.PENDING
                    ? decision(PaymentAttemptStatus.FAILED, PaymentAttemptDisposition.APPLIED)
                    : currentStatus == PaymentAttemptStatus.SUBMITTING || currentStatus == PaymentAttemptStatus.UNKNOWN
                    ? decision(PaymentAttemptStatus.FAILED, PaymentAttemptDisposition.APPLIED)
                    : currentStatus == PaymentAttemptStatus.FAILED
                    ? decision(currentStatus, PaymentAttemptDisposition.DUPLICATE)
                    : decision(currentStatus, PaymentAttemptDisposition.IGNORED_OUT_OF_ORDER);
            case EXPIRE -> currentStatus == PaymentAttemptStatus.PENDING
                    ? decision(PaymentAttemptStatus.EXPIRED, PaymentAttemptDisposition.APPLIED)
                    : currentStatus == PaymentAttemptStatus.SUBMITTING || currentStatus == PaymentAttemptStatus.UNKNOWN
                    ? decision(PaymentAttemptStatus.EXPIRED, PaymentAttemptDisposition.APPLIED)
                    : currentStatus == PaymentAttemptStatus.EXPIRED
                    ? decision(currentStatus, PaymentAttemptDisposition.DUPLICATE)
                    : decision(currentStatus, PaymentAttemptDisposition.IGNORED_OUT_OF_ORDER);
            case CANCEL -> currentStatus == PaymentAttemptStatus.PENDING
                    || currentStatus == PaymentAttemptStatus.SUBMITTING || currentStatus == PaymentAttemptStatus.UNKNOWN
                    ? decision(PaymentAttemptStatus.CANCELED, PaymentAttemptDisposition.APPLIED)
                    : currentStatus == PaymentAttemptStatus.CANCELED
                    ? decision(currentStatus, PaymentAttemptDisposition.DUPLICATE)
                    : decision(currentStatus, PaymentAttemptDisposition.IGNORED_OUT_OF_ORDER);
            case PARTIAL_REFUND -> currentStatus == PaymentAttemptStatus.SUCCEEDED
                    ? decision(PaymentAttemptStatus.PARTIALLY_REFUNDED, PaymentAttemptDisposition.APPLIED)
                    : decision(currentStatus, PaymentAttemptDisposition.IGNORED_OUT_OF_ORDER);
            case FULL_REFUND -> currentStatus == PaymentAttemptStatus.SUCCEEDED
                    || currentStatus == PaymentAttemptStatus.PARTIALLY_REFUNDED
                    ? decision(PaymentAttemptStatus.REFUNDED, PaymentAttemptDisposition.APPLIED)
                    : currentStatus == PaymentAttemptStatus.REFUNDED
                    ? decision(currentStatus, PaymentAttemptDisposition.DUPLICATE)
                    : decision(currentStatus, PaymentAttemptDisposition.IGNORED_OUT_OF_ORDER);
            case SUCCESS -> throw new IllegalStateException("Success event must use success policy");
        };
    }

    /** Prevents a second attempt while an attempt is unresolved or has established successful payment. */
    public boolean blocksNewAttempt(PaymentAttemptStatus status) {
        return blocksNewAttempt(status, false);
    }

    /** A durable unresolved reconciliation case blocks retry even when the old attempt is terminal. */
    public boolean blocksNewAttempt(PaymentAttemptStatus status, boolean unresolvedReconciliation) {
        Objects.requireNonNull(status, "status is required");
        if (unresolvedReconciliation) {
            return true;
        }
        return switch (status) {
            case INITIATED, SUBMITTING, PENDING, UNKNOWN, LEGACY_REVIEW,
                    SUCCEEDED, PARTIALLY_REFUNDED, REFUNDED -> true;
            case FAILED, EXPIRED, CANCELED -> false;
        };
    }

    private PaymentAttemptDecision successDecision(PaymentAttemptStatus currentStatus, OrderStatus orderStatus) {
        if (currentStatus == PaymentAttemptStatus.SUBMITTING
                || currentStatus == PaymentAttemptStatus.PENDING
                || currentStatus == PaymentAttemptStatus.UNKNOWN) {
            if (orderStatus == OrderStatus.PENDING) {
                return new PaymentAttemptDecision(
                        PaymentAttemptStatus.SUCCEEDED,
                        PaymentAttemptDisposition.APPLIED,
                        true);
            }
            return decision(PaymentAttemptStatus.SUCCEEDED, PaymentAttemptDisposition.RECONCILIATION_REQUIRED);
        }

        if (currentStatus == PaymentAttemptStatus.FAILED || currentStatus == PaymentAttemptStatus.EXPIRED) {
            return decision(currentStatus, PaymentAttemptDisposition.RECONCILIATION_REQUIRED);
        }

        if (orderStatus == OrderStatus.CANCELED) {
            return decision(currentStatus, PaymentAttemptDisposition.RECONCILIATION_REQUIRED);
        }

        return decision(currentStatus, PaymentAttemptDisposition.DUPLICATE);
    }

    private PaymentAttemptDecision decision(PaymentAttemptStatus status, PaymentAttemptDisposition disposition) {
        return new PaymentAttemptDecision(status, disposition, false);
    }
}
