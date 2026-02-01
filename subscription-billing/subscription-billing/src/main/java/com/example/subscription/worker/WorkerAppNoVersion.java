package com.example.subscription.worker;

import com.example.subscription.activity.PaymentActivityImpl;
import com.example.subscription.workflow.SubscriptionWorkflowImpl;
import io.temporal.client.WorkflowClient;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;

public class WorkerAppNoVersion {

    public static void main(String[] args) {

        WorkflowServiceStubs service =
                WorkflowServiceStubs.newInstance();

        WorkflowClient client =
                WorkflowClient.newInstance(service);

        WorkerFactory factory =
                WorkerFactory.newInstance(client);

        // Worker WITHOUT versioning (for testing when versioning is disabled)
        Worker worker =
                factory.newWorker("SUBSCRIPTION_TASK_QUEUE");

        worker.registerWorkflowImplementationTypes(
                SubscriptionWorkflowImpl.class);

        worker.registerActivitiesImplementations(
                new PaymentActivityImpl());

        factory.start();

        System.out.println("✅ Worker started (NO VERSIONING) - for testing only");
        System.out.println("📋 Max billing cycles: 12");
        System.out.println("⏰ Pause timeout: 3 minutes");
        System.out.println("✓ Ready to process subscription workflows");
    }
}
