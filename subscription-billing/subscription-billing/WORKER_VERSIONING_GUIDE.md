# Worker Versioning & Rainbow Deployment - Complete Guide

This guide demonstrates Temporal's worker versioning capabilities through a practical subscription billing system. You'll learn how to safely deploy new workflow logic without disrupting existing workflows.

---

## 📚 Table of Contents

1. [Overview](#overview)
2. [Why Automatic Workflow Completion Matters](#why-automatic-workflow-completion-matters)
3. [Three Versions - Feature Evolution](#three-versions---feature-evolution)
4. [Version Lifecycle States](#version-lifecycle-states)
5. [Implementation Plan](#implementation-plan)
6. [Testing Scenarios](#testing-scenarios)
7. [Observability & Monitoring](#observability--monitoring)
8. [Troubleshooting](#troubleshooting)

---

## Overview

### What You'll Learn

- **Worker Versioning**: How to run multiple workflow versions simultaneously
- **Rainbow Deployment**: Gradual rollout with percentage-based traffic splitting
- **State Management**: Understanding INACTIVE, RAMPING, ACTIVE, DRAINING, DRAINED
- **Safe Migration**: Zero-downtime deployments with rollback capability
- **Production Patterns**: Real-world deployment strategies used by major tech companies

### The Challenge

In production, you need to deploy new workflow logic while:
- ✅ Keeping existing workflows running with their original logic
- ✅ Testing new logic with a small percentage of traffic
- ✅ Rolling back instantly if problems occur
- ✅ Gracefully retiring old versions
- ✅ Ensuring zero data loss and no duplicate operations

---

## Why Automatic Workflow Completion Matters

### The Problem: Indefinitely Running Workflows

Without completion conditions, workflows can run forever:

```
┌─────────────────┐
│ v1.0 Worker     │
│                 │
│ sub-001: PAUSED │ ← Stuck waiting for resume signal
│ sub-002: ACTIVE │ ← Running for months
│ sub-003: PAUSED │ ← Never completing
└─────────────────┘
```

**Issues this causes:**

1. **Cannot drain v1 workers**: Workers wait indefinitely for workflows to complete
2. **Blocks version migration**: v1 workers can never shut down cleanly
3. **Event history bloat**: Long-running workflows accumulate huge event histories
4. **Resource waste**: Old workers must stay running forever
5. **Testing difficulty**: Cannot observe complete version lifecycle in demos

### The Solution: Automatic Completion Conditions

We implement **two automatic completion rules**:

#### **Rule 1: Success Completion After 12 Billing Cycles**

```java
if (totalPaymentsProcessed >= 12) {
    // Gracefully complete and restart as new workflow
    Workflow.continueAsNew(subscriptionId);
}
```

**Benefits:**
- ✅ Prevents infinite event history growth
- ✅ Allows worker to complete its work
- ✅ Workflow can be restarted with latest version
- ✅ Demonstrates Temporal best practice (`continueAsNew`)

**What happens:**
1. Workflow completes after 12 successful payments (~12 minutes in demo)
2. Can be automatically restarted with **current default version**
3. Old worker processes the completion, then can drain
4. Customer subscription continues seamlessly (transparent restart)

#### **Rule 2: Auto-Cancel After 3 Minutes of Being Paused**

```java
if (currentState.equals("PAUSED")) {
    // Wait for resume OR 3 minutes timeout
    boolean resumed = Workflow.await(
        Duration.ofMinutes(3),
        () -> !paused || cancelled
    );

    if (!resumed) {
        // Auto-cancel after timeout
        Workflow.getLogger(this.getClass()).warn(
            "Subscription paused for 3 minutes. Auto-cancelling."
        );
        cancelled = true;
        currentState = "CANCELLED_AUTO_TIMEOUT";
    }
}
```

**Benefits:**
- ✅ Paused workflows don't block version draining forever
- ✅ Realistic business rule (subscriptions don't stay paused indefinitely)
- ✅ Allows testing complete version lifecycle in reasonable time
- ✅ Forces cleanup of abandoned subscriptions

**What happens:**
1. Workflow enters PAUSED state (insufficient funds)
2. Waits for resume signal OR 3-minute timeout
3. If no resume signal received, auto-cancels
4. Workflow completes, worker can drain
5. User can re-subscribe later (starting fresh with current version)

### Observable Version Draining Timeline

With these rules, you can observe the complete lifecycle:

```
Time 0:00 - Start v1 worker, create subscriptions
Time 0:05 - Deploy v2 worker (INACTIVE)
Time 0:10 - Promote v2 to ACTIVE, v1 becomes DRAINING
Time 0:15 - v1 workflows completing (12 cycles done) or auto-cancelling (paused 3min)
Time 0:20 - v1 worker fully DRAINED, can shut down
Time 0:25 - Deploy v3 with 20% ramp
Time 0:35 - Increase v3 to 50% ramp
Time 0:45 - Promote v3 to ACTIVE, v2 becomes DRAINING
Time 0:55 - v2 workflows completing
Time 1:00 - v2 worker fully DRAINED, can shut down
```

**Without these rules**: Workers would wait hours/days for manual intervention on paused workflows!

---

## Three Versions - Feature Evolution

### Version 1.0: Immediate Pause (Baseline)

**Behavior:**
```
3 payment retries (5 sec apart)
  ↓
All failed?
  ↓
PAUSE immediately (wait for resume signal indefinitely)
```

**Build ID:** `v1.0-immediate-pause`

**Key Characteristics:**
- Simple retry logic
- No grace period
- Quick to pause
- Harsh on customers (instant service suspension)

**When it completes:**
- After 12 successful billing cycles, OR
- After 3 minutes in PAUSED state (auto-cancel)

---

### Version 2.0: Single Grace Period (Improvement)

**Behavior:**
```
3 payment retries (5 sec apart)
  ↓
All failed?
  ↓
Enter 30-second GRACE PERIOD
  ↓
1 additional retry attempt
  ↓
Still failed?
  ↓
PAUSE (wait for resume signal)
```

**Build ID:** `v2.0-grace-period`

**Key Characteristics:**
- More customer-friendly
- 30-second buffer before pausing
- One extra chance to charge
- Better customer retention

**What you observe:**
- Worker logs: `"⏰ Entering 30-second GRACE PERIOD"`
- Status API shows: `"state": "GRACE_PERIOD"`
- Total failure time: ~45 seconds (3 retries + 30s grace + 1 retry)

**When it completes:**
- After 12 successful billing cycles, OR
- After 3 minutes in PAUSED state (auto-cancel)

---

### Version 3.0: Escalating Grace Periods (Advanced)

**Behavior:**
```
3 payment retries (5 sec apart)
  ↓
All failed?
  ↓
10-second grace period + "gentle reminder"
  ↓
1 retry attempt
  ↓
Still failed?
  ↓
20-second grace period + "urgent reminder"
  ↓
1 retry attempt
  ↓
Still failed?
  ↓
30-second grace period + "final warning"
  ↓
1 retry attempt
  ↓
Still failed?
  ↓
PAUSE (wait for resume signal)
```

**Build ID:** `v3.0-escalating-grace`

**Key Characteristics:**
- Progressive customer communication
- Multiple grace periods
- Three extra chances to charge
- Maximum customer retention
- Simulates real-world notification systems

**What you observe:**
- Worker logs show **three distinct grace periods**:
  ```
  [WARN] ⏰ Entering 10-second GRACE PERIOD (gentle reminder)
  [WARN] ⏰ Entering 20-second GRACE PERIOD (urgent reminder)
  [WARN] ⏰ Entering 30-second GRACE PERIOD (final warning)
  ```
- Status API cycles through: `"GRACE_PERIOD_1"` → `"GRACE_PERIOD_2"` → `"GRACE_PERIOD_3"`
- Total failure time: ~90 seconds (3 retries + 10s + 20s + 30s + 3 retries)

**When it completes:**
- After 12 successful billing cycles, OR
- After 3 minutes in PAUSED state (auto-cancel)

---

## Version Lifecycle States

Understanding worker states is crucial for observing version migrations.

### State Definitions

#### 1. **INACTIVE** (Not Receiving Traffic)

```
┌─────────────────┐
│  v3.0: INACTIVE │ ← Worker deployed but not in task queue default set
└─────────────────┘
```

**Characteristics:**
- Worker process is running and healthy
- Connected to Temporal server
- Polling task queue
- NOT assigned any workflows
- Not listed in "current default" build IDs

**When to use:**
- Pre-deployment validation
- Ensuring worker connectivity before routing traffic
- "Dark deployment" for testing

**How to observe:**
```bash
# Check task queue status
temporal task-queue describe --task-queue SUBSCRIPTION_TASK_QUEUE

# Output shows:
# Build IDs:
#   - v2.0-grace-period (default)
#   - v3.0-escalating-grace (reachable, not default) ← INACTIVE
```

**Worker logs:**
```
[INFO] ✅ Worker v3.0 started with build ID: v3.0-escalating-grace
[INFO] Polling task queue: SUBSCRIPTION_TASK_QUEUE
[INFO] Waiting for workflow assignments... (currently idle)
```

---

#### 2. **RAMPING** (Receiving Percentage of Traffic)

```
┌─────────────────┐
│  v2.0: ACTIVE   │ ← 70% of new workflows
└─────────────────┘

┌─────────────────┐
│ v3.0: RAMPING   │ ← 30% of new workflows (canary)
│  (30% traffic)  │
└─────────────────┘
```

**Characteristics:**
- Receives configured percentage of NEW workflows
- Existing workflows remain on their original version
- Percentage is probabilistic (not exact per request)
- Allows gradual rollout and monitoring

**When to use:**
- Canary deployments (start with 10-20%)
- Progressive rollout (20% → 50% → 80%)
- A/B testing different implementations
- Risk mitigation (limit blast radius of bugs)

**How to observe:**
```bash
# Set ramp percentage
temporal task-queue update-build-ids add-new-default \
  --task-queue SUBSCRIPTION_TASK_QUEUE \
  --build-id v3.0-escalating-grace \
  --ramp-percentage 30

# Verify
temporal task-queue describe --task-queue SUBSCRIPTION_TASK_QUEUE
# Output:
# Build IDs:
#   - v2.0-grace-period (default, 70%)
#   - v3.0-escalating-grace (ramping, 30%) ← RAMPING
```

**Worker logs (create 10 subscriptions):**
```
v2 worker: Started 7 workflows (sub-001, sub-003, sub-004, sub-005, sub-006, sub-008, sub-010)
v3 worker: Started 3 workflows (sub-002, sub-007, sub-009)
```

**Temporal UI:**
- Workflows page shows mix of v2 and v3 build IDs
- Metrics show traffic split approximating ramp percentage

---

#### 3. **ACTIVE** (Default Version, Receiving Most/All Traffic)

```
┌─────────────────┐
│  v2.0: ACTIVE   │ ← Default version, 100% of new workflows
└─────────────────┘
```

**Characteristics:**
- Current default version for task queue
- Receives 100% of new workflows (if no ramping version)
- Receives (100 - ramp_percentage)% if another version ramping
- Continues executing its assigned workflows

**When to use:**
- Stable, production-ready version
- Proven through canary testing
- Expected to handle bulk of traffic

**How to observe:**
```bash
temporal task-queue describe --task-queue SUBSCRIPTION_TASK_QUEUE
# Output:
# Build IDs:
#   - v2.0-grace-period (default) ✓ ← ACTIVE
```

**Worker logs:**
```
[INFO] 🆕 Starting subscription sub-123 with VERSION: v2.0
[INFO] 🆕 Starting subscription sub-124 with VERSION: v2.0
[INFO] 🆕 Starting subscription sub-125 with VERSION: v2.0
... continuous workflow starts
```

---

#### 4. **DRAINING** (No New Work, Completing Existing)

```
┌─────────────────┐
│ v1.0: DRAINING  │ ← No new workflows, completing 5 existing ones
│                 │    (countdown: 5 → 4 → 3 → 2 → 1 → 0)
└─────────────────┘

┌─────────────────┐
│  v2.0: ACTIVE   │ ← Taking over, receiving 100% of new traffic
└─────────────────┘
```

**Characteristics:**
- NO new workflows assigned
- Continues executing previously assigned workflows until completion
- Worker remains running and healthy
- Graceful migration (no workflow interruption)

**When to use:**
- After promoting newer version to default
- Before shutting down old worker
- Ensures existing workflows complete with original logic

**How to observe:**
```bash
# After promoting v2, v1 becomes draining
temporal task-queue update-build-ids add-new-default \
  --task-queue SUBSCRIPTION_TASK_QUEUE \
  --build-id v2.0-grace-period

# Mark as compatible for draining
temporal task-queue update-build-ids add-new-compatible \
  --task-queue SUBSCRIPTION_TASK_QUEUE \
  --build-id v2.0-grace-period \
  --existing-compatible-build-id v1.0-immediate-pause

# Check status
temporal task-queue describe --task-queue SUBSCRIPTION_TASK_QUEUE
# Output:
# Build IDs:
#   - v2.0-grace-period (default) ← ACTIVE
#   - v1.0-immediate-pause (compatible, draining) ← DRAINING
```

**Worker logs:**
```
[INFO] No new workflows being assigned to v1.0
[INFO] Completing existing workflow: sub-100 (4 remaining)
[INFO] Workflow sub-100 reached 12 billing cycles. Completing.
[INFO] Completing existing workflow: sub-101 (3 remaining)
[INFO] Workflow sub-101 auto-cancelled after 3min pause timeout.
[INFO] Completing existing workflow: sub-102 (2 remaining)
...
[INFO] All workflows complete. Worker ready to shut down.
```

**Temporal UI:**
- Filter by build-id: `v1.0-immediate-pause`
- See workflows with status "Running" decreasing over time
- Eventually all show "Completed" or "Cancelled"

**Timeline example:**
```
10:00 - v1 has 10 active workflows
10:05 - v2 promoted, v1 draining (10 workflows)
10:08 - v1 has 7 workflows (3 completed with 12 cycles)
10:11 - v1 has 5 workflows (2 auto-cancelled after pause timeout)
10:15 - v1 has 2 workflows
10:18 - v1 has 0 workflows ← Ready to shut down
```

---

#### 5. **DRAINED** (All Work Complete, Can Shut Down)

```
┌─────────────────┐
│  v1.0: DRAINED  │ ← 0 active workflows, safe to terminate
└─────────────────┘

┌─────────────────┐
│  v2.0: ACTIVE   │ ← Only version running
└─────────────────┘
```

**Characteristics:**
- Zero active workflows
- Worker idle, just polling (no work to do)
- Safe to shut down without data loss
- Can be removed from task queue configuration

**When to use:**
- After all workflows naturally complete
- Before decommissioning old worker
- Clean up old versions from infrastructure

**How to observe:**
```bash
# Check workflow count
temporal workflow list --query 'BuildIds="v1.0-immediate-pause" AND ExecutionStatus="Running"'
# Output: No workflows found ← DRAINED

# Check worker polling
temporal task-queue describe --task-queue SUBSCRIPTION_TASK_QUEUE
# Output shows v1 last polled timestamp getting older
```

**Worker logs:**
```
[INFO] No active workflows. Worker idle.
[INFO] Last workflow completed 5 minutes ago.
[INFO] Polling task queue (no work available)...
```

**Actions you can take:**
```bash
# 1. Shut down worker
# In terminal running v1 worker: Ctrl+C
[INFO] Shutting down gracefully...
[INFO] Worker stopped.

# 2. Clean up task queue (optional)
temporal task-queue update-build-ids promote-build-id-within-set \
  --task-queue SUBSCRIPTION_TASK_QUEUE \
  --build-id v2.0-grace-period

# This removes v1.0 from the compatible set entirely
```

---

## Implementation Plan

### Phase 1: Prepare v1.0 with Versioning Support

#### Step 1.1: Update SubscriptionWorkflowImpl.java

Add automatic completion conditions to your existing workflow:

```java
// SubscriptionWorkflowImpl.java (v1.0)

@WorkflowInterface
public class SubscriptionWorkflowImpl implements SubscriptionWorkflow {

    private static final int MAX_BILLING_CYCLES = 12;  // NEW
    private static final Duration PAUSE_TIMEOUT = Duration.ofMinutes(3);  // NEW

    // ... existing fields ...

    @Override
    public void start(String subscriptionId) {
        this.subscriptionId = subscriptionId;

        Workflow.getLogger(this.getClass()).info(
            "Starting subscription with VERSION: v1.0-immediate-pause"
        );

        while (!cancelled) {
            billingCycle++;

            // NEW: Check if reached max billing cycles
            if (totalPaymentsProcessed >= MAX_BILLING_CYCLES) {
                Workflow.getLogger(this.getClass()).info(
                    "✅ Subscription completed {} billing cycles. Ending workflow gracefully.",
                    MAX_BILLING_CYCLES
                );
                currentState = "COMPLETED_MAX_CYCLES";
                break;  // Exit loop, workflow completes
            }

            // ... existing billing logic ...

            // After payment failure handling:
            if (!paymentSuccess && !cancelled) {
                paused = true;
                currentState = "PAUSED";

                Workflow.getLogger(this.getClass()).warn(
                    "Subscription PAUSED. Waiting for resume signal (max 3 minutes)..."
                );

                // NEW: Wait with timeout
                boolean resumed = Workflow.await(
                    PAUSE_TIMEOUT,
                    () -> !paused || cancelled
                );

                if (!resumed && !cancelled) {
                    // NEW: Auto-cancel after timeout
                    Workflow.getLogger(this.getClass()).warn(
                        "⏰ Subscription paused for 3 minutes without resume. Auto-cancelling."
                    );
                    cancelled = true;
                    currentState = "CANCELLED_PAUSE_TIMEOUT";
                    break;  // Exit loop
                }

                if (cancelled) break;

                // Resumed successfully
                currentState = "ACTIVE";
                currentRetryAttempts = 0;
            }

            // ... rest of billing cycle ...
        }

        // Workflow completion
        if (!currentState.equals("CANCELLED_PAUSE_TIMEOUT") &&
            !currentState.equals("COMPLETED_MAX_CYCLES")) {
            currentState = "CANCELLED";
        }

        Workflow.getLogger(this.getClass()).info(
            "Subscription ended. Final state: {}, Cycles: {}, Payments: {}",
            currentState, billingCycle, totalPaymentsProcessed
        );
    }

    // ... rest of methods unchanged ...
}
```

#### Step 1.2: Update WorkerApp.java with Build ID

```java
// WorkerApp.java

public class WorkerApp {

    private static final Logger log = LoggerFactory.getLogger(WorkerApp.class);

    public static void main(String[] args) {
        ApplicationContext context =
            new AnnotationConfigApplicationContext(TemporalConfig.class);

        UserWallet wallet = context.getBean(UserWallet.class);
        WorkflowServiceStubs service = WorkflowServiceStubs.newLocalServiceStubs();
        WorkflowClient client = WorkflowClient.newInstance(service);

        WorkerFactory factory = WorkerFactory.newInstance(client);

        // NEW: Configure worker with versioning
        WorkerOptions options = WorkerOptions.newBuilder()
            .setBuildId("v1.0-immediate-pause")    // Version identifier
            .setUseBuildIdForVersioning(true)      // Enable versioning
            .build();

        Worker worker = factory.newWorker("SUBSCRIPTION_TASK_QUEUE", options);

        worker.registerWorkflowImplementationTypes(SubscriptionWorkflowImpl.class);
        worker.registerActivitiesImplementations(new PaymentActivityImpl(wallet));

        factory.start();

        log.info("✅ Worker v1.0 started with build ID: v1.0-immediate-pause");
        log.info("📋 Max billing cycles: 12");
        log.info("⏰ Pause timeout: 3 minutes");
    }
}
```

#### Step 1.3: Register v1.0 as Default

```bash
# Start v1.0 worker
mvn exec:java -Dexec.mainClass="com.example.subscription.worker.WorkerApp"

# In another terminal, set as default
temporal task-queue update-build-ids add-new-default \
  --task-queue SUBSCRIPTION_TASK_QUEUE \
  --build-id v1.0-immediate-pause

# Verify
temporal task-queue describe --task-queue SUBSCRIPTION_TASK_QUEUE
```

**Expected output:**
```
Task Queue: SUBSCRIPTION_TASK_QUEUE
Pollers: 1
Build IDs:
  - v1.0-immediate-pause (default) ✓
```

---

### Phase 2: Implement v2.0 (Single Grace Period)

#### Step 2.1: Create SubscriptionWorkflowImplV2.java

Create a new file with v2 logic:

```java
// SubscriptionWorkflowImplV2.java

public class SubscriptionWorkflowImplV2 implements SubscriptionWorkflow {

    private static final String VERSION = "v2.0-grace-period";
    private static final int MAX_BILLING_CYCLES = 12;
    private static final Duration PAUSE_TIMEOUT = Duration.ofMinutes(3);
    private static final Duration GRACE_PERIOD = Duration.ofSeconds(30);  // NEW

    // ... same fields as v1 ...

    @Override
    public void start(String subscriptionId) {
        this.subscriptionId = subscriptionId;

        Workflow.getLogger(this.getClass()).info(
            "🆕 Starting subscription with VERSION: {}", VERSION
        );

        while (!cancelled) {
            billingCycle++;

            // Check max cycles
            if (totalPaymentsProcessed >= MAX_BILLING_CYCLES) {
                Workflow.getLogger(this.getClass()).info(
                    "✅ Subscription completed {} billing cycles.",
                    MAX_BILLING_CYCLES
                );
                currentState = "COMPLETED_MAX_CYCLES";
                break;
            }

            // Try payment with retries (3 attempts)
            boolean paymentSuccess = false;
            while (currentRetryAttempts < 3 && !cancelled) {
                try {
                    paymentActivity.charge(subscriptionId);
                    paymentSuccess = true;
                    // ... handle success ...
                    break;
                } catch (Exception e) {
                    currentRetryAttempts++;
                    // ... log failure ...
                    if (currentRetryAttempts < 3) {
                        Workflow.sleep(Duration.ofSeconds(5));
                    }
                }
            }

            // NEW: Grace period logic
            if (!paymentSuccess && !cancelled) {
                currentState = "GRACE_PERIOD";

                Workflow.getLogger(this.getClass()).warn(
                    "⏰ Entering 30-second GRACE PERIOD (v2.0 feature)"
                );

                // Sleep for grace period
                Workflow.sleep(GRACE_PERIOD);

                // Try one more time after grace
                try {
                    Workflow.getLogger(this.getClass()).info(
                        "Attempting payment after grace period..."
                    );
                    paymentActivity.charge(subscriptionId);

                    // Success!
                    paymentSuccess = true;
                    lastPaymentStatus = "SUCCESS_AFTER_GRACE";
                    totalPaymentsProcessed++;
                    currentState = "ACTIVE";

                } catch (Exception e) {
                    // Still failed - now pause
                    paused = true;
                    currentState = "PAUSED";

                    Workflow.getLogger(this.getClass()).warn(
                        "Payment failed after grace period. Pausing."
                    );

                    // Wait for resume with timeout
                    boolean resumed = Workflow.await(
                        PAUSE_TIMEOUT,
                        () -> !paused || cancelled
                    );

                    if (!resumed && !cancelled) {
                        cancelled = true;
                        currentState = "CANCELLED_PAUSE_TIMEOUT";
                        break;
                    }

                    if (cancelled) break;
                    currentState = "ACTIVE";
                    currentRetryAttempts = 0;
                }
            }

            // Continue to next billing cycle
            if (!cancelled && paymentSuccess) {
                Workflow.sleep(Duration.ofMinutes(1));
            }
        }

        // ... completion logging ...
    }

    // ... other methods same as v1 ...
}
```

#### Step 2.2: Create WorkerAppV2.java

```java
// WorkerAppV2.java

public class WorkerAppV2 {

    private static final Logger log = LoggerFactory.getLogger(WorkerAppV2.class);

    public static void main(String[] args) {
        ApplicationContext context =
            new AnnotationConfigApplicationContext(TemporalConfig.class);

        UserWallet wallet = context.getBean(UserWallet.class);
        WorkflowServiceStubs service = WorkflowServiceStubs.newLocalServiceStubs();
        WorkflowClient client = WorkflowClient.newInstance(service);

        WorkerFactory factory = WorkerFactory.newInstance(client);

        WorkerOptions options = WorkerOptions.newBuilder()
            .setBuildId("v2.0-grace-period")
            .setUseBuildIdForVersioning(true)
            .build();

        Worker worker = factory.newWorker("SUBSCRIPTION_TASK_QUEUE", options);

        worker.registerWorkflowImplementationTypes(SubscriptionWorkflowImplV2.class);
        worker.registerActivitiesImplementations(new PaymentActivityImpl(wallet));

        factory.start();

        log.info("✅ Worker v2.0 started with build ID: v2.0-grace-period");
        log.info("🆕 NEW FEATURE: 30-second grace period before pausing");
    }
}
```

---

### Phase 3: Implement v3.0 (Escalating Grace Periods)

#### Step 3.1: Create SubscriptionWorkflowImplV3.java

```java
// SubscriptionWorkflowImplV3.java

public class SubscriptionWorkflowImplV3 implements SubscriptionWorkflow {

    private static final String VERSION = "v3.0-escalating-grace";
    private static final int MAX_BILLING_CYCLES = 12;
    private static final Duration PAUSE_TIMEOUT = Duration.ofMinutes(3);

    // NEW: Multiple grace periods
    private static final Duration GRACE_PERIOD_1 = Duration.ofSeconds(10);
    private static final Duration GRACE_PERIOD_2 = Duration.ofSeconds(20);
    private static final Duration GRACE_PERIOD_3 = Duration.ofSeconds(30);

    // ... same fields ...

    @Override
    public void start(String subscriptionId) {
        this.subscriptionId = subscriptionId;

        Workflow.getLogger(this.getClass()).info(
            "🆕 Starting subscription with VERSION: {}", VERSION
        );

        while (!cancelled) {
            billingCycle++;

            // Check max cycles
            if (totalPaymentsProcessed >= MAX_BILLING_CYCLES) {
                Workflow.getLogger(this.getClass()).info(
                    "✅ Subscription completed {} billing cycles.",
                    MAX_BILLING_CYCLES
                );
                currentState = "COMPLETED_MAX_CYCLES";
                break;
            }

            // Initial 3 retry attempts
            boolean paymentSuccess = attemptPayment(3);

            // NEW: Escalating grace periods
            if (!paymentSuccess && !cancelled) {
                paymentSuccess = escalatingGracePeriods();
            }

            // If still failed, pause with timeout
            if (!paymentSuccess && !cancelled) {
                paused = true;
                currentState = "PAUSED";

                boolean resumed = Workflow.await(
                    PAUSE_TIMEOUT,
                    () -> !paused || cancelled
                );

                if (!resumed && !cancelled) {
                    cancelled = true;
                    currentState = "CANCELLED_PAUSE_TIMEOUT";
                    break;
                }

                if (cancelled) break;
                currentState = "ACTIVE";
                currentRetryAttempts = 0;
            }

            // Next billing cycle
            if (!cancelled && paymentSuccess) {
                Workflow.sleep(Duration.ofMinutes(1));
            }
        }

        // ... completion logging ...
    }

    private boolean attemptPayment(int maxAttempts) {
        currentRetryAttempts = 0;
        while (currentRetryAttempts < maxAttempts && !cancelled) {
            try {
                paymentActivity.charge(subscriptionId);
                // Success
                lastPaymentStatus = "SUCCESS";
                totalPaymentsProcessed++;
                currentState = "ACTIVE";
                return true;
            } catch (Exception e) {
                currentRetryAttempts++;
                lastPaymentStatus = "FAILED - " + e.getMessage();

                Workflow.getLogger(this.getClass()).warn(
                    "Payment failed (attempt {}/{}): {}",
                    currentRetryAttempts, maxAttempts, e.getMessage()
                );

                if (currentRetryAttempts < maxAttempts) {
                    Workflow.sleep(Duration.ofSeconds(5));
                }
            }
        }
        return false;
    }

    private boolean escalatingGracePeriods() {
        // Grace period 1: 10 seconds
        currentState = "GRACE_PERIOD_1";
        Workflow.getLogger(this.getClass()).warn(
            "⏰ Entering 10-second GRACE PERIOD (gentle reminder)"
        );
        Workflow.sleep(GRACE_PERIOD_1);

        if (attemptPayment(1)) {
            lastPaymentStatus = "SUCCESS_AFTER_GRACE_1";
            return true;
        }

        // Grace period 2: 20 seconds
        currentState = "GRACE_PERIOD_2";
        Workflow.getLogger(this.getClass()).warn(
            "⏰⏰ Entering 20-second GRACE PERIOD (urgent reminder)"
        );
        Workflow.sleep(GRACE_PERIOD_2);

        if (attemptPayment(1)) {
            lastPaymentStatus = "SUCCESS_AFTER_GRACE_2";
            return true;
        }

        // Grace period 3: 30 seconds (final)
        currentState = "GRACE_PERIOD_3";
        Workflow.getLogger(this.getClass()).warn(
            "⏰⏰⏰ Entering 30-second GRACE PERIOD (final warning)"
        );
        Workflow.sleep(GRACE_PERIOD_3);

        if (attemptPayment(1)) {
            lastPaymentStatus = "SUCCESS_AFTER_GRACE_3";
            return true;
        }

        // All grace periods exhausted
        Workflow.getLogger(this.getClass()).error(
            "Payment failed after all grace periods. Will pause."
        );
        return false;
    }

    // ... other methods same as v1 ...
}
```

#### Step 3.2: Create WorkerAppV3.java

```java
// WorkerAppV3.java

public class WorkerAppV3 {

    private static final Logger log = LoggerFactory.getLogger(WorkerAppV3.class);

    public static void main(String[] args) {
        ApplicationContext context =
            new AnnotationConfigApplicationContext(TemporalConfig.class);

        UserWallet wallet = context.getBean(UserWallet.class);
        WorkflowServiceStubs service = WorkflowServiceStubs.newLocalServiceStubs();
        WorkflowClient client = WorkflowClient.newInstance(service);

        WorkerFactory factory = WorkerFactory.newInstance(client);

        WorkerOptions options = WorkerOptions.newBuilder()
            .setBuildId("v3.0-escalating-grace")
            .setUseBuildIdForVersioning(true)
            .build();

        Worker worker = factory.newWorker("SUBSCRIPTION_TASK_QUEUE", options);

        worker.registerWorkflowImplementationTypes(SubscriptionWorkflowImplV3.class);
        worker.registerActivitiesImplementations(new PaymentActivityImpl(wallet));

        factory.start();

        log.info("✅ Worker v3.0 started with build ID: v3.0-escalating-grace");
        log.info("🆕 NEW FEATURES: Escalating grace periods (10s → 20s → 30s)");
        log.info("🆕 Progressive customer notifications");
    }
}
```

---

### Phase 4: Update SubscriptionController for Version Pinning

Add ability to pin subscriptions to specific versions:

```java
// SubscriptionController.java

@RestController
public class SubscriptionController {

    @Autowired
    private WorkflowClient client;

    @Autowired
    private UserWallet wallet;

    @PostMapping("/subscribe")
    public Map<String, Object> subscribe(
        @RequestParam double initialBalance,
        @RequestParam(required = false) String pinVersion) {

        String subscriptionId = "sub-" + System.currentTimeMillis();
        wallet.addBalance(subscriptionId, initialBalance);

        // Build workflow options
        WorkflowOptions.Builder optionsBuilder = WorkflowOptions.newBuilder()
            .setWorkflowId(subscriptionId)
            .setTaskQueue("SUBSCRIPTION_TASK_QUEUE");

        // NEW: Pin to specific version if requested
        if (pinVersion != null && !pinVersion.isEmpty()) {
            optionsBuilder.setVersioningIntent(
                VersioningIntent.newBuilder()
                    .setBuildId(pinVersion)
                    .build()
            );
        }

        SubscriptionWorkflow workflow = client.newWorkflowStub(
            SubscriptionWorkflow.class,
            optionsBuilder.build()
        );

        WorkflowClient.start(workflow::start, subscriptionId);

        return Map.of(
            "subscriptionId", subscriptionId,
            "initialBalance", String.valueOf(initialBalance),
            "subscriptionPrice", String.valueOf(wallet.getSubscriptionPrice()),
            "pinnedVersion", pinVersion != null ? pinVersion : "default",
            "status", "STARTED"
        );
    }

    // ... other endpoints unchanged ...
}
```

---

## Testing Scenarios

### Scenario 1: Basic Version Lifecycle (v1 → v2)

**Goal:** Observe complete lifecycle from INACTIVE → ACTIVE → DRAINING → DRAINED

#### Setup

```bash
# Terminal 1: Temporal Server (already running)
docker-compose up

# Terminal 2: Start v1 worker
cd subscription-billing/subscription-billing
mvn exec:java -Dexec.mainClass="com.example.subscription.worker.WorkerApp"

# Terminal 3: Set v1 as default
temporal task-queue update-build-ids add-new-default \
  --task-queue SUBSCRIPTION_TASK_QUEUE \
  --build-id v1.0-immediate-pause

# Terminal 4: Start API
mvn spring-boot:run
```

#### Test Steps

**Step 1: Create subscriptions on v1**

```bash
# Terminal 5: Create test subscriptions
# Fast completion: low balance (will pause and auto-cancel)
SUB_FAST=$(curl -s -X POST "http://localhost:8081/subscribe?initialBalance=15" | jq -r '.subscriptionId')
echo "Fast completion subscription: $SUB_FAST"

# Slow completion: good balance (will complete 12 cycles)
SUB_SLOW=$(curl -s -X POST "http://localhost:8081/subscribe?initialBalance=200" | jq -r '.subscriptionId')
echo "Slow completion subscription: $SUB_SLOW"

# Check Temporal UI
echo "View in UI: http://localhost:8080"
```

**Observe in v1 worker logs:**
```
[INFO] 🆕 Starting subscription sub-XXX with VERSION: v1.0-immediate-pause
[INFO] Starting billing cycle 1
```

**Step 2: Deploy v2 (INACTIVE state)**

```bash
# Terminal 6: Start v2 worker
mvn exec:java -Dexec.mainClass="com.example.subscription.worker.WorkerAppV2"
```

**Observe v2 worker logs:**
```
[INFO] ✅ Worker v2.0 started with build ID: v2.0-grace-period
[INFO] Polling task queue: SUBSCRIPTION_TASK_QUEUE
[INFO] Waiting for workflow assignments... (currently idle)
```

**Check task queue:**
```bash
temporal task-queue describe --task-queue SUBSCRIPTION_TASK_QUEUE
```

**Expected output:**
```
Build IDs:
  - v1.0-immediate-pause (default) ← ACTIVE
  - v2.0-grace-period (reachable) ← INACTIVE (not in default set)
```

**Step 3: Promote v2 to ACTIVE (v1 becomes DRAINING)**

```bash
# Promote v2
temporal task-queue update-build-ids add-new-default \
  --task-queue SUBSCRIPTION_TASK_QUEUE \
  --build-id v2.0-grace-period

# Mark v1 as compatible (allows draining)
temporal task-queue update-build-ids add-new-compatible \
  --task-queue SUBSCRIPTION_TASK_QUEUE \
  --build-id v2.0-grace-period \
  --existing-compatible-build-id v1.0-immediate-pause
```

**Observe v1 worker logs:**
```
[INFO] No new workflows being assigned
[INFO] Completing existing workflow: sub-XXX
[INFO] Active workflows remaining: 2
```

**Observe v2 worker logs:**
```
[INFO] 🆕 Starting subscription sub-YYY with VERSION: v2.0
```

**Step 4: Create new subscription (goes to v2)**

```bash
SUB_V2=$(curl -s -X POST "http://localhost:8081/subscribe?initialBalance=15" | jq -r '.subscriptionId')
echo "v2 subscription: $SUB_V2"
```

**Observe:** v2 worker logs show this subscription, v1 silent

**Step 5: Wait for v1 workflows to complete**

**Monitor v1 worker:**
```bash
# Watch v1 completing workflows
# Fast subscription: Will pause after ~2 minutes, auto-cancel after 3 more minutes
# Slow subscription: Will complete after 12 minutes

# Check status
curl "http://localhost:8081/status/$SUB_FAST" | jq '{state, billingCycle, totalPayments}'
```

**Timeline:**
```
Time 00:00 - Created SUB_FAST with $15 balance
Time 01:00 - Cycle 1: Success ($5 remaining)
Time 02:00 - Cycle 2: Fails 3 times, enters PAUSED
Time 05:00 - Auto-cancel timeout triggered (3 minutes in PAUSED)
             v1 worker: "Workflow auto-cancelled, 1 workflow remaining"
Time 12:00 - SUB_SLOW completes 12 cycles
             v1 worker: "Workflow completed max cycles, 0 workflows remaining"
Time 12:01 - v1 worker: "All workflows complete. Worker can shut down."
```

**Step 6: v1 is DRAINED**

```bash
# Check v1 has no workflows
temporal workflow list \
  --query 'BuildIds="v1.0-immediate-pause" AND ExecutionStatus="Running"'

# Output: No workflows found

# Safe to shut down v1 worker
# In Terminal 2: Ctrl+C
```

**Verify in Temporal UI:**
- v1 build ID shows "Last polled: X minutes ago"
- All v1 workflows show Completed or Cancelled status
- v2 workflows running normally

---

### Scenario 2: Three-Way Split (v1, v2, v3 All Active)

**Goal:** Have all three versions running simultaneously during migration

#### Setup

```bash
# From Scenario 1, you have:
# - v1: DRAINED (can restart if needed)
# - v2: ACTIVE
# - v3: Not deployed yet

# Terminal 2: Keep v2 worker running
# Terminal 6: Start v3 worker (INACTIVE initially)
mvn exec:java -Dexec.mainClass="com.example.subscription.worker.WorkerAppV3"
```

#### Test Steps

**Step 1: v2 is ACTIVE, v3 is INACTIVE**

```bash
# Check status
temporal task-queue describe --task-queue SUBSCRIPTION_TASK_QUEUE
```

**Output:**
```
Build IDs:
  - v2.0-grace-period (default) ← ACTIVE
  - v3.0-escalating-grace (reachable) ← INACTIVE
```

**Step 2: Restart v1 worker and create some v1 subscriptions**

```bash
# Terminal 7: Restart v1 (for demonstration)
mvn exec:java -Dexec.mainClass="com.example.subscription.worker.WorkerApp"

# Create pinned v1 subscriptions
SUB_V1=$(curl -s -X POST "http://localhost:8081/subscribe?initialBalance=200&pinVersion=v1.0-immediate-pause" | jq -r '.subscriptionId')
echo "Pinned to v1: $SUB_V1"
```

**Step 3: Add v3 with 30% ramp**

```bash
temporal task-queue update-build-ids add-new-default \
  --task-queue SUBSCRIPTION_TASK_QUEUE \
  --build-id v3.0-escalating-grace \
  --ramp-percentage 30
```

**Check status:**
```bash
temporal task-queue describe --task-queue SUBSCRIPTION_TASK_QUEUE
```

**Output:**
```
Build IDs:
  - v2.0-grace-period (default, 70%)
  - v3.0-escalating-grace (ramping, 30%)
  - v1.0-immediate-pause (pinned workflows only)
```

**Step 4: Create multiple subscriptions and observe distribution**

```bash
# Create 10 subscriptions
for i in {1..10}; do
  curl -X POST "http://localhost:8081/subscribe?initialBalance=100"
  sleep 2
done
```

**Observe worker logs:**
```
v1 worker: Only shows the pinned workflow (SUB_V1)
v2 worker: Shows ~7 workflows (70%)
v3 worker: Shows ~3 workflows (30%)
```

**Step 5: Three-way state visualization**

At this point you have:
```
┌─────────────────┐
│ v1.0: PINNED    │ ← Only handling pinned workflows
│  (1 workflow)   │
└─────────────────┘

┌─────────────────┐
│ v2.0: ACTIVE    │ ← 70% of new workflows
│  (7 workflows)  │
└─────────────────┘

┌─────────────────┐
│ v3.0: RAMPING   │ ← 30% of new workflows
│  (3 workflows)  │
└─────────────────┘
```

---

### Scenario 3: Progressive Ramp (20% → 50% → 80% → 100%)

**Goal:** Gradual rollout with monitoring at each stage

#### Setup

```bash
# From Scenario 2, you have v2 (ACTIVE) and v3 (RAMPING 30%)
# We'll adjust v3's ramp percentage progressively
```

#### Test Steps

**Step 1: Set v3 to 20% (Canary)**

```bash
temporal task-queue update-build-ids add-new-default \
  --task-queue SUBSCRIPTION_TASK_QUEUE \
  --build-id v3.0-escalating-grace \
  --ramp-percentage 20
```

**Monitor for 5 minutes:**
```bash
# Create subscriptions and monitor
for i in {1..20}; do
  curl -X POST "http://localhost:8081/subscribe?initialBalance=100"
  sleep 10
done

# Expected: ~16 on v2, ~4 on v3
```

**Check metrics:**
- Error rates: Compare v2 vs v3
- Success rates: Both should be similar
- Performance: Check processing times

**Step 2: Increase to 50% (Half Traffic)**

```bash
temporal task-queue update-build-ids add-new-default \
  --task-queue SUBSCRIPTION_TASK_QUEUE \
  --build-id v3.0-escalating-grace \
  --ramp-percentage 50
```

**Create subscriptions with intentional failures (to see grace periods):**
```bash
# Create low-balance subscriptions to trigger failures
for i in {1..10}; do
  curl -X POST "http://localhost:8081/subscribe?initialBalance=15"
  sleep 30
done
```

**Observe behavioral difference:**
```
v2 worker logs (5 subscriptions):
  [WARN] ⏰ Entering 30-second GRACE PERIOD (v2.0 feature)

v3 worker logs (5 subscriptions):
  [WARN] ⏰ Entering 10-second GRACE PERIOD (gentle reminder)
  [WARN] ⏰⏰ Entering 20-second GRACE PERIOD (urgent reminder)
  [WARN] ⏰⏰⏰ Entering 30-second GRACE PERIOD (final warning)
```

**Step 3: Increase to 80% (Near Complete)**

```bash
temporal task-queue update-build-ids add-new-default \
  --task-queue SUBSCRIPTION_TASK_QUEUE \
  --build-id v3.0-escalating-grace \
  --ramp-percentage 80
```

**Observe:** v2 now minority (20%), v3 handling bulk (80%)

**Step 4: Promote v3 to 100% (v2 becomes DRAINING)**

```bash
# Remove ramp, make v3 full default
temporal task-queue update-build-ids add-new-default \
  --task-queue SUBSCRIPTION_TASK_QUEUE \
  --build-id v3.0-escalating-grace
# No --ramp-percentage means 100%

# Mark v2 as compatible
temporal task-queue update-build-ids add-new-compatible \
  --task-queue SUBSCRIPTION_TASK_QUEUE \
  --build-id v3.0-escalating-grace \
  --existing-compatible-build-id v2.0-grace-period
```

**Observe v2 worker:**
```
[INFO] No new workflows being assigned
[INFO] Completing existing workflow: sub-XXX (5 remaining)
...
[INFO] All workflows complete. Worker ready to shut down.
```

**Wait for v2 workflows to complete** (up to 12 minutes for max-cycle completion, or 5 minutes for pause-timeout)

**Step 5: v2 is DRAINED**

```bash
# Verify no v2 workflows
temporal workflow list \
  --query 'BuildIds="v2.0-grace-period" AND ExecutionStatus="Running"'

# Shut down v2 worker
# Terminal 2: Ctrl+C
```

**Final state:**
```
┌─────────────────┐
│  v3.0: ACTIVE   │ ← Only version, 100% traffic
└─────────────────┘
```

---

### Scenario 4: Rollback (v3 Has Critical Bug)

**Goal:** Quickly revert from v3 back to v2 when issue detected

#### Simulated Bug Scenario

Let's say during 30% ramp, you notice v3 is causing errors.

**Step 1: Detect issue**

```bash
# During v3 ramp at 30%
# Observe error logs in v3 worker (simulated bug)

# Check error rate
temporal workflow list \
  --query 'BuildIds="v3.0-escalating-grace" AND ExecutionStatus="Failed"'
```

**Step 2: Immediate rollback**

```bash
# Remove v3 from default set, restore v2 to 100%
temporal task-queue update-build-ids promote-build-id-within-set \
  --task-queue SUBSCRIPTION_TASK_QUEUE \
  --build-id v2.0-grace-period

# This instantly routes all NEW traffic back to v2
```

**Observe:**
- v2 worker: Immediately starts receiving all new workflows again
- v3 worker: Only continues processing workflows already assigned (isolated!)
- NEW subscriptions: All go to v2

**Step 3: Handle in-flight v3 workflows**

```bash
# Option A: Let them complete naturally (safe)
# v3 workflows already running continue on v3 worker

# Option B: Manually cancel and restart on v2 (if critical)
# Get v3 workflows
temporal workflow list --query 'BuildIds="v3.0-escalating-grace" AND ExecutionStatus="Running"'

# For each, cancel and restart (script this)
# curl -X POST "http://localhost:8081/cancel/{id}"
# Then create new subscription with pinVersion=v2.0-grace-period
```

**Step 4: Investigate and fix v3**

```bash
# Keep v3 worker running but inactive for debugging
# Fix the bug in code
# Redeploy v3 with fix
# Test with pinned subscriptions before ramping again
```

**Step 5: Re-ramp v3 after fix**

```bash
# Start conservative with 10% after fix verified
temporal task-queue update-build-ids add-new-default \
  --task-queue SUBSCRIPTION_TASK_QUEUE \
  --build-id v3.0-escalating-grace \
  --ramp-percentage 10

# Monitor closely, then ramp up again
```

---

### Scenario 5: Observing Automatic Completion

**Goal:** Watch workflows complete via both mechanisms (12 cycles and pause timeout)

#### Test Steps

**Step 1: Create subscriptions with different behaviors**

```bash
# Subscription A: Will complete 12 cycles (~12 minutes)
SUB_A=$(curl -s -X POST "http://localhost:8081/subscribe?initialBalance=200" | jq -r '.subscriptionId')

# Subscription B: Will pause and auto-cancel (~5 minutes)
SUB_B=$(curl -s -X POST "http://localhost:8081/subscribe?initialBalance=15" | jq -r '.subscriptionId')

# Subscription C: Will pause, we'll resume it
SUB_C=$(curl -s -X POST "http://localhost:8081/subscribe?initialBalance=15" | jq -r '.subscriptionId')

echo "SUB_A (12 cycles): $SUB_A"
echo "SUB_B (auto-cancel): $SUB_B"
echo "SUB_C (resume): $SUB_C"
```

**Step 2: Monitor SUB_B (pause timeout)**

```bash
# Watch status every 30 seconds
watch -n 30 "curl -s http://localhost:8081/status/$SUB_B | jq '{state, billingCycle, lastPaymentStatus}'"
```

**Expected timeline for SUB_B:**
```
Time 0:00 - Created, ACTIVE
Time 1:00 - Cycle 1 success ($5 remaining)
Time 2:00 - Cycle 2 fails, enters PAUSED
Time 2:00-5:00 - Waiting in PAUSED state
Time 5:00 - Auto-cancel triggered
          - Worker logs: "⏰ Subscription paused for 3 minutes. Auto-cancelling."
          - Status: "CANCELLED_PAUSE_TIMEOUT"
          - Workflow completes
```

**Step 3: Resume SUB_C before timeout**

```bash
# Wait for SUB_C to pause (~2 minutes)
sleep 130

# Check it's paused
curl "http://localhost:8081/status/$SUB_C" | jq '.state'
# Should show: "PAUSED"

# Add money
curl -X POST "http://localhost:8081/wallet/$SUB_C/add?amount=100"

# Resume (before 3-minute timeout)
curl -X POST "http://localhost:8081/resume/$SUB_C"

# Worker logs: "Resume signal received"
# Workflow continues, will complete after 12 cycles
```

**Step 4: Monitor SUB_A (12-cycle completion)**

```bash
# This will take ~12 minutes
watch -n 30 "curl -s http://localhost:8081/status/$SUB_A | jq '{state, billingCycle, totalPaymentsProcessed}'"
```

**Expected progression:**
```
Cycle 1: {state: "ACTIVE", billingCycle: 1, totalPaymentsProcessed: 1}
Cycle 2: {state: "ACTIVE", billingCycle: 2, totalPaymentsProcessed: 2}
...
Cycle 12: {state: "ACTIVE", billingCycle: 12, totalPaymentsProcessed: 12}
After cycle 12:
  - Worker logs: "✅ Subscription completed 12 billing cycles. Ending workflow gracefully."
  - State: "COMPLETED_MAX_CYCLES"
  - Workflow completes
```

**Step 5: Verify in Temporal UI**

```
http://localhost:8080

Filter workflows:
- SUB_A: Status=Completed, Result="COMPLETED_MAX_CYCLES"
- SUB_B: Status=Completed, Result="CANCELLED_PAUSE_TIMEOUT"
- SUB_C: Status=Completed, Result="COMPLETED_MAX_CYCLES" (after resume)
```

**Event history shows:**
- SUB_A: 12 billing cycles with timers
- SUB_B: Pause + 3-minute timeout timer + completion
- SUB_C: Pause + resume signal + remaining cycles + completion

---

### Scenario 6: Version Pinning for Specific Customers

**Goal:** Route specific subscriptions to specific versions regardless of default

#### Use Cases

- VIP customers: Always use stable version
- Test accounts: Try bleeding-edge version
- Legacy contracts: Stay on old version until migration

#### Test Steps

**Step 1: Create pinned subscriptions**

```bash
# VIP customer on stable v2
VIP_SUB=$(curl -s -X POST "http://localhost:8081/subscribe?initialBalance=500&pinVersion=v2.0-grace-period" | jq -r '.subscriptionId')

# Test customer on latest v3
TEST_SUB=$(curl -s -X POST "http://localhost:8081/subscribe?initialBalance=500&pinVersion=v3.0-escalating-grace" | jq -r '.subscriptionId')

# Legacy customer on v1
LEGACY_SUB=$(curl -s -X POST "http://localhost:8081/subscribe?initialBalance=500&pinVersion=v1.0-immediate-pause" | jq -r '.subscriptionId')

# Regular customer (uses default)
REGULAR_SUB=$(curl -s -X POST "http://localhost:8081/subscribe?initialBalance=500" | jq -r '.subscriptionId')
```

**Step 2: Verify routing**

```bash
# Check Temporal UI - each workflow shows its build ID
# VIP_SUB: Build ID = v2.0-grace-period
# TEST_SUB: Build ID = v3.0-escalating-grace
# LEGACY_SUB: Build ID = v1.0-immediate-pause
# REGULAR_SUB: Build ID = (whatever current default is)
```

**Step 3: Observe behavior under failure**

```bash
# Remove balance to trigger failures
for sub in $VIP_SUB $TEST_SUB $LEGACY_SUB; do
  # Wait for cycle to complete, then remove money
  sleep 70
  curl -X POST "http://localhost:8081/wallet/$sub/remove?amount=490"
done
```

**Watch different behaviors:**
```
LEGACY (v1) logs:
  [WARN] 3 retries failed, PAUSED immediately

VIP (v2) logs:
  [WARN] 3 retries failed
  [WARN] ⏰ Entering 30-second GRACE PERIOD
  [WARN] Still failed, PAUSED

TEST (v3) logs:
  [WARN] 3 retries failed
  [WARN] ⏰ Entering 10-second GRACE PERIOD
  [WARN] ⏰⏰ Entering 20-second GRACE PERIOD
  [WARN] ⏰⏰⏰ Entering 30-second GRACE PERIOD
  [WARN] Still failed, PAUSED
```

**Step 4: Version upgrade path**

```bash
# To migrate LEGACY customer to v2:
# 1. Cancel old workflow
curl -X POST "http://localhost:8081/cancel/$LEGACY_SUB"

# 2. Create new subscription with v2 pin
NEW_SUB=$(curl -s -X POST "http://localhost:8081/subscribe?initialBalance=500&pinVersion=v2.0-grace-period" | jq -r '.subscriptionId')

# Now customer has v2 features
```

---

## Observability & Monitoring

### Key Metrics to Track

#### 1. Traffic Distribution

Monitor how workflows are distributed across versions:

```bash
# Count workflows by build ID
temporal workflow list --query 'BuildIds="v1.0-immediate-pause" AND ExecutionStatus="Running"' | wc -l
temporal workflow list --query 'BuildIds="v2.0-grace-period" AND ExecutionStatus="Running"' | wc -l
temporal workflow list --query 'BuildIds="v3.0-escalating-grace" AND ExecutionStatus="Running"' | wc -l
```

**Expected during 30% v3 ramp:**
```
v2 workflows: 14 (70%)
v3 workflows: 6 (30%)
```

#### 2. Completion Reasons

Track why workflows are completing:

```bash
# Query by completion state
temporal workflow list --query 'ExecutionStatus="Completed"'

# In logs or status API, track:
# - COMPLETED_MAX_CYCLES: Successful 12-cycle runs
# - CANCELLED_PAUSE_TIMEOUT: Auto-cancelled after pause
# - CANCELLED: Manual user cancellation
```

**Healthy distribution:**
```
60% COMPLETED_MAX_CYCLES (normal lifecycle)
30% CANCELLED (user action)
10% CANCELLED_PAUSE_TIMEOUT (abandoned subscriptions)
```

#### 3. Version-Specific Success Rates

Compare versions:

```bash
# For each version, calculate:
# Success Rate = Successful Payments / Total Payment Attempts

# Query workflow status API for each version's workflows
# Aggregate totalPaymentsProcessed and billingCycle
```

**Example comparison:**
```
v1: 85% success rate (immediate pause, less forgiving)
v2: 92% success rate (grace period helps)
v3: 96% success rate (multiple grace periods = best retention)
```

#### 4. Draining Progress

Monitor worker draining:

```bash
# Count workflows remaining on old version
watch -n 10 "temporal workflow list --query 'BuildIds=\"v1.0-immediate-pause\" AND ExecutionStatus=\"Running\"' | wc -l"

# Output shows countdown: 10 → 8 → 6 → 4 → 2 → 0 (DRAINED)
```

#### 5. Task Queue Health

```bash
# Check task queue status
temporal task-queue describe --task-queue SUBSCRIPTION_TASK_QUEUE

# Look for:
# - Current default build ID
# - Ramping percentages
# - Poller count per version
# - Backlog (should be near 0)
```

### Temporal UI Dashboards

Access http://localhost:8080 and monitor:

#### Workflows Page
- Filter by Build ID
- Sort by Start Time
- Check status distribution (Running/Completed/Failed)

#### Workflow Details
- Click individual workflow
- See full event history
- Identify which version executed which activities
- Check timers (grace periods, billing cycles)

#### Task Queue Page
- View all registered build IDs
- See reachability status
- Check poller health

### Worker Logs Analysis

Parse worker logs for insights:

```bash
# Count workflows started per version
grep "Starting subscription with VERSION" worker.log | grep "v1.0" | wc -l
grep "Starting subscription with VERSION" worker.log | grep "v2.0" | wc -l
grep "Starting subscription with VERSION" worker.log | grep "v3.0" | wc -l

# Count completion reasons
grep "COMPLETED_MAX_CYCLES" worker.log | wc -l
grep "CANCELLED_PAUSE_TIMEOUT" worker.log | wc -l

# Identify grace period usage (v3 specific)
grep "GRACE_PERIOD_1" worker.log | wc -l
grep "GRACE_PERIOD_2" worker.log | wc -l
grep "GRACE_PERIOD_3" worker.log | wc -l
```

### Custom Metrics Endpoint

Add to SubscriptionController:

```java
@GetMapping("/metrics/versions")
public Map<String, Object> getVersionMetrics() {
    // Query Temporal for workflow counts by build ID
    // Calculate distribution percentages
    // Return JSON with metrics

    return Map.of(
        "v1_active", 5,
        "v2_active", 14,
        "v3_active", 6,
        "v1_completed", 100,
        "v2_completed", 50,
        "v3_completed", 10,
        "ramp_percentage", 30
    );
}
```

---

## Troubleshooting

### Issue 1: Worker Not Receiving Traffic After Deployment

**Symptoms:**
- Worker started successfully
- Shows "Polling task queue"
- No workflows assigned

**Diagnosis:**
```bash
temporal task-queue describe --task-queue SUBSCRIPTION_TASK_QUEUE
# Check if build ID is listed and is default
```

**Solutions:**

1. **If build ID not in list:**
```bash
# Add to task queue
temporal task-queue update-build-ids add-new-default \
  --task-queue SUBSCRIPTION_TASK_QUEUE \
  --build-id <your-build-id>
```

2. **If listed as "reachable" but not default:**
- Intentional (INACTIVE state)
- To activate, promote to default or add ramp percentage

3. **If ramp percentage is 0%:**
```bash
# Increase ramp
temporal task-queue update-build-ids add-new-default \
  --task-queue SUBSCRIPTION_TASK_QUEUE \
  --build-id <your-build-id> \
  --ramp-percentage 20
```

---

### Issue 2: Workers Not Draining (Stuck in DRAINING State)

**Symptoms:**
- Promoted new version
- Old worker still has workflows after expected time
- Cannot shut down old worker

**Diagnosis:**
```bash
# Check workflows on old version
temporal workflow list --query 'BuildIds="<old-build-id>" AND ExecutionStatus="Running"'

# For each workflow, check status
curl "http://localhost:8081/status/{subscriptionId}" | jq
```

**Common causes:**

1. **Paused workflows waiting indefinitely**
   - Without auto-cancel, they block forever
   - Solution: Implemented in this guide (3-minute timeout)

2. **Workflows with high balance (won't complete 12 cycles soon)**
   - Solution: Wait or manually cancel/restart

3. **Pinned workflows**
   - If subscription is pinned to old version, it won't migrate
   - Solution: Check for pinned workflows:
   ```bash
   # Look for workflows with explicit version intent
   # These must be manually migrated or cancelled
   ```

**Solutions:**

```bash
# Option 1: Wait for automatic completion (up to 15 minutes)
# - 12 minutes for max-cycle completion
# - 5 minutes for pause + timeout (2min to pause + 3min timeout)

# Option 2: Manually cancel all old workflows
temporal workflow list --query 'BuildIds="<old-build-id>"' | \
  xargs -I {} temporal workflow cancel --workflow-id {}

# Option 3: Force compatible migration
# Mark new version as compatible, workflows can transfer
temporal task-queue update-build-ids add-new-compatible \
  --task-queue SUBSCRIPTION_TASK_QUEUE \
  --build-id <new-build-id> \
  --existing-compatible-build-id <old-build-id>

# This allows new worker to execute old workflows
```

---

### Issue 3: Unexpected Traffic Distribution (Ramp Not Working)

**Symptoms:**
- Set v3 to 30% ramp
- Actually seeing 50% or 10% traffic

**Diagnosis:**
```bash
# Check actual ramp setting
temporal task-queue describe --task-queue SUBSCRIPTION_TASK_QUEUE

# Count recent workflows
temporal workflow list --query 'StartTime > "2024-01-01"' | grep "BuildId"
```

**Causes:**

1. **Ramp is probabilistic, not exact**
   - With small sample sizes (< 100 workflows), expect variance
   - 30% ramp might show 20-40% in practice
   - Over large samples, converges to target percentage

2. **Pinned workflows don't count toward ramp**
   - Workflows with explicit version intent bypass ramp logic
   - Only "default" subscriptions participate in ramp

3. **Cached task distribution**
   - Temporal may batch tasks, causing temporary imbalance
   - Smooths out over time (minutes)

**Solutions:**

```bash
# If truly incorrect, re-set ramp
temporal task-queue update-build-ids add-new-default \
  --task-queue SUBSCRIPTION_TASK_QUEUE \
  --build-id v3.0-escalating-grace \
  --ramp-percentage 30

# Verify change propagated
temporal task-queue describe --task-queue SUBSCRIPTION_TASK_QUEUE

# Test with larger sample
for i in {1..100}; do
  curl -X POST "http://localhost:8081/subscribe?initialBalance=100"
  sleep 1
done

# Should see ~70 on v2, ~30 on v3
```

---

### Issue 4: Version Rollback Not Instant

**Symptoms:**
- Rolled back from v3 to v2
- Still seeing new workflows on v3

**Diagnosis:**
```bash
# Check current default
temporal task-queue describe --task-queue SUBSCRIPTION_TASK_QUEUE
```

**Causes:**

1. **Rollback command not applied correctly**
2. **In-flight workflow starts (already committed to v3)**
3. **Cached task assignment (worker pre-fetched tasks)**

**Solutions:**

```bash
# Confirm rollback
temporal task-queue update-build-ids promote-build-id-within-set \
  --task-queue SUBSCRIPTION_TASK_QUEUE \
  --build-id v2.0-grace-period

# Wait 10-30 seconds for propagation

# Verify new subscriptions go to v2
curl -X POST "http://localhost:8081/subscribe?initialBalance=100"
# Check worker logs - should be v2

# In-flight v3 workflows will complete on v3 (correct behavior - isolation)
```

---

### Issue 5: Worker Crashing on Startup

**Symptoms:**
- Worker starts, then crashes immediately
- Error in logs

**Common errors:**

**Error: "Build ID already registered with different worker"**
```
Solution: Each worker binary must have unique build ID
Don't reuse build IDs across different code versions
```

**Error: "Task queue not found"**
```bash
Solution: Create task queue first
temporal task-queue describe --task-queue SUBSCRIPTION_TASK_QUEUE --create-if-not-exist
```

**Error: "Version string format invalid"**
```
Solution: Build IDs must be alphanumeric + dashes
Valid: "v1.0-immediate-pause"
Invalid: "v1.0_immediate#pause"
```

---

### Issue 6: Cannot Observe Version in Temporal UI

**Symptoms:**
- Workflow running, but Build ID not shown in UI

**Causes:**

1. **Versioning not enabled**
   ```java
   // Check worker configuration
   WorkerOptions.newBuilder()
       .setBuildId("v1.0-immediate-pause")
       .setUseBuildIdForVersioning(true)  // Must be true!
       .build()
   ```

2. **Using old Temporal UI version**
   - Build ID feature requires Temporal Server 1.20+
   - Update docker-compose to use latest image

**Solutions:**

```bash
# Verify Temporal version
temporal server version

# Update docker-compose.yml
services:
  temporal:
    image: temporalio/auto-setup:latest  # Use latest

# Restart
docker-compose down
docker-compose up
```

---

## Summary

This guide demonstrates:

✅ **Three versions** with progressively smarter payment failure handling
✅ **Automatic completion** to enable clean version draining (12 cycles or 3-min pause timeout)
✅ **Complete lifecycle** from INACTIVE → RAMPING → ACTIVE → DRAINING → DRAINED
✅ **Rainbow deployment** with percentage-based traffic splitting
✅ **Zero-downtime migration** with rollback capability
✅ **Production patterns** used by major tech companies

### Key Takeaways

1. **Versioning prevents breaking changes**
   - Old workflows continue with old logic
   - New workflows use new logic
   - Both coexist safely

2. **Automatic completion is essential**
   - Prevents workflows from blocking version draining
   - Demonstrates `continueAsNew` best practice
   - Enables reasonable demo timelines

3. **Rainbow deployment reduces risk**
   - Start with small percentage (canary)
   - Monitor metrics at each stage
   - Rollback instantly if issues detected
   - Gradually increase confidence

4. **Temporal handles complexity**
   - No custom code for traffic splitting
   - Built-in task distribution
   - Automatic failover
   - Complete observability

### Next Steps

- Implement the three versions following this guide
- Run through all test scenarios
- Observe worker state transitions in real-time
- Experiment with different ramp percentages
- Practice rollback procedures
- Monitor Temporal UI during deployments

**Happy versioning! 🚀**
