package com.example.subscription.workflow;

import com.example.subscription.activity.PaymentActivity;
import com.example.subscription.model.SubscriptionStatus;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Workflow;

import java.time.Duration;

public class SubscriptionWorkflowImplV3 implements SubscriptionWorkflow {

    private static final String VERSION = "v3.0-escalating-grace";
    private static final int MAX_BILLING_CYCLES = 12;
    private static final Duration PAUSE_TIMEOUT = Duration.ofMinutes(3);

    // NEW: Multiple grace periods
    private static final Duration GRACE_PERIOD_1 = Duration.ofSeconds(10);
    private static final Duration GRACE_PERIOD_2 = Duration.ofSeconds(20);
    private static final Duration GRACE_PERIOD_3 = Duration.ofSeconds(30);

    private boolean paused = false;
    private boolean cancelled = false;
    private boolean gatewayIssue = false;
    private String currentState = "ACTIVE";
    private int billingCycle = 0;
    private int currentRetryAttempts = 0;
    private String lastPaymentStatus = "NOT_STARTED";
    private long totalPaymentsProcessed = 0;
    private String subscriptionId;

    private final ActivityOptions activityOptions =
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofSeconds(5))
                    .setRetryOptions(RetryOptions.newBuilder()
                            .setMaximumAttempts(1)
                            .build())
                    .build();

    private final PaymentActivity paymentActivity =
            Workflow.newActivityStub(PaymentActivity.class, activityOptions);

    @Override
    public void start(String subscriptionId) {
        this.subscriptionId = subscriptionId;
        currentState = "ACTIVE";

        Workflow.getLogger(this.getClass())
                .info("🆕 Starting subscription with VERSION: {} - subscriptionId: {}", VERSION, subscriptionId);

        while (!cancelled) {
            billingCycle++;

            // Check if reached max billing cycles
            if (totalPaymentsProcessed >= MAX_BILLING_CYCLES) {
                Workflow.getLogger(this.getClass()).info(
                    "✅ Subscription completed {} billing cycles. Ending workflow gracefully.",
                    MAX_BILLING_CYCLES
                );
                currentState = "COMPLETED_MAX_CYCLES";
                break;
            }

            Workflow.getLogger(this.getClass())
                    .info("Starting billing cycle {} for subscription {}", billingCycle, subscriptionId);

            // Initial 3 retry attempts
            boolean paymentSuccess = attemptPayment(3);

            // NEW: Escalating grace periods
            if (!paymentSuccess && !cancelled) {
                paymentSuccess = escalatingGracePeriods();
            }

            // If still failed, pause with timeout
            if (!paymentSuccess && !cancelled) {
                paused = true;
                currentState = "PAUSED";

                Workflow.getLogger(this.getClass()).warn(
                    "Subscription PAUSED after all retry attempts in cycle {}. Waiting for resume signal (max 3 minutes)...",
                    billingCycle
                );

                boolean resumed = Workflow.await(
                    PAUSE_TIMEOUT,
                    () -> !paused || cancelled
                );

                if (!resumed && !cancelled) {
                    cancelled = true;
                    currentState = "CANCELLED_PAUSE_TIMEOUT";
                    Workflow.getLogger(this.getClass()).warn(
                        "⏰ Subscription paused for 3 minutes without resume. Auto-cancelling."
                    );
                    break;
                }

                if (cancelled) break;
                currentState = "ACTIVE";
                currentRetryAttempts = 0;
            }

            // Next billing cycle
            if (!cancelled && paymentSuccess) {
                Workflow.getLogger(this.getClass())
                        .info("Waiting 1 minute until next billing cycle...");
                Workflow.sleep(Duration.ofMinutes(1));
            }
        }

        // Workflow completion
        if (!currentState.equals("CANCELLED_PAUSE_TIMEOUT") &&
            !currentState.equals("COMPLETED_MAX_CYCLES")) {
            currentState = "CANCELLED";
        }

        Workflow.getLogger(this.getClass()).info(
            "Subscription ended. Final state: {}, Cycles: {}, Payments: {}",
            currentState, billingCycle, totalPaymentsProcessed
        );
    }

    private boolean attemptPayment(int maxAttempts) {
        currentRetryAttempts = 0;
        while (currentRetryAttempts < maxAttempts && !cancelled) {
            try {
                currentState = "PROCESSING_PAYMENT";
                paymentActivity.charge(subscriptionId);
                // Success
                lastPaymentStatus = "SUCCESS";
                totalPaymentsProcessed++;
                currentState = "ACTIVE";
                gatewayIssue = false;
                Workflow.getLogger(this.getClass())
                        .info("Payment successful for cycle {} (total: {})", billingCycle, totalPaymentsProcessed);
                return true;
            } catch (Exception e) {
                currentRetryAttempts++;
                lastPaymentStatus = "FAILED - " + e.getMessage();
                currentState = "RETRYING";

                String fullError = getFullErrorMessage(e);
                gatewayIssue = fullError.toLowerCase().contains("gateway") &&
                               fullError.toLowerCase().contains("unavailable");

                Workflow.getLogger(this.getClass()).warn(
                    "Payment failed (attempt {}/{}): {}",
                    currentRetryAttempts, maxAttempts, e.getMessage()
                );

                // If gateway issue, handle separately
                if (gatewayIssue) {
                    if (currentRetryAttempts >= maxAttempts) {
                        currentState = "WAITING_FOR_GATEWAY";
                        Workflow.getLogger(this.getClass())
                                .warn("Payment gateway unavailable. Waiting 30 seconds before retry (cycle {})...",
                                      billingCycle);
                        Workflow.sleep(Duration.ofSeconds(30));
                        currentRetryAttempts = 0;
                        Workflow.getLogger(this.getClass())
                                .info("Retrying payment after gateway wait (cycle {})...", billingCycle);
                        // Return false to continue the loop (caller will retry)
                        return false;
                    }
                }

                if (currentRetryAttempts < maxAttempts) {
                    Workflow.sleep(Duration.ofSeconds(5));
                }
            }
        }
        return false;
    }

    private boolean escalatingGracePeriods() {
        // Grace period 1: 10 seconds + gentle reminder
        currentState = "GRACE_PERIOD_1";
        Workflow.getLogger(this.getClass()).warn(
            "⏰ Entering 10-second GRACE PERIOD (gentle reminder)"
        );
        Workflow.sleep(GRACE_PERIOD_1);

        if (attemptPayment(1)) {
            lastPaymentStatus = "SUCCESS_AFTER_GRACE_1";
            Workflow.getLogger(this.getClass()).info(
                "✅ Payment successful after grace period 1 for cycle {} (total: {})",
                billingCycle, totalPaymentsProcessed
            );
            return true;
        }

        // Grace period 2: 20 seconds + urgent reminder
        currentState = "GRACE_PERIOD_2";
        Workflow.getLogger(this.getClass()).warn(
            "⏰⏰ Entering 20-second GRACE PERIOD (urgent reminder)"
        );
        Workflow.sleep(GRACE_PERIOD_2);

        if (attemptPayment(1)) {
            lastPaymentStatus = "SUCCESS_AFTER_GRACE_2";
            Workflow.getLogger(this.getClass()).info(
                "✅ Payment successful after grace period 2 for cycle {} (total: {})",
                billingCycle, totalPaymentsProcessed
            );
            return true;
        }

        // Grace period 3: 30 seconds + final warning
        currentState = "GRACE_PERIOD_3";
        Workflow.getLogger(this.getClass()).warn(
            "⏰⏰⏰ Entering 30-second GRACE PERIOD (final warning)"
        );
        Workflow.sleep(GRACE_PERIOD_3);

        if (attemptPayment(1)) {
            lastPaymentStatus = "SUCCESS_AFTER_GRACE_3";
            Workflow.getLogger(this.getClass()).info(
                "✅ Payment successful after grace period 3 for cycle {} (total: {})",
                billingCycle, totalPaymentsProcessed
            );
            return true;
        }

        // All grace periods exhausted
        Workflow.getLogger(this.getClass()).error(
            "Payment failed after all grace periods. Will pause."
        );
        return false;
    }

    @Override
    public void resume() {
        paused = false;
        currentState = "ACTIVE";
        Workflow.getLogger(this.getClass()).info("Resume signal received");
    }

    @Override
    public void cancel() {
        cancelled = true;
        currentState = "CANCELLED";
        Workflow.getLogger(this.getClass()).info("Cancel signal received");
    }

    @Override
    public SubscriptionStatus getStatus() {
        return new SubscriptionStatus(
                subscriptionId,
                currentState,
                billingCycle,
                currentRetryAttempts,
                0.0,
                lastPaymentStatus,
                totalPaymentsProcessed
        );
    }

    @Override
    public String getCurrentState() {
        return currentState;
    }

    private String getFullErrorMessage(Exception e) {
        StringBuilder fullMessage = new StringBuilder();
        Throwable current = e;
        while (current != null) {
            if (current.getMessage() != null) {
                fullMessage.append(current.getMessage()).append(" ");
            }
            current = current.getCause();
        }
        return fullMessage.toString();
    }
}
