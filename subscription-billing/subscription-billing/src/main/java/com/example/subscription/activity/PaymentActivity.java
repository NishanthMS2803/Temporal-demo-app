package com.example.subscription.activity;

import io.temporal.activity.ActivityInterface;

@ActivityInterface
public interface PaymentActivity {
    void charge(String subscriptionId);
}
