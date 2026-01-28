package com.example.subscription.workflow;

import com.example.subscription.activity.PaymentActivity;
import com.example.subscription.model.SubscriptionStatus;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Workflow;

import java.time.Duration;

public class SubscriptionWorkflowImpl implements SubscriptionWorkflow {

    private boolean paused = false;
    private boolean cancelled = false;
    private boolean gatewayIssue = false;  // Track if last failure was gateway-related
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
                .info("Subscription started: {}", subscriptionId);

        while (!cancelled) {
            billingCycle++;
            currentRetryAttempts = 0;
            boolean success = false;

            Workflow.getLogger(this.getClass())
                    .info("Starting billing cycle {} for subscription {}", billingCycle, subscriptionId);

            while (currentRetryAttempts < 3 && !cancelled) {
                try {
                    currentState = "PROCESSING_PAYMENT";
                    paymentActivity.charge(subscriptionId);
                    success = true;
                    lastPaymentStatus = "SUCCESS";
                    totalPaymentsProcessed++;
                    currentState = "ACTIVE";
                    gatewayIssue = false;  // Clear flag on success
                    Workflow.getLogger(this.getClass())
                            .info("Payment successful for cycle {} (total: {})", billingCycle, totalPaymentsProcessed);
                    break;
                } catch (Exception e) {
                    currentRetryAttempts++;
                    lastPaymentStatus = "FAILED - " + e.getMessage();
                    currentState = "RETRYING";

                    // Check if this is a gateway issue by examining the full exception chain
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
                // gatewayIssue flag was already set in the catch block above

                if (gatewayIssue) {
                    // Gateway down - wait and retry automatically (no manual intervention needed)
                    currentState = "WAITING_FOR_GATEWAY";
                    Workflow.getLogger(this.getClass())
                            .warn("Payment gateway unavailable. Waiting 30 seconds before retry (cycle {})...",
                                  billingCycle);

                    Workflow.sleep(Duration.ofSeconds(30)); // Wait 30 seconds
                    currentRetryAttempts = 0; // Reset retry counter

                    // Continue loop - will retry billing cycle
                    Workflow.getLogger(this.getClass())
                            .info("Retrying payment after gateway wait (cycle {})...", billingCycle);
                    continue; // Skip the sleep at bottom, retry immediately

                } else {
                    // Insufficient funds - pause and wait for manual intervention
                    paused = true;
                    gatewayIssue = false;
                    currentState = "PAUSED";
                    Workflow.getLogger(this.getClass())
                            .warn("Subscription PAUSED after {} failed attempts in cycle {} (insufficient funds)",
                                  currentRetryAttempts, billingCycle);

                    // Wait indefinitely until resume signal is received or workflow is cancelled
                    Workflow.await(() -> !paused || cancelled);

                    if (cancelled) {
                        break;
                    }

                    currentRetryAttempts = 0; // Reset retry counter after resume
                    Workflow.getLogger(this.getClass())
                            .info("Subscription RESUMED, continuing from cycle {}", billingCycle);
                }
            }

            if (!cancelled && success) {
                Workflow.getLogger(this.getClass())
                        .info("Waiting 1 minute until next billing cycle...");
                Workflow.sleep(Duration.ofMinutes(1)); // monthly billing simulation
            }
        }

        currentState = "CANCELLED";
        Workflow.getLogger(this.getClass())
                .info("Subscription cancelled after {} cycles and {} successful payments", billingCycle, totalPaymentsProcessed);
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
                0.0, // Balance will be queried separately via API
                lastPaymentStatus,
                totalPaymentsProcessed
        );
    }

    @Override
    public String getCurrentState() {
        return currentState;
    }

    /**
     * Extract full error message including all causes in the exception chain.
     * This helps detect gateway issues even when wrapped in Temporal exceptions.
     */
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
