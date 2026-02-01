# Worker Versioning - Quick Start

This document provides quick commands to run the different worker versions.

## Three Workflow Versions

### Version 1.0: Immediate Pause
- **Build ID**: `v1.0-immediate-pause`
- **Behavior**: After 3 payment retries (5 sec apart), immediately pauses
- **No grace period**
- **Automatic completion**:
  - After 12 successful billing cycles, OR
  - After 3 minutes in PAUSED state (auto-cancel)

### Version 2.0: Single Grace Period
- **Build ID**: `v2.0-grace-period`
- **Behavior**: After 3 payment retries, enters 30-second grace period, then 1 more attempt
- **Better customer retention**
- **Automatic completion**:
  - After 12 successful billing cycles, OR
  - After 3 minutes in PAUSED state (auto-cancel)

### Version 3.0: Escalating Grace Periods
- **Build ID**: `v3.0-escalating-grace`
- **Behavior**: After 3 payment retries, escalating grace periods:
  - 10-second grace period + gentle reminder → 1 retry
  - 20-second grace period + urgent reminder → 1 retry
  - 30-second grace period + final warning → 1 retry
- **Maximum customer retention**
- **Automatic completion**:
  - After 12 successful billing cycles, OR
  - After 3 minutes in PAUSED state (auto-cancel)

---

## Running Workers

### Start v1.0 Worker
```bash
mvn exec:java -Dexec.mainClass="com.example.subscription.worker.WorkerApp"
```

### Start v2.0 Worker
```bash
mvn exec:java -Dexec.mainClass="com.example.subscription.worker.WorkerAppV2"
```

### Start v3.0 Worker
```bash
mvn exec:java -Dexec.mainClass="com.example.subscription.worker.WorkerAppV3"
```

---

## Setting Default Version

### Set v1 as Default
```bash
temporal task-queue update-build-ids add-new-default \
  --task-queue SUBSCRIPTION_TASK_QUEUE \
  --build-id v1.0-immediate-pause
```

### Set v2 as Default
```bash
temporal task-queue update-build-ids add-new-default \
  --task-queue SUBSCRIPTION_TASK_QUEUE \
  --build-id v2.0-grace-period
```

### Set v3 as Default
```bash
temporal task-queue update-build-ids add-new-default \
  --task-queue SUBSCRIPTION_TASK_QUEUE \
  --build-id v3.0-escalating-grace
```

---

## Ramp Deployment

### Set v3 with 30% Ramp (v2 gets 70%)
```bash
temporal task-queue update-build-ids add-new-default \
  --task-queue SUBSCRIPTION_TASK_QUEUE \
  --build-id v3.0-escalating-grace \
  --ramp-percentage 30
```

### Increase Ramp to 50%
```bash
temporal task-queue update-build-ids add-new-default \
  --task-queue SUBSCRIPTION_TASK_QUEUE \
  --build-id v3.0-escalating-grace \
  --ramp-percentage 50
```

### Promote to 100% (Full Default)
```bash
temporal task-queue update-build-ids add-new-default \
  --task-queue SUBSCRIPTION_TASK_QUEUE \
  --build-id v3.0-escalating-grace
```

---

## Version Migration

### Promote v2, Drain v1
```bash
# 1. Promote v2 to default
temporal task-queue update-build-ids add-new-default \
  --task-queue SUBSCRIPTION_TASK_QUEUE \
  --build-id v2.0-grace-period

# 2. Mark v1 as compatible (allows draining)
temporal task-queue update-build-ids add-new-compatible \
  --task-queue SUBSCRIPTION_TASK_QUEUE \
  --build-id v2.0-grace-period \
  --existing-compatible-build-id v1.0-immediate-pause

# 3. Wait for v1 workflows to complete (up to 15 minutes)
# 4. Shut down v1 worker (Ctrl+C in v1 worker terminal)
```

---

## Rollback

### Rollback from v3 to v2
```bash
temporal task-queue update-build-ids promote-build-id-within-set \
  --task-queue SUBSCRIPTION_TASK_QUEUE \
  --build-id v2.0-grace-period
```

This instantly routes all NEW traffic back to v2. Existing v3 workflows continue on v3.

---

## Creating Subscriptions

### Default Version (Uses Current Default)
```bash
curl -X POST "http://localhost:8081/subscribe?initialBalance=100"
```

### Pinned to v1
```bash
curl -X POST "http://localhost:8081/subscribe?initialBalance=100&pinVersion=v1.0-immediate-pause"
```

### Pinned to v2
```bash
curl -X POST "http://localhost:8081/subscribe?initialBalance=100&pinVersion=v2.0-grace-period"
```

### Pinned to v3
```bash
curl -X POST "http://localhost:8081/subscribe?initialBalance=100&pinVersion=v3.0-escalating-grace"
```

---

## Monitoring

### Check Task Queue Status
```bash
temporal task-queue describe --task-queue SUBSCRIPTION_TASK_QUEUE
```

### List Workflows by Version
```bash
# v1 workflows
temporal workflow list --query 'BuildIds="v1.0-immediate-pause" AND ExecutionStatus="Running"'

# v2 workflows
temporal workflow list --query 'BuildIds="v2.0-grace-period" AND ExecutionStatus="Running"'

# v3 workflows
temporal workflow list --query 'BuildIds="v3.0-escalating-grace" AND ExecutionStatus="Running"'
```

### Count Workflows by Version
```bash
temporal workflow list --query 'BuildIds="v1.0-immediate-pause" AND ExecutionStatus="Running"' | wc -l
```

### Watch Draining Progress
```bash
watch -n 10 "temporal workflow list --query 'BuildIds=\"v1.0-immediate-pause\" AND ExecutionStatus=\"Running\"' | wc -l"
```

---

## Testing Scenarios

For comprehensive step-by-step testing instructions, see:
- **[WORKER_VERSIONING_TESTING_GUIDE.md](WORKER_VERSIONING_TESTING_GUIDE.md)** - Complete testing guide with 7 detailed test scenarios

---

## Files Created

### Workflow Implementations
- `SubscriptionWorkflowImpl.java` - Version 1.0 (updated with automatic completion)
- `SubscriptionWorkflowImplV2.java` - Version 2.0 (single grace period)
- `SubscriptionWorkflowImplV3.java` - Version 3.0 (escalating grace periods)

### Worker Applications
- `WorkerApp.java` - Version 1.0 worker (updated with build ID)
- `WorkerAppV2.java` - Version 2.0 worker
- `WorkerAppV3.java` - Version 3.0 worker

### API Updates
- `SubscriptionController.java` - Added `pinVersion` parameter support

### Documentation
- `WORKER_VERSIONING_GUIDE.md` - Theory and concepts (user-created)
- `WORKER_VERSIONING_TESTING_GUIDE.md` - Step-by-step testing instructions
- `WORKER_VERSIONS_README.md` - This file (quick reference)

---

## Key Concepts

### Worker States
1. **INACTIVE** - Worker deployed but not receiving traffic (not in default set)
2. **ACTIVE** - Current default version receiving 100% or (100-ramp)% of traffic
3. **RAMPING** - New version receiving configured percentage of traffic
4. **DRAINING** - Old version completing existing workflows, no new assignments
5. **DRAINED** - Zero workflows remaining, safe to shut down

### Automatic Completion Conditions
Both are critical for enabling clean version draining:

1. **12 Billing Cycles** - Prevents infinite workflow execution
2. **3-Minute Pause Timeout** - Prevents workflows from blocking draining forever

Without these conditions, workflows could run indefinitely, preventing old workers from ever draining completely.

---

## Next Steps

1. Start Temporal Server: `docker-compose up`
2. Start API: `mvn spring-boot:run`
3. Follow the **WORKER_VERSIONING_TESTING_GUIDE.md** to test all scenarios
4. Experiment with different ramp percentages
5. Try rollback procedures
6. Observe different workflow behaviors under failure conditions

**Happy versioning! 🚀**
