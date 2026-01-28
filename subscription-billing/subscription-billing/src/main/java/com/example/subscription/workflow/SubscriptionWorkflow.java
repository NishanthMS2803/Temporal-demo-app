package com.example.subscription.workflow;

import com.example.subscription.model.SubscriptionStatus;
import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

@WorkflowInterface
public interface SubscriptionWorkflow {

    @WorkflowMethod
    void start(String subscriptionId);

    @SignalMethod
    void resume();

    @SignalMethod
    void cancel();

    @QueryMethod
    SubscriptionStatus getStatus();

    @QueryMethod
    String getCurrentState();
}
