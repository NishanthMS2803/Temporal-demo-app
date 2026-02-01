# Worker Versioning Testing Guide - Step by Step

This comprehensive guide walks you through testing all Temporal worker versioning features including rainbow deployments, version states (INACTIVE, ACTIVE, RAMPING, DRAINING, DRAINED), pinned workflows, and rollback scenarios.

---

## 📚 Table of Contents

1. [Prerequisites & Setup](#prerequisites--setup)
2. [TEST 1: Basic Version Lifecycle (v1 → v2)](#test-1-basic-version-lifecycle-v1--v2)
3. [TEST 2: Three-Way Split (v1, v2, v3 All Active)](#test-2-three-way-split-v1-v2-v3-all-active)
4. [TEST 3: Progressive Ramp (20% → 50% → 80% → 100%)](#test-3-progressive-ramp-20--50--80--100)
5. [TEST 4: Rollback Scenario](#test-4-rollback-scenario-v3-has-issues)
6. [TEST 5: Observing Automatic Completion](#test-5-observing-automatic-completion)
7. [TEST 6: Version Pinning for Specific Customers](#test-6-version-pinning-for-specific-customers)
8. [TEST 7: Draining and Drained States](#test-7-draining-and-drained-states)
9. [Quick Reference Commands](#quick-reference-commands)

---

## Prerequisites & Setup

### **Step 1: Start Temporal Server**

**Terminal 1:**
```bash
cd /Users/nms/Documents/Temporal-Demo/subscription-billing
docker-compose up
```

**Expected Output:**
```
temporal_1  | Temporal server started
temporal_1  | Listening on port 7233
temporal-ui_1 | UI available at http://localhost:8080
```

**Verify:**
- Open browser: http://localhost:8080
- Should see Temporal Web UI
- No workflows yet (clean start)

---

### **Step 2: Start API**

**Terminal 2:**
```bash
cd /Users/nms/Documents/Temporal-Demo/subscription-billing/subscription-billing
mvn spring-boot:run
```

**Expected Output:**
```
Started SubscriptionApplication in 2.5 seconds
Tomcat started on port(s): 8081 (http)
```

**Verify:**
```bash
curl http://localhost:8081/actuator/health
```
Should return: `{"status":"UP"}`

---

### **Summary - You Should Have:**
```
Terminal 1: Temporal Server (Docker) ✅
Terminal 2: API (Spring Boot) ✅
Terminal 3: FREE for v1 worker ✅
Terminal 4: FREE for v2 worker ✅
Terminal 5: FREE for v3 worker ✅
Terminal 6: FREE for testing commands ✅
```

---

## TEST 1: Basic Version Lifecycle (v1 → v2)

**Goal:** Observe complete lifecycle from INACTIVE → ACTIVE → DRAINING → DRAINED

### **Phase 1: Deploy v1 as Default**

**Step 1.1: Start v1 Worker**

**Terminal 3:**
```bash
cd /Users/nms/Documents/Temporal-Demo/subscription-billing/subscription-billing
mvn exec:java -Dexec.mainClass="com.example.subscription.worker.WorkerApp"
```

**Expected Output:**
```
✅ Worker v1.0 started with build ID: v1.0-immediate-pause
📋 Max billing cycles: 12
⏰ Pause timeout: 3 minutes
✓ Ready to process subscription workflows
```

---

**Step 1.2: Set v1 as Default**

**Terminal 6:**
```bash
temporal task-queue update-build-ids add-new-default \
  --task-queue SUBSCRIPTION_TASK_QUEUE \
  --build-id v1.0-immediate-pause
```

**Verify:**
```bash
temporal task-queue describe --task-queue SUBSCRIPTION_TASK_QUEUE
```

**Expected Output:**
```
Task Queue: SUBSCRIPTION_TASK_QUEUE
Pollers: 1
Build IDs:
  - v1.0-immediate-pause (default) ✓
```

---

**Step 1.3: Create Subscriptions on v1**

**Terminal 6:**
```bash
# Fast completion: low balance (will pause and auto-cancel after 3 minutes)
SUB_FAST=$(curl -s -X POST "http://localhost:8081/subscribe?initialBalance=15" | jq -r '.subscriptionId')
echo "Fast completion subscription: $SUB_FAST"

# Slow completion: good balance (will complete 12 cycles)
SUB_SLOW=$(curl -s -X POST "http://localhost:8081/subscribe?initialBalance=200" | jq -r '.subscriptionId')
echo "Slow completion subscription: $SUB_SLOW"
```

---

**Step 1.4: Observe v1 Worker Logs**

**Terminal 3 (v1 Worker) shows:**
```
[INFO] 🆕 Starting subscription with VERSION: v1.0-immediate-pause - subscriptionId: sub-XXX
[INFO] Starting billing cycle 1 for subscription sub-XXX
[INFO] Payment successful for cycle 1 (total: 1)
[INFO] Waiting 1 minute until next billing cycle...
```

---

**Step 1.5: Check Temporal UI**

**Browser → http://localhost:8080:**
- Click "Workflows" in sidebar
- Find your workflows: `sub-XXX`
- See "Build ID: v1.0-immediate-pause"
- Status: Running

---

### **Phase 2: Deploy v2 (INACTIVE State)**

**Step 2.1: Start v2 Worker (INACTIVE)**

**Terminal 4:**
```bash
mvn exec:java -Dexec.mainClass="com.example.subscription.worker.WorkerAppV2"
```

**Expected Output:**
```
✅ Worker v2.0 started with build ID: v2.0-grace-period
🆕 NEW FEATURE: 30-second grace period before pausing
📋 Max billing cycles: 12
⏰ Pause timeout: 3 minutes
✓ Ready to process subscription workflows
```

**Observe:** v2 worker logs show it's polling but NOT receiving any workflows (INACTIVE state).

---

**Step 2.2: Verify v2 is INACTIVE**

**Terminal 6:**
```bash
temporal task-queue describe --task-queue SUBSCRIPTION_TASK_QUEUE
```

**Expected Output:**
```
Build IDs:
  - v1.0-immediate-pause (default) ← ACTIVE
  - v2.0-grace-period (reachable) ← INACTIVE (not in default set)
```

**What this means:**
- v2 worker is connected and polling
- v2 worker is NOT receiving any new workflows
- All new workflows go to v1 (the default)

---

**Step 2.3: Create New Subscription (Should Still Go to v1)**

**Terminal 6:**
```bash
SUB_V1_TEST=$(curl -s -X POST "http://localhost:8081/subscribe?initialBalance=100" | jq -r '.subscriptionId')
echo "This should be on v1: $SUB_V1_TEST"
```

**Observe Terminal 3 (v1 Worker):** Shows the new subscription starting
**Observe Terminal 4 (v2 Worker):** Silent (no workflows assigned)

---

### **Phase 3: Promote v2 to ACTIVE (v1 becomes DRAINING)**

**Step 3.1: Promote v2 to Default**

**Terminal 6:**
```bash
# Promote v2 to default
temporal task-queue update-build-ids add-new-default \
  --task-queue SUBSCRIPTION_TASK_QUEUE \
  --build-id v2.0-grace-period

# Mark v1 as compatible (allows draining)
temporal task-queue update-build-ids add-new-compatible \
  --task-queue SUBSCRIPTION_TASK_QUEUE \
  --build-id v2.0-grace-period \
  --existing-compatible-build-id v1.0-immediate-pause
```

---

**Step 3.2: Verify State Change**

**Terminal 6:**
```bash
temporal task-queue describe --task-queue SUBSCRIPTION_TASK_QUEUE
```

**Expected Output:**
```
Build IDs:
  - v2.0-grace-period (default) ← ACTIVE
  - v1.0-immediate-pause (compatible, draining) ← DRAINING
```

**What happened:**
- ✅ v2 is now ACTIVE (receives all new workflows)
- ✅ v1 is now DRAINING (no new workflows, completing existing ones)

---

**Step 3.3: Create New Subscription (Should Go to v2)**

**Terminal 6:**
```bash
SUB_V2=$(curl -s -X POST "http://localhost:8081/subscribe?initialBalance=15" | jq -r '.subscriptionId')
echo "This should be on v2: $SUB_V2"
```

**Observe Terminal 4 (v2 Worker):**
```
[INFO] 🆕 Starting subscription with VERSION: v2.0-grace-period - subscriptionId: sub-XXX
```

**Observe Terminal 3 (v1 Worker):** Silent (not receiving new workflows, only processing existing ones)

---

### **Phase 4: Watch v1 Drain**

**Step 4.1: Monitor SUB_FAST (Should Auto-Cancel)**

**Terminal 6:**
```bash
# Watch status every 30 seconds
watch -n 30 "curl -s http://localhost:8081/status/$SUB_FAST | jq '{state, billingCycle, lastPaymentStatus}'"
```

**Expected Timeline for SUB_FAST:**
```
Time 0:00 - Created, ACTIVE, balance $15
Time 1:00 - Cycle 1 success, balance $5
Time 2:00 - Cycle 2 fails (3 retries), enters PAUSED
Time 2:00-5:00 - Waiting in PAUSED state (timeout: 3 minutes)
Time 5:00 - Auto-cancel triggered
          v1 worker logs: "⏰ Subscription paused for 3 minutes. Auto-cancelling."
          Status: "CANCELLED_PAUSE_TIMEOUT"
          Workflow completes (v1 now has 1 less workflow)
```

---

**Step 4.2: Monitor SUB_SLOW (Should Complete 12 Cycles)**

**Terminal 6:**
```bash
# Check workflow count on v1
temporal workflow list \
  --query 'BuildIds="v1.0-immediate-pause" AND ExecutionStatus="Running"'
```

**Expected Timeline for SUB_SLOW:**
```
Time 0:00-12:00 - Completes 12 billing cycles (1 per minute)
Time 12:00 - v1 worker logs: "✅ Subscription completed 12 billing cycles."
            Status: "COMPLETED_MAX_CYCLES"
            Workflow completes
```

---

**Step 4.3: Check SUB_V2 Behavior (Grace Period Feature)**

Let SUB_V2 fail (it has only $15 balance):

**Expected v2 Worker Logs:**
```
[INFO] Starting billing cycle 2 for subscription sub-V2
[WARN] Payment failed (attempt 1/3): Insufficient funds...
[WARN] Payment failed (attempt 2/3): Insufficient funds...
[WARN] Payment failed (attempt 3/3): Insufficient funds...
[WARN] ⏰ Entering 30-second GRACE PERIOD (v2.0 feature)    ← NEW v2 FEATURE!
[INFO] Attempting payment after grace period...
[WARN] Payment failed after grace period. Pausing.
[WARN] Subscription PAUSED. Waiting for resume signal (max 3 minutes)...
```

**Difference from v1:**
- v1: Pauses immediately after 3 retries
- v2: Waits 30 seconds (grace period) then tries once more before pausing

---

### **Phase 5: v1 is DRAINED**

**Step 5.1: Verify v1 Has No Workflows**

**Terminal 6:**
```bash
# Check v1 has no running workflows
temporal workflow list \
  --query 'BuildIds="v1.0-immediate-pause" AND ExecutionStatus="Running"'
```

**Expected Output:** `No workflows found`

---

**Step 5.2: Safe to Shut Down v1 Worker**

**Terminal 3 (v1 Worker):**
```
Press Ctrl+C
```

**Output:**
```
^C
Shutting down gracefully...
Worker stopped.
```

**v1 worker is now DRAINED and shut down! ✅**

---

**Step 5.3: Verify in Temporal UI**

**Browser → http://localhost:8080:**
- Filter workflows by Build ID: `v1.0-immediate-pause`
- All workflows should show "Completed" or "Cancelled" status
- v2 workflows continue running normally

---

**✅ TEST 1 COMPLETE!**

**What you demonstrated:**
- ✅ INACTIVE state (v2 deployed but not receiving traffic)
- ✅ ACTIVE state (v1 initially, then v2)
- ✅ DRAINING state (v1 after v2 promotion)
- ✅ DRAINED state (v1 after all workflows complete)
- ✅ Automatic completion (12 cycles and 3-minute pause timeout)
- ✅ Different workflow behavior (v1 immediate pause vs v2 grace period)

---

## TEST 2: Three-Way Split (v1, v2, v3 All Active)

**Goal:** Have all three versions running simultaneously during migration

**Starting Point:** v1 DRAINED, v2 ACTIVE (from TEST 1)

---

### **Step 1: Deploy v3 (INACTIVE)**

**Terminal 5:**
```bash
mvn exec:java -Dexec.mainClass="com.example.subscription.worker.WorkerAppV3"
```

**Expected Output:**
```
✅ Worker v3.0 started with build ID: v3.0-escalating-grace
🆕 NEW FEATURES: Escalating grace periods (10s → 20s → 30s)
🆕 Progressive customer notifications
📋 Max billing cycles: 12
⏰ Pause timeout: 3 minutes
✓ Ready to process subscription workflows
```

---

**Step 2: Verify v3 is INACTIVE**

**Terminal 6:**
```bash
temporal task-queue describe --task-queue SUBSCRIPTION_TASK_QUEUE
```

**Expected Output:**
```
Build IDs:
  - v2.0-grace-period (default) ← ACTIVE
  - v3.0-escalating-grace (reachable) ← INACTIVE
```

---

**Step 3: Restart v1 Worker and Create Pinned v1 Subscriptions**

**Terminal 3:**
```bash
mvn exec:java -Dexec.mainClass="com.example.subscription.worker.WorkerApp"
```

**Terminal 6:**
```bash
# Create pinned v1 subscription (explicitly use v1)
SUB_V1=$(curl -s -X POST "http://localhost:8081/subscribe?initialBalance=200&pinVersion=v1.0-immediate-pause" | jq -r '.subscriptionId')
echo "Pinned to v1: $SUB_V1"
```

**Observe Terminal 3 (v1 Worker):**
```
[INFO] 🆕 Starting subscription with VERSION: v1.0-immediate-pause - subscriptionId: sub-XXX
```

**Note:** Even though v1 is not the default, this workflow is pinned to v1!

---

**Step 4: Add v3 with 30% Ramp**

**Terminal 6:**
```bash
temporal task-queue update-build-ids add-new-default \
  --task-queue SUBSCRIPTION_TASK_QUEUE \
  --build-id v3.0-escalating-grace \
  --ramp-percentage 30
```

---

**Step 5: Verify Three-Way Split**

**Terminal 6:**
```bash
temporal task-queue describe --task-queue SUBSCRIPTION_TASK_QUEUE
```

**Expected Output:**
```
Build IDs:
  - v2.0-grace-period (default, 70%)
  - v3.0-escalating-grace (ramping, 30%)
  - v1.0-immediate-pause (pinned workflows only)
```

---

**Step 6: Create Multiple Subscriptions and Observe Distribution**

**Terminal 6:**
```bash
# Create 10 subscriptions (should split 70% v2, 30% v3)
for i in {1..10}; do
  curl -X POST "http://localhost:8081/subscribe?initialBalance=100"
  sleep 2
done
```

---

**Step 7: Observe Worker Logs**

**Terminal 3 (v1 Worker):**
- Only shows the pinned workflow (SUB_V1)

**Terminal 4 (v2 Worker):**
- Shows ~7 workflows (70%)

**Terminal 5 (v3 Worker):**
- Shows ~3 workflows (30%)

---

**Step 8: Three-Way State Visualization**

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

**✅ TEST 2 COMPLETE!**

**What you demonstrated:**
- ✅ Three versions running simultaneously
- ✅ PINNED workflows (v1 explicitly assigned)
- ✅ RAMPING state (v3 at 30%)
- ✅ Percentage-based traffic splitting
- ✅ Load distribution across versions

---

## TEST 3: Progressive Ramp (20% → 50% → 80% → 100%)

**Goal:** Gradual rollout with monitoring at each stage

**Starting Point:** v2 ACTIVE (70%), v3 RAMPING (30%)

---

### **Step 1: Set v3 to 20% (Canary)**

**Terminal 6:**
```bash
temporal task-queue update-build-ids add-new-default \
  --task-queue SUBSCRIPTION_TASK_QUEUE \
  --build-id v3.0-escalating-grace \
  --ramp-percentage 20
```

---

**Step 2: Create Subscriptions and Monitor (5 minutes)**

**Terminal 6:**
```bash
# Create 20 subscriptions over 5 minutes
for i in {1..20}; do
  curl -X POST "http://localhost:8081/subscribe?initialBalance=100"
  sleep 15
done
```

**Expected Distribution:** ~16 on v2, ~4 on v3

**Monitor v2 and v3 worker logs:**
- Count how many workflows each version receives
- Check for errors
- Compare success rates

---

**Step 3: Increase to 50% (Half Traffic)**

**Terminal 6:**
```bash
temporal task-queue update-build-ids add-new-default \
  --task-queue SUBSCRIPTION_TASK_QUEUE \
  --build-id v3.0-escalating-grace \
  --ramp-percentage 50
```

---

**Step 4: Create Low-Balance Subscriptions to See v3 Grace Periods**

**Terminal 6:**
```bash
# Create low-balance subscriptions to trigger failures
for i in {1..10}; do
  curl -X POST "http://localhost:8081/subscribe?initialBalance=15"
  sleep 30
done
```

---

**Step 5: Observe Behavioral Difference**

**Terminal 4 (v2 Worker - ~5 subscriptions):**
```
[WARN] ⏰ Entering 30-second GRACE PERIOD (v2.0 feature)
[INFO] Attempting payment after grace period...
[WARN] Payment failed after grace period. Pausing.
```

**Terminal 5 (v3 Worker - ~5 subscriptions):**
```
[WARN] ⏰ Entering 10-second GRACE PERIOD (gentle reminder)      ← v3 FEATURE!
[INFO] Attempting payment after grace period...
[WARN] ⏰⏰ Entering 20-second GRACE PERIOD (urgent reminder)    ← v3 FEATURE!
[INFO] Attempting payment after grace period...
[WARN] ⏰⏰⏰ Entering 30-second GRACE PERIOD (final warning)    ← v3 FEATURE!
[INFO] Attempting payment after grace period...
[WARN] Payment failed after all grace periods. Will pause.
```

**v3 gives customers THREE chances with escalating grace periods!**

---

**Step 6: Increase to 80% (Near Complete)**

**Terminal 6:**
```bash
temporal task-queue update-build-ids add-new-default \
  --task-queue SUBSCRIPTION_TASK_QUEUE \
  --build-id v3.0-escalating-grace \
  --ramp-percentage 80
```

**Observe:** v2 now minority (20%), v3 handling bulk (80%)

---

**Step 7: Promote v3 to 100% (v2 becomes DRAINING)**

**Terminal 6:**
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

---

**Step 8: Observe v2 Worker (DRAINING)**

**Terminal 4 (v2 Worker):**
```
[INFO] No new workflows being assigned
[INFO] Completing existing workflow: sub-XXX (5 remaining)
[INFO] Payment successful for cycle 3 (total: 3)
...
[INFO] All workflows complete. Worker ready to shut down.
```

---

**Step 9: Wait for v2 Workflows to Complete**

Monitor progress:
```bash
watch -n 30 "temporal workflow list --query 'BuildIds=\"v2.0-grace-period\" AND ExecutionStatus=\"Running\"' | wc -l"
```

**Timeline:** Up to 15 minutes
- 12 minutes for max-cycle completion
- 5 minutes for pause + timeout (2min to pause + 3min timeout)

---

**Step 10: v2 is DRAINED**

**Terminal 6:**
```bash
# Verify no v2 workflows
temporal workflow list \
  --query 'BuildIds="v2.0-grace-period" AND ExecutionStatus="Running"'
```

**Expected:** `No workflows found`

**Terminal 4 (v2 Worker):**
```
Press Ctrl+C
```

**v2 worker is now shut down!**

---

**Step 11: Final State**

```
┌─────────────────┐
│  v3.0: ACTIVE   │ ← Only version, 100% traffic
└─────────────────┘
```

---

**✅ TEST 3 COMPLETE!**

**What you demonstrated:**
- ✅ Progressive ramp: 20% → 50% → 80% → 100%
- ✅ Canary deployment pattern
- ✅ Monitoring at each ramp stage
- ✅ Behavioral comparison (v2 vs v3 grace periods)
- ✅ Final promotion to 100%
- ✅ v2 draining and drained

---

## TEST 4: Rollback Scenario (v3 Has Issues)

**Goal:** Quickly revert from v3 back to v2 when issue detected

**Simulated Scenario:** During 30% ramp, v3 is causing errors. Need immediate rollback.

---

### **Step 1: Restart v2 Worker**

**Terminal 4:**
```bash
mvn exec:java -Dexec.mainClass="com.example.subscription.worker.WorkerAppV2"
```

---

**Step 2: Set v3 to 30% Ramp (Simulate Canary)**

**Terminal 6:**
```bash
temporal task-queue update-build-ids add-new-default \
  --task-queue SUBSCRIPTION_TASK_QUEUE \
  --build-id v3.0-escalating-grace \
  --ramp-percentage 30
```

---

**Step 3: Create Some Workflows**

**Terminal 6:**
```bash
for i in {1..10}; do
  curl -X POST "http://localhost:8081/subscribe?initialBalance=100"
  sleep 2
done
```

**Expected:** ~7 on v2, ~3 on v3

---

**Step 4: Detect Issue (Simulated)**

Let's say you notice v3 workflows have higher error rates (in reality, you'd monitor metrics).

**Simulate checking workflow status:**
```bash
temporal workflow list \
  --query 'BuildIds="v3.0-escalating-grace" AND ExecutionStatus="Running"'
```

---

**Step 5: IMMEDIATE ROLLBACK**

**Terminal 6:**
```bash
# Remove v3 from default set, restore v2 to 100%
temporal task-queue update-build-ids promote-build-id-within-set \
  --task-queue SUBSCRIPTION_TASK_QUEUE \
  --build-id v2.0-grace-period

# This instantly routes all NEW traffic back to v2
```

---

**Step 6: Observe Rollback**

**Immediately create new workflows:**
```bash
for i in {1..5}; do
  curl -X POST "http://localhost:8081/subscribe?initialBalance=100"
  sleep 2
done
```

**Observe Terminal 4 (v2 Worker):**
- Immediately starts receiving ALL new workflows again

**Observe Terminal 5 (v3 Worker):**
- Only continues processing workflows already assigned
- No new workflows (isolated!)

---

**Step 7: Handle In-Flight v3 Workflows**

**Option A: Let them complete naturally (safe)**
- v3 workflows already running continue on v3 worker
- They will complete over time (up to 15 minutes)

**Option B: Manually cancel and restart on v2 (if critical)**
```bash
# Get v3 workflows
temporal workflow list --query 'BuildIds="v3.0-escalating-grace" AND ExecutionStatus="Running"'

# For each, cancel
temporal workflow cancel --workflow-id {id}

# Customer can re-subscribe (will use v2)
```

---

**Step 8: Verify Rollback Complete**

**Terminal 6:**
```bash
temporal task-queue describe --task-queue SUBSCRIPTION_TASK_QUEUE
```

**Expected Output:**
```
Build IDs:
  - v2.0-grace-period (default, 100%) ← ACTIVE
  - v3.0-escalating-grace (reachable) ← INACTIVE
```

---

**✅ TEST 4 COMPLETE!**

**What you demonstrated:**
- ✅ Instant rollback from ramping version
- ✅ Traffic immediately redirected to stable version
- ✅ In-flight workflows isolated (no new assignments)
- ✅ Zero downtime during rollback
- ✅ Safety of percentage-based rollout

---

## TEST 5: Observing Automatic Completion

**Goal:** Watch workflows complete via both mechanisms (12 cycles and pause timeout)

**Starting Point:** v2 ACTIVE

---

### **Step 1: Create Subscriptions with Different Behaviors**

**Terminal 6:**
```bash
# Subscription A: Will complete 12 cycles (~12 minutes)
SUB_A=$(curl -s -X POST "http://localhost:8081/subscribe?initialBalance=200" | jq -r '.subscriptionId')
echo "SUB_A (12 cycles): $SUB_A"

# Subscription B: Will pause and auto-cancel (~5 minutes)
SUB_B=$(curl -s -X POST "http://localhost:8081/subscribe?initialBalance=15" | jq -r '.subscriptionId')
echo "SUB_B (auto-cancel): $SUB_B"

# Subscription C: Will pause, we'll resume it
SUB_C=$(curl -s -X POST "http://localhost:8081/subscribe?initialBalance=15" | jq -r '.subscriptionId')
echo "SUB_C (resume): $SUB_C"
```

---

### **Step 2: Monitor SUB_B (Pause Timeout)**

**Terminal 6:**
```bash
# Watch status every 30 seconds
watch -n 30 "curl -s http://localhost:8081/status/$SUB_B | jq '{state, billingCycle, lastPaymentStatus}'"
```

---

### **Step 3: Expected Timeline for SUB_B**

```
Time 0:00 - Created, ACTIVE, balance $15
Time 1:00 - Cycle 1 success, balance $5
Time 2:00 - Cycle 2 fails (3 retries), enters GRACE PERIOD
Time 2:30 - Grace period ends, tries once more, fails, enters PAUSED
Time 2:30-5:30 - Waiting in PAUSED state (timeout: 3 minutes)
Time 5:30 - Auto-cancel triggered
          Worker logs: "⏰ Subscription paused for 3 minutes. Auto-cancelling."
          Status: "CANCELLED_PAUSE_TIMEOUT"
          Workflow completes ✅
```

---

### **Step 4: Resume SUB_C Before Timeout**

**Wait for SUB_C to pause (~2 minutes):**
```bash
sleep 130

# Check it's paused
curl "http://localhost:8081/status/$SUB_C" | jq '.state'
# Should show: "PAUSED"
```

**Add money:**
```bash
curl -X POST "http://localhost:8081/wallet/$SUB_C/add?amount=100"
```

**Resume (before 3-minute timeout):**
```bash
curl -X POST "http://localhost:8081/resume/$SUB_C"
```

**Worker logs:**
```
[INFO] Resume signal received
[INFO] Subscription RESUMED, continuing from cycle 2
[INFO] Starting billing cycle 2 for subscription sub-C
[INFO] Payment successful for cycle 2 (total: 2)
```

**SUB_C will now continue and complete after 12 cycles total!**

---

### **Step 5: Monitor SUB_A (12-Cycle Completion)**

**Terminal 6:**
```bash
# This will take ~12 minutes
watch -n 30 "curl -s http://localhost:8081/status/$SUB_A | jq '{state, billingCycle, totalPaymentsProcessed}'"
```

**Expected Progression:**
```
Cycle 1: {state: "ACTIVE", billingCycle: 1, totalPaymentsProcessed: 1}
Cycle 2: {state: "ACTIVE", billingCycle: 2, totalPaymentsProcessed: 2}
...
Cycle 12: {state: "ACTIVE", billingCycle: 12, totalPaymentsProcessed: 12}
After cycle 12:
  Worker logs: "✅ Subscription completed 12 billing cycles. Ending workflow gracefully."
  State: "COMPLETED_MAX_CYCLES"
  Workflow completes ✅
```

---

### **Step 6: Verify in Temporal UI**

**Browser → http://localhost:8080:**

Filter workflows:
- SUB_A: Status=Completed, Build ID shows version
- SUB_B: Status=Completed
- SUB_C: Status=Completed (after resume and 12 cycles)

**Event history shows:**
- SUB_A: 12 billing cycles with timers
- SUB_B: Pause + 3-minute timeout timer + completion
- SUB_C: Pause + resume signal + remaining cycles + completion

---

**✅ TEST 5 COMPLETE!**

**What you demonstrated:**
- ✅ Automatic completion after 12 cycles
- ✅ Automatic cancellation after 3 minutes in PAUSED
- ✅ Manual resume interrupts timeout
- ✅ Workflows don't run forever
- ✅ Enables clean version draining

---

## TEST 6: Version Pinning for Specific Customers

**Goal:** Route specific subscriptions to specific versions regardless of default

---

### **Use Cases:**
- VIP customers: Always use stable version
- Test accounts: Try bleeding-edge version
- Legacy contracts: Stay on old version until migration

---

### **Step 1: Ensure All Three Workers Running**

**Terminal 3:** v1 worker
**Terminal 4:** v2 worker (set as default)
**Terminal 5:** v3 worker

**Terminal 6:**
```bash
# Set v2 as default
temporal task-queue update-build-ids add-new-default \
  --task-queue SUBSCRIPTION_TASK_QUEUE \
  --build-id v2.0-grace-period
```

---

### **Step 2: Create Pinned Subscriptions**

**Terminal 6:**
```bash
# VIP customer on stable v2
VIP_SUB=$(curl -s -X POST "http://localhost:8081/subscribe?initialBalance=500&pinVersion=v2.0-grace-period" | jq -r '.subscriptionId')
echo "VIP on v2: $VIP_SUB"

# Test customer on latest v3
TEST_SUB=$(curl -s -X POST "http://localhost:8081/subscribe?initialBalance=500&pinVersion=v3.0-escalating-grace" | jq -r '.subscriptionId')
echo "Test on v3: $TEST_SUB"

# Legacy customer on v1
LEGACY_SUB=$(curl -s -X POST "http://localhost:8081/subscribe?initialBalance=500&pinVersion=v1.0-immediate-pause" | jq -r '.subscriptionId')
echo "Legacy on v1: $LEGACY_SUB"

# Regular customer (uses default = v2)
REGULAR_SUB=$(curl -s -X POST "http://localhost:8081/subscribe?initialBalance=500" | jq -r '.subscriptionId')
echo "Regular (default): $REGULAR_SUB"
```

---

### **Step 3: Verify Routing**

**Observe Worker Logs:**

**Terminal 3 (v1):**
```
[INFO] 🆕 Starting subscription with VERSION: v1.0-immediate-pause - subscriptionId: $LEGACY_SUB
```

**Terminal 4 (v2):**
```
[INFO] 🆕 Starting subscription with VERSION: v2.0-grace-period - subscriptionId: $VIP_SUB
[INFO] 🆕 Starting subscription with VERSION: v2.0-grace-period - subscriptionId: $REGULAR_SUB
```

**Terminal 5 (v3):**
```
[INFO] 🆕 Starting subscription with VERSION: v3.0-escalating-grace - subscriptionId: $TEST_SUB
```

---

### **Step 4: Verify in Temporal UI**

**Browser → http://localhost:8080:**
- VIP_SUB: Build ID = v2.0-grace-period
- TEST_SUB: Build ID = v3.0-escalating-grace
- LEGACY_SUB: Build ID = v1.0-immediate-pause
- REGULAR_SUB: Build ID = v2.0-grace-period (current default)

---

### **Step 5: Observe Behavior Under Failure**

**Terminal 6:**
```bash
# Wait for first cycle to complete, then remove money
sleep 70

curl -X POST "http://localhost:8081/wallet/$LEGACY_SUB/remove?amount=490"
curl -X POST "http://localhost:8081/wallet/$VIP_SUB/remove?amount=490"
curl -X POST "http://localhost:8081/wallet/$TEST_SUB/remove?amount=490"
```

---

### **Step 6: Watch Different Behaviors**

**Terminal 3 (LEGACY on v1):**
```
[WARN] Payment failed (attempt 1/3): Insufficient funds...
[WARN] Payment failed (attempt 2/3): Insufficient funds...
[WARN] Payment failed (attempt 3/3): Insufficient funds...
[WARN] Subscription PAUSED after 3 failed attempts    ← IMMEDIATE PAUSE (v1)
```

**Terminal 4 (VIP on v2):**
```
[WARN] Payment failed (attempt 1/3): Insufficient funds...
[WARN] Payment failed (attempt 2/3): Insufficient funds...
[WARN] Payment failed (attempt 3/3): Insufficient funds...
[WARN] ⏰ Entering 30-second GRACE PERIOD              ← 30s GRACE (v2)
[INFO] Attempting payment after grace period...
[WARN] Payment failed after grace period. Pausing.
```

**Terminal 5 (TEST on v3):**
```
[WARN] Payment failed (attempt 1/3): Insufficient funds...
[WARN] Payment failed (attempt 2/3): Insufficient funds...
[WARN] Payment failed (attempt 3/3): Insufficient funds...
[WARN] ⏰ Entering 10-second GRACE PERIOD              ← 10s GRACE (v3)
[INFO] Attempting payment after grace period...
[WARN] ⏰⏰ Entering 20-second GRACE PERIOD            ← 20s GRACE (v3)
[INFO] Attempting payment after grace period...
[WARN] ⏰⏰⏰ Entering 30-second GRACE PERIOD          ← 30s GRACE (v3)
[INFO] Attempting payment after grace period...
[WARN] Payment failed after all grace periods. Will pause.
```

**Three versions, three different behaviors! 🎯**

---

### **Step 7: Version Upgrade Path**

**To migrate LEGACY customer to v2:**

```bash
# 1. Cancel old workflow
curl -X POST "http://localhost:8081/cancel/$LEGACY_SUB"

# 2. Create new subscription with v2 pin
NEW_SUB=$(curl -s -X POST "http://localhost:8081/subscribe?initialBalance=500&pinVersion=v2.0-grace-period" | jq -r '.subscriptionId')
echo "Migrated to v2: $NEW_SUB"
```

**Now customer has v2 features (30s grace period)!**

---

**✅ TEST 6 COMPLETE!**

**What you demonstrated:**
- ✅ Version pinning (explicit version assignment)
- ✅ Pinned workflows bypass default routing
- ✅ Different customers can use different versions
- ✅ Behavioral differences across versions
- ✅ Manual migration path

---

## TEST 7: Draining and Drained States

**Goal:** Understand draining process in detail

**Starting Point:** v2 ACTIVE with multiple workflows

---

### **Step 1: Create Multiple Workflows on v2**

**Terminal 6:**
```bash
# Create 5 workflows with varying balances
curl -X POST "http://localhost:8081/subscribe?initialBalance=200"  # Will complete 12 cycles
curl -X POST "http://localhost:8081/subscribe?initialBalance=15"   # Will pause and auto-cancel
curl -X POST "http://localhost:8081/subscribe?initialBalance=100"  # Will complete 12 cycles
curl -X POST "http://localhost:8081/subscribe?initialBalance=15"   # Will pause and auto-cancel
curl -X POST "http://localhost:8081/subscribe?initialBalance=200"  # Will complete 12 cycles
```

---

### **Step 2: Verify v2 Has 5 Running Workflows**

**Terminal 6:**
```bash
temporal workflow list \
  --query 'BuildIds="v2.0-grace-period" AND ExecutionStatus="Running"'
```

**Expected:** Shows 5 workflows

---

### **Step 3: Promote v3 (v2 Becomes DRAINING)**

**Terminal 6:**
```bash
temporal task-queue update-build-ids add-new-default \
  --task-queue SUBSCRIPTION_TASK_QUEUE \
  --build-id v3.0-escalating-grace

temporal task-queue update-build-ids add-new-compatible \
  --task-queue SUBSCRIPTION_TASK_QUEUE \
  --build-id v3.0-escalating-grace \
  --existing-compatible-build-id v2.0-grace-period
```

---

### **Step 4: Monitor Draining Progress**

**Terminal 6:**
```bash
# Watch workflow count decrease
watch -n 10 "temporal workflow list --query 'BuildIds=\"v2.0-grace-period\" AND ExecutionStatus=\"Running\"' | wc -l"
```

---

### **Step 5: Observe v2 Worker Logs (DRAINING)**

**Terminal 4 (v2 Worker):**
```
[INFO] No new workflows being assigned
[INFO] Completing existing workflow: sub-XXX (5 remaining)
[INFO] Payment successful for cycle 3 (total: 3)
[INFO] Waiting 1 minute until next billing cycle...
...
[INFO] Completing existing workflow: sub-YYY (4 remaining)
[WARN] ⏰ Subscription paused for 3 minutes. Auto-cancelling.
[INFO] Subscription ended. Final state: CANCELLED_PAUSE_TIMEOUT
...
[INFO] Completing existing workflow: sub-ZZZ (1 remaining)
[INFO] ✅ Subscription completed 12 billing cycles.
[INFO] Subscription ended. Final state: COMPLETED_MAX_CYCLES
[INFO] All workflows complete. Worker ready to shut down.
```

---

### **Step 6: Expected Timeline**

```
Time 0:00 - Promote v3, v2 has 5 workflows (DRAINING)
Time 5:00 - 2 workflows auto-cancel (pause timeout) - 3 remaining
Time 8:00 - 1 workflow completes (12 cycles) - 2 remaining
Time 12:00 - 1 workflow completes (12 cycles) - 1 remaining
Time 15:00 - 1 workflow completes (12 cycles) - 0 remaining
Time 15:00 - v2 is DRAINED ✅
```

---

### **Step 7: Verify DRAINED**

**Terminal 6:**
```bash
temporal workflow list \
  --query 'BuildIds="v2.0-grace-period" AND ExecutionStatus="Running"'
```

**Expected:** `No workflows found`

---

### **Step 8: Safe Shutdown**

**Terminal 4:**
```
Press Ctrl+C
```

**v2 worker gracefully shuts down!**

---

**✅ TEST 7 COMPLETE!**

**What you demonstrated:**
- ✅ DRAINING state (no new workflows, completing existing)
- ✅ Countdown of remaining workflows
- ✅ Multiple completion mechanisms (12 cycles, pause timeout)
- ✅ DRAINED state (zero workflows, safe to shut down)
- ✅ Graceful shutdown process

---

## Quick Reference Commands

### **Worker Management**

```bash
# Start v1 worker
mvn exec:java -Dexec.mainClass="com.example.subscription.worker.WorkerApp"

# Start v2 worker
mvn exec:java -Dexec.mainClass="com.example.subscription.worker.WorkerAppV2"

# Start v3 worker
mvn exec:java -Dexec.mainClass="com.example.subscription.worker.WorkerAppV3"
```

---

### **Version Management**

```bash
# Set version as default (100% traffic)
temporal task-queue update-build-ids add-new-default \
  --task-queue SUBSCRIPTION_TASK_QUEUE \
  --build-id <version>

# Set version with ramp percentage
temporal task-queue update-build-ids add-new-default \
  --task-queue SUBSCRIPTION_TASK_QUEUE \
  --build-id <version> \
  --ramp-percentage <0-100>

# Mark as compatible (for draining old version)
temporal task-queue update-build-ids add-new-compatible \
  --task-queue SUBSCRIPTION_TASK_QUEUE \
  --build-id <new-version> \
  --existing-compatible-build-id <old-version>

# Describe task queue (see all versions)
temporal task-queue describe --task-queue SUBSCRIPTION_TASK_QUEUE

# Rollback to previous version
temporal task-queue update-build-ids promote-build-id-within-set \
  --task-queue SUBSCRIPTION_TASK_QUEUE \
  --build-id <previous-version>
```

---

### **Subscription Management**

```bash
# Subscribe with default version
curl -X POST "http://localhost:8081/subscribe?initialBalance=100"

# Subscribe with pinned version
curl -X POST "http://localhost:8081/subscribe?initialBalance=100&pinVersion=v2.0-grace-period"

# Check status
curl http://localhost:8081/status/{id} | jq

# Add money
curl -X POST "http://localhost:8081/wallet/{id}/add?amount=50"

# Resume
curl -X POST http://localhost:8081/resume/{id}

# Cancel
curl -X POST http://localhost:8081/cancel/{id}
```

---

### **Monitoring**

```bash
# List workflows by version
temporal workflow list --query 'BuildIds="<version>" AND ExecutionStatus="Running"'

# Count workflows by version
temporal workflow list --query 'BuildIds="<version>" AND ExecutionStatus="Running"' | wc -l

# Watch workflow count
watch -n 10 "temporal workflow list --query 'BuildIds=\"<version>\" AND ExecutionStatus=\"Running\"' | wc -l"

# Check workflow status
watch -n 30 "curl -s http://localhost:8081/status/{id} | jq"
```

---

## Summary

After completing all tests, you have successfully demonstrated:

### **Version States:**
- ✅ **INACTIVE**: Worker deployed but not receiving traffic
- ✅ **ACTIVE**: Current default version receiving 100% or (100-ramp)% of traffic
- ✅ **RAMPING**: New version receiving configured percentage of traffic
- ✅ **DRAINING**: Old version completing existing workflows, no new assignments
- ✅ **DRAINED**: Zero workflows, safe to shut down

### **Features:**
- ✅ **Rainbow Deployment**: Gradual rollout with percentage-based traffic splitting
- ✅ **Version Pinning**: Explicit version assignment for specific workflows
- ✅ **Automatic Completion**: Workflows complete after 12 cycles or 3-minute pause timeout
- ✅ **Rollback**: Instant revert to previous stable version
- ✅ **Zero-Downtime Migration**: Seamless version transitions

### **Three Versions:**
- ✅ **v1.0**: Immediate pause after 3 retries
- ✅ **v2.0**: 30-second grace period before pausing
- ✅ **v3.0**: Escalating grace periods (10s → 20s → 30s)

---

**You've mastered Temporal Worker Versioning! 🎉**

For more information, see:
- [Temporal Worker Versioning Docs](https://docs.temporal.io/production-deployment/worker-deployments/worker-versioning)
- [Worker Deployment Guide](https://docs.temporal.io/production-deployment/worker-deployments)
