package com.example.subscription.workflow;

import com.example.subscription.activity.PaymentActivity;
import com.example.subscription.model.SubscriptionStatus;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Workflow;

import java.time.Duration;

public class SubscriptionWorkflowImplV2 implements SubscriptionWorkflow {

    private static final String VERSION = "v2.0-grace-period";
    private static final int MAX_BILLING_CYCLES = 12;
    private static final Duration PAUSE_TIMEOUT = Duration.ofMinutes(3);
    private static final Duration GRACE_PERIOD = Duration.ofSeconds(30);  // NEW: Single grace period

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

            currentRetryAttempts = 0;
            boolean success = false;

            Workflow.getLogger(this.getClass())
                    .info("Starting billing cycle {} for subscription {}", billingCycle, subscriptionId);

            // Try payment with 3 retries
            while (currentRetryAttempts < 3 && !cancelled) {
                try {
                    currentState = "PROCESSING_PAYMENT";
                    paymentActivity.charge(subscriptionId);
                    success = true;
                    lastPaymentStatus = "SUCCESS";
                    totalPaymentsProcessed++;
                    currentState = "ACTIVE";
                    gatewayIssue = false;
                    Workflow.getLogger(this.getClass())
                            .info("Payment successful for cycle {} (total: {})", billingCycle, totalPaymentsProcessed);
                    break;
                } catch (Exception e) {
                    currentRetryAttempts++;
                    lastPaymentStatus = "FAILED - " + e.getMessage();
                    currentState = "RETRYING";

                    String fullError = getFullErrorMessage(e);
                    gatewayIssue = fullError.toLowerCase().contains("gateway") &&
                                   fullError.toLowerCase().contains("unavailable");

                    Workflow.getLogger(this.getClass())
                            .warn("Payment failed (attempt {}/3): {}", currentRetryAttempts, e.getMessage());

                    if (currentRetryAttempts < 3) {
                        Workflow.sleep(Duration.ofSeconds(5));
                    }
                }
            }

            if (!success && !cancelled) {
                if (gatewayIssue) {
                    // Gateway down - wait and retry automatically
                    currentState = "WAITING_FOR_GATEWAY";
                    Workflow.getLogger(this.getClass())
                            .warn("Payment gateway unavailable. Waiting 30 seconds before retry (cycle {})...",
                                  billingCycle);

                    Workflow.sleep(Duration.ofSeconds(30));
                    currentRetryAttempts = 0;

                    Workflow.getLogger(this.getClass())
                            .info("Retrying payment after gateway wait (cycle {})...", billingCycle);
                    continue;

                } else {
                    // NEW: Grace period logic
                    currentState = "GRACE_PERIOD";

                    Workflow.getLogger(this.getClass()).warn(
                        "⏰ Entering 30-second GRACE PERIOD (v2.0 feature)"
                    );

                    // Sleep for grace period
                    Workflow.sleep(GRACE_PERIOD);

                    // Try one more time after grace
                    try {
                        Workflow.getLogger(this.getClass()).info(
                            "Attempting payment after grace period..."
                        );
                        paymentActivity.charge(subscriptionId);

                        // Success!
                        success = true;
                        lastPaymentStatus = "SUCCESS_AFTER_GRACE";
                        totalPaymentsProcessed++;
                        currentState = "ACTIVE";

                        Workflow.getLogger(this.getClass()).info(
                            "✅ Payment successful after grace period for cycle {} (total: {})",
                            billingCycle, totalPaymentsProcessed
                        );

                    } catch (Exception e) {
                        // Still failed - now pause
                        paused = true;
                        currentState = "PAUSED";

                        Workflow.getLogger(this.getClass()).warn(
                            "Payment failed after grace period. Pausing. Waiting for resume signal (max 3 minutes)..."
                        );

                        // Wait for resume with timeout
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
                }
            }

            // Continue to next billing cycle
            if (!cancelled && success) {
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
