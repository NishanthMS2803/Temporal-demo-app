package com.example.subscription.model;

public class SubscriptionStatus {
    private String subscriptionId;
    private String state;
    private int billingCycle;
    private int retryAttempts;
    private double balance;
    private String lastPaymentStatus;
    private long totalPaymentsProcessed;

    public SubscriptionStatus() {
    }

    public SubscriptionStatus(String subscriptionId, String state, int billingCycle,
                             int retryAttempts, double balance, String lastPaymentStatus,
                             long totalPaymentsProcessed) {
        this.subscriptionId = subscriptionId;
        this.state = state;
        this.billingCycle = billingCycle;
        this.retryAttempts = retryAttempts;
        this.balance = balance;
        this.lastPaymentStatus = lastPaymentStatus;
        this.totalPaymentsProcessed = totalPaymentsProcessed;
    }

    // Getters and setters
    public String getSubscriptionId() {
        return subscriptionId;
    }

    public void setSubscriptionId(String subscriptionId) {
        this.subscriptionId = subscriptionId;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public int getBillingCycle() {
        return billingCycle;
    }

    public void setBillingCycle(int billingCycle) {
        this.billingCycle = billingCycle;
    }

    public int getRetryAttempts() {
        return retryAttempts;
    }

    public void setRetryAttempts(int retryAttempts) {
        this.retryAttempts = retryAttempts;
    }

    public double getBalance() {
        return balance;
    }

    public void setBalance(double balance) {
        this.balance = balance;
    }

    public String getLastPaymentStatus() {
        return lastPaymentStatus;
    }

    public void setLastPaymentStatus(String lastPaymentStatus) {
        this.lastPaymentStatus = lastPaymentStatus;
    }

    public long getTotalPaymentsProcessed() {
        return totalPaymentsProcessed;
    }

    public void setTotalPaymentsProcessed(long totalPaymentsProcessed) {
        this.totalPaymentsProcessed = totalPaymentsProcessed;
    }
}
