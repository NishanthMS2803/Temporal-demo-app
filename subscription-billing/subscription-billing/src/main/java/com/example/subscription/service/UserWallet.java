package com.example.subscription.service;

import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;

@Service
public class UserWallet {

    // Shared static storage to simulate external database
    // In real world, this would be a database or external service
    private static final ConcurrentHashMap<String, Double> balances = new ConcurrentHashMap<>();
    private static final double SUBSCRIPTION_PRICE = 10.0;

    // Simulation flags (shared across all instances in API's JVM)
    private static volatile boolean gatewayDown = false;

    public UserWallet() {
        // Empty constructor - data persists across instances
    }

    public double getBalance(String subscriptionId) {
        return balances.getOrDefault(subscriptionId, 0.0);
    }

    public void addBalance(String subscriptionId, double amount) {
        balances.merge(subscriptionId, amount, Double::sum);
    }

    public boolean charge(String subscriptionId, double amount) {
        Double balance = balances.get(subscriptionId);
        if (balance == null || balance < amount) {
            return false;
        }
        balances.put(subscriptionId, balance - amount);
        return true;
    }

    public boolean hasBalance(String subscriptionId) {
        return getBalance(subscriptionId) >= SUBSCRIPTION_PRICE;
    }

    public double getSubscriptionPrice() {
        return SUBSCRIPTION_PRICE;
    }

    // Gateway simulation methods
    public void enableGatewayDown() {
        gatewayDown = true;
    }

    public void disableGatewayDown() {
        gatewayDown = false;
    }

    public boolean isGatewayDown() {
        return gatewayDown;
    }
}
