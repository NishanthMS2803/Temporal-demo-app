package com.example.subscription.worker;

import com.example.subscription.activity.PaymentActivityImpl;
import com.example.subscription.workflow.SubscriptionWorkflowImpl;
import io.temporal.client.WorkflowClient;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import io.temporal.worker.WorkerOptions;

public class WorkerApp {

    private static final String BUILD_ID = "v1.0-immediate-pause";

    public static void main(String[] args) {

        WorkflowServiceStubs service =
                WorkflowServiceStubs.newInstance();

        WorkflowClient client =
                WorkflowClient.newInstance(service);

        WorkerFactory factory =
                WorkerFactory.newInstance(client);

        // NEW: Configure worker with versioning
        WorkerOptions options = WorkerOptions.newBuilder()
            .setBuildId(BUILD_ID)                    // Version identifier
            .setUseBuildIdForVersioning(true)        // Enable versioning
            .build();

        Worker worker =
                factory.newWorker("SUBSCRIPTION_TASK_QUEUE", options);

        worker.registerWorkflowImplementationTypes(
                SubscriptionWorkflowImpl.class);

        worker.registerActivitiesImplementations(
                new PaymentActivityImpl());

        factory.start();

        System.out.println("✅ Worker v1.0 started with build ID: " + BUILD_ID);
        System.out.println("📋 Max billing cycles: 12");
        System.out.println("⏰ Pause timeout: 3 minutes");
        System.out.println("✓ Ready to process subscription workflows");
    }
}
