package com.example.subscription.api;

import com.example.subscription.model.SubscriptionStatus;
import com.example.subscription.service.UserWallet;
import com.example.subscription.workflow.SubscriptionWorkflow;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
public class SubscriptionController {

    private final WorkflowClient client;
    private final UserWallet wallet;

    public SubscriptionController(WorkflowClient client, UserWallet wallet) {
        this.client = client;
        this.wallet = wallet;
    }

    @PostMapping("/subscribe")
    public Map<String, String> subscribe(@RequestParam(required = false, defaultValue = "100.0") double initialBalance) {
        String subscriptionId = "sub-" + System.currentTimeMillis();

        // Set initial balance for this subscription
        wallet.addBalance(subscriptionId, initialBalance);

        SubscriptionWorkflow workflow =
                client.newWorkflowStub(
                        SubscriptionWorkflow.class,
                        WorkflowOptions.newBuilder()
                                .setWorkflowId(subscriptionId)
                                .setTaskQueue("SUBSCRIPTION_TASK_QUEUE")
                                .build()
                );

        WorkflowClient.start(workflow::start, subscriptionId);

        Map<String, String> response = new HashMap<>();
        response.put("subscriptionId", subscriptionId);
        response.put("initialBalance", String.valueOf(initialBalance));
        response.put("subscriptionPrice", String.valueOf(wallet.getSubscriptionPrice()));
        response.put("status", "STARTED");

        return response;
    }

    @PostMapping("/resume/{id}")
    public Map<String, String> resume(@PathVariable String id) {
        client.newWorkflowStub(SubscriptionWorkflow.class, id).resume();

        Map<String, String> response = new HashMap<>();
        response.put("message", "Resume signal sent to subscription: " + id);
        response.put("action", "RESUMED");
        return response;
    }

    @PostMapping("/cancel/{id}")
    public Map<String, String> cancel(@PathVariable String id) {
        client.newWorkflowStub(SubscriptionWorkflow.class, id).cancel();

        Map<String, String> response = new HashMap<>();
        response.put("message", "Cancel signal sent to subscription: " + id);
        response.put("action", "CANCELLED");
        return response;
    }

    @GetMapping("/status/{id}")
    public Map<String, Object> getStatus(@PathVariable String id) {
        SubscriptionWorkflow workflow = client.newWorkflowStub(SubscriptionWorkflow.class, id);
        SubscriptionStatus status = workflow.getStatus();

        double balance = wallet.getBalance(id);

        Map<String, Object> response = new HashMap<>();
        response.put("subscriptionId", id);
        response.put("state", status.getState());
        response.put("billingCycle", status.getBillingCycle());
        response.put("retryAttempts", status.getRetryAttempts());
        response.put("lastPaymentStatus", status.getLastPaymentStatus());
        response.put("totalPaymentsProcessed", status.getTotalPaymentsProcessed());
        response.put("currentBalance", balance);
        response.put("subscriptionPrice", wallet.getSubscriptionPrice());

        return response;
    }

    @PostMapping("/wallet/{id}/add")
    public Map<String, Object> addBalance(@PathVariable String id, @RequestParam double amount) {
        wallet.addBalance(id, amount);
        double newBalance = wallet.getBalance(id);

        Map<String, Object> response = new HashMap<>();
        response.put("subscriptionId", id);
        response.put("amountAdded", amount);
        response.put("newBalance", newBalance);
        response.put("message", "Balance updated successfully");

        return response;
    }

    @GetMapping("/wallet/{id}")
    public Map<String, Object> getBalance(@PathVariable String id) {
        double balance = wallet.getBalance(id);

        Map<String, Object> response = new HashMap<>();
        response.put("subscriptionId", id);
        response.put("balance", balance);
        response.put("subscriptionPrice", wallet.getSubscriptionPrice());
        response.put("canSubscribe", wallet.hasBalance(id));

        return response;
    }

    @PostMapping("/wallet/{id}/charge")
    public Map<String, Object> chargeWallet(@PathVariable String id) {
        double price = wallet.getSubscriptionPrice();
        boolean success = wallet.charge(id, price);

        if (!success) {
            throw new RuntimeException("Insufficient funds");
        }

        double newBalance = wallet.getBalance(id);

        Map<String, Object> response = new HashMap<>();
        response.put("subscriptionId", id);
        response.put("amountCharged", price);
        response.put("newBalance", newBalance);
        response.put("message", "Payment processed successfully");

        return response;
    }

    // Failure simulation endpoints
    @PostMapping("/simulate/gateway-down/enable")
    public Map<String, String> enableGatewayDown() {
        wallet.enableGatewayDown();
        Map<String, String> response = new HashMap<>();
        response.put("message", "Gateway down simulation ENABLED");
        response.put("status", "All payment attempts will fail until disabled");
        return response;
    }

    @PostMapping("/simulate/gateway-down/disable")
    public Map<String, String> disableGatewayDown() {
        wallet.disableGatewayDown();
        Map<String, String> response = new HashMap<>();
        response.put("message", "Gateway down simulation DISABLED");
        response.put("status", "Payments will now process normally");
        return response;
    }

    @GetMapping("/simulate/gateway-down/status")
    public Map<String, Object> getGatewayDownStatus() {
        Map<String, Object> response = new HashMap<>();
        response.put("gatewayDown", wallet.isGatewayDown());
        return response;
    }
}
