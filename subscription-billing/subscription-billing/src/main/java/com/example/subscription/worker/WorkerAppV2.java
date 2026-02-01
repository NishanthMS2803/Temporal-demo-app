package com.example.subscription.worker;

import com.example.subscription.activity.PaymentActivityImpl;
import com.example.subscription.workflow.SubscriptionWorkflowImplV2;
import io.temporal.client.WorkflowClient;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import io.temporal.worker.WorkerOptions;

public class WorkerAppV2 {

    private static final String BUILD_ID = "v2.0-grace-period";

    public static void main(String[] args) {

        WorkflowServiceStubs service =
                WorkflowServiceStubs.newInstance();

        WorkflowClient client =
                WorkflowClient.newInstance(service);

        WorkerFactory factory =
                WorkerFactory.newInstance(client);

        WorkerOptions options = WorkerOptions.newBuilder()
            .setBuildId(BUILD_ID)
            .setUseBuildIdForVersioning(true)
            .build();

        Worker worker =
                factory.newWorker("SUBSCRIPTION_TASK_QUEUE", options);

        worker.registerWorkflowImplementationTypes(
                SubscriptionWorkflowImplV2.class);

        worker.registerActivitiesImplementations(
                new PaymentActivityImpl());

        factory.start();

        System.out.println("✅ Worker v2.0 started with build ID: " + BUILD_ID);
        System.out.println("🆕 NEW FEATURE: 30-second grace period before pausing");
        System.out.println("📋 Max billing cycles: 12");
        System.out.println("⏰ Pause timeout: 3 minutes");
        System.out.println("✓ Ready to process subscription workflows");
    }
}
