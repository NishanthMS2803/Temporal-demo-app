package com.example.subscription.worker;

import com.example.subscription.activity.PaymentActivityImpl;
import com.example.subscription.workflow.SubscriptionWorkflowImpl;
import io.temporal.client.WorkflowClient;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;

public class WorkerApp {

    public static void main(String[] args) {

        WorkflowServiceStubs service =
                WorkflowServiceStubs.newInstance();

        WorkflowClient client =
                WorkflowClient.newInstance(service);

        WorkerFactory factory =
                WorkerFactory.newInstance(client);

        Worker worker =
                factory.newWorker("SUBSCRIPTION_TASK_QUEUE");

        worker.registerWorkflowImplementationTypes(
                SubscriptionWorkflowImpl.class);

        worker.registerActivitiesImplementations(
                new PaymentActivityImpl());

        factory.start();

        System.out.println("✓ Worker started and listening on SUBSCRIPTION_TASK_QUEUE...");
        System.out.println("✓ Ready to process subscription workflows");
    }
}
