# Complete Testing Guide - Step by Step

This is your **hands-on lab manual** to test every scenario. Follow these steps exactly.

---

## 🚀 Pre-requisites: Start Everything

### **Step 1: Start Temporal Server**

**Terminal 1:**
```bash
cd /Users/nms/Documents/docker-compose/subscription-billing/subscription-billing
cd ..  # Go to directory with docker-compose.yml
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

### **Step 2: Start Worker**

**Terminal 2:**
```bash
cd /Users/nms/Documents/docker-compose/subscription-billing/subscription-billing
mvn exec:java -Dexec.mainClass="com.example.subscription.worker.WorkerApp"
```

**Expected Output:**
```
✓ Worker started and listening on SUBSCRIPTION_TASK_QUEUE...
✓ Ready to process subscription workflows
```

**What to Watch:**
- This terminal will show workflow execution logs
- Keep it visible while testing

---

### **Step 3: Start API**

**Terminal 3:**
```bash
cd /Users/nms/Documents/docker-compose/subscription-billing/subscription-billing
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
Terminal 2: Worker (showing logs) ✅
Terminal 3: API (Spring Boot) ✅
Terminal 4: FREE for testing commands ✅
```

---

## 📋 TEST 1: Basic Subscription (Happy Path)

**Goal:** Create subscription, see successful billing cycle.

### **Step 1: Subscribe with Good Balance**

**Terminal 4:**
```bash
curl -X POST "http://localhost:8081/subscribe?initialBalance=100"
```

**Expected Response:**
```json
{
  "subscriptionId": "sub-1737451234567",
  "initialBalance": "100.0",
  "subscriptionPrice": "10.0",
  "status": "STARTED"
}
```

**Save the subscriptionId!** Replace `sub-XXX` in all commands below.

---

### **Step 2: Watch Worker Logs**

**Terminal 2 (Worker) - You Should See:**
```
[INFO] Subscription started: sub-1737451234567
[INFO] Starting billing cycle 1 for subscription sub-1737451234567
[TXN:a1b2c3d4] Starting payment for subscription: sub-1737451234567 (attempt #1)
[TXN:a1b2c3d4] Checking balance for subscription sub-1737451234567...
[TXN:a1b2c3d4] Balance: $100.0, Required: $10.0
[TXN:a1b2c3d4] Sufficient balance found. Processing charge...
[TXN:a1b2c3d4] ✓ Payment SUCCESS - Charged $10.0. New balance: $90.0
[INFO] Payment successful for cycle 1 (total: 1)
[INFO] Waiting 1 minute until next billing cycle...
```

**What Happened:**
- ✅ Workflow started
- ✅ First billing cycle executed
- ✅ Payment succeeded ($100 → $90)
- ✅ Workflow sleeping for 1 minute

---

### **Step 3: Check Status Immediately**

**Terminal 4:**
```bash
curl http://localhost:8081/status/sub-1769155744014 | jq
```

**Expected Response:**
```json
{
  "subscriptionId": "sub-1737451234567",
  "state": "ACTIVE",
  "billingCycle": 1,
  "retryAttempts": 0,
  "lastPaymentStatus": "SUCCESS",
  "totalPaymentsProcessed": 1,
  "currentBalance": 90.0,
  "subscriptionPrice": 10.0
}
```

**Verify:**
- ✅ state = "ACTIVE"
- ✅ billingCycle = 1
- ✅ totalPaymentsProcessed = 1
- ✅ currentBalance = 90.0 (was 100, charged 10)

---

### **Step 4: Check Balance Separately**

**Terminal 4:**
```bash
curl http://localhost:8081/wallet/sub-1769155744014 | jq
```

**Expected Response:**
```json
{
  "subscriptionId": "sub-1737451234567",
  "balance": 90.0,
  "subscriptionPrice": 10.0,
  "canSubscribe": true
}
```

---

### **Step 5: Check Temporal UI**

**Browser:**
1. Go to http://localhost:8080
2. Click "Workflows" in sidebar
3. Find your workflow: `sub-1737451234567`

**You Should See:**
- Status: Running
- Start Time: Just now
- Workflow Type: SubscriptionWorkflow

**Click on the workflow:**
- See complete event history
- See timers (1 minute sleep)
- See activity execution (charge)
- See activity result (success)

---

### **Step 6: Wait 1 Minute - Second Cycle**

**After 1 minute, Worker logs show:**
```
[INFO] Starting billing cycle 2 for subscription sub-1737451234567
[TXN:b2c3d4e5] Starting payment for subscription: sub-1737451234567 (attempt #1)
[TXN:b2c3d4e5] Balance: $90.0, Required: $10.0
[TXN:b2c3d4e5] ✓ Payment SUCCESS - Charged $10.0. New balance: $80.0
[INFO] Payment successful for cycle 2 (total: 2)
[INFO] Waiting 1 minute until next billing cycle...
```

**Check status again:**
```bash
curl http://localhost:8081/status/sub-1769155744014 | jq
```

**Now Shows:**
```json
{
  "billingCycle": 2,
  "totalPaymentsProcessed": 2,
  "currentBalance": 80.0
}
```

**✅ TEST 1 PASSED: Basic billing works!**

---

## 📋 TEST 2: Insufficient Funds - Pause & Resume

**Goal:** See what happens when money runs out.

### **Step 1: Create Subscription with Low Balance**

**Terminal 4:**
```bash
curl -X POST "http://localhost:8081/subscribe?initialBalance=15"
```

**Response:**
```json
{
  "subscriptionId": "sub-1737451234999",
  "initialBalance": "15.0",
  "status": "STARTED"
}
```

**Save this ID!** Let's call it `sub-LOW`.

---

### **Step 2: Watch First Cycle Succeed**

**Worker logs:**
```
[INFO] Starting billing cycle 1 for subscription sub-LOW
[TXN:c3d4e5f6] Balance: $15.0, Required: $10.0
[TXN:c3d4e5f6] ✓ Payment SUCCESS - Charged $10.0. New balance: $5.0
[INFO] Payment successful for cycle 1 (total: 1)
[INFO] Waiting 1 minute until next billing cycle...
```

**First payment worked! Balance now $5.**

---

### **Step 3: Check Status After First Cycle**

```bash
curl http://localhost:8081/status/sub-1769156103526 | jq
```

**Shows:**
```json
{
  "state": "ACTIVE",
  "billingCycle": 1,
  "totalPaymentsProcessed": 1,
  "currentBalance": 5.0
}
```

---

### **Step 4: Wait 1 Minute - Second Cycle FAILS**

**Worker logs show all 3 retry attempts:**
```
[INFO] Starting billing cycle 2 for subscription sub-LOW

[TXN:d4e5f6g7] Starting payment (attempt #1)
[TXN:d4e5f6g7] Balance: $5.0, Required: $10.0
[TXN:d4e5f6g7] Insufficient funds - Balance: $5.0, Required: $10.0
[WARN] Payment failed (attempt 1/3): Insufficient funds...

[Waits 5 seconds]

[TXN:e5f6g7h8] Starting payment (attempt #2)
[TXN:e5f6g7h8] Balance: $5.0, Required: $10.0
[TXN:e5f6g7h8] Insufficient funds - Balance: $5.0, Required: $10.0
[WARN] Payment failed (attempt 2/3): Insufficient funds...

[Waits 5 seconds]

[TXN:f6g7h8i9] Starting payment (attempt #3)
[TXN:f6g7h8i9] Balance: $5.0, Required: $10.0
[TXN:f6g7h8i9] Insufficient funds - Balance: $5.0, Required: $10.0
[WARN] Payment failed (attempt 3/3): Insufficient funds...

[WARN] Subscription PAUSED after 3 failed attempts in cycle 2
```

**What Happened:**
- ✅ Tried to charge $10 but only $5 available
- ✅ Retried 3 times with 5-second delays
- ✅ All failed (balance still only $5)
- ✅ Workflow entered PAUSED state

---

### **Step 5: Check Status (Should Be PAUSED)**

```bash
curl http://localhost:8081/status/sub-LOW | jq
```

**Expected:**
```json
{
  "state": "PAUSED",
  "billingCycle": 2,
  "retryAttempts": 3,
  "lastPaymentStatus": "FAILED - Insufficient funds - Balance: $5.0, Required: $10.0",
  "totalPaymentsProcessed": 1,
  "currentBalance": 5.0
}
```

**Verify:**
- ✅ state = "PAUSED"
- ✅ retryAttempts = 3
- ✅ lastPaymentStatus shows error
- ✅ totalPaymentsProcessed still 1 (second failed)

---

### **Step 6: Check Temporal UI**

**Browser → http://localhost:8080 → Find `sub-LOW`:**

**Event History Shows:**
1. WorkflowExecutionStarted
2. WorkflowTaskScheduled
3. WorkflowTaskStarted
4. ActivityTaskScheduled (attempt 1)
5. ActivityTaskStarted
6. ActivityTaskFailed (Insufficient funds)
7. TimerStarted (5 second delay)
8. TimerFired
9. ActivityTaskScheduled (attempt 2)
10. ActivityTaskFailed
11. TimerStarted (5 second delay)
12. TimerFired
13. ActivityTaskScheduled (attempt 3)
14. ActivityTaskFailed
15. **WorkflowExecutionContinuedAsNew** (or similar - workflow is waiting)

**Current State:** Waiting (blocked on `Workflow.await()`)

---

### **Step 7: Try to Add Money**

```bash
curl -X POST "http://localhost:8081/wallet/sub-LOW/add?amount=50" | jq
```

**Response:**
```json
{
  "subscriptionId": "sub-LOW",
  "amountAdded": 50.0,
  "newBalance": 55.0,
  "message": "Balance updated successfully"
}
```

**Verify balance:**
```bash
curl http://localhost:8081/wallet/sub-LOW | jq
```

**Shows:**
```json
{
  "balance": 55.0,
  "canSubscribe": true
}
```

**But check status:**
```bash
curl http://localhost:8081/status/sub-LOW | jq
```

**Still shows:**
```json
{
  "state": "PAUSED"
}
```

**Why?** Workflow doesn't know money was added! It's waiting for resume signal.

---

### **Step 8: Send Resume Signal**

```bash
curl -X POST http://localhost:8081/resume/sub-LOW | jq
```

**Response:**
```json
{
  "message": "Resume signal sent to subscription: sub-LOW",
  "action": "RESUMED"
}
```

**Worker logs show:**
```
[INFO] Resume signal received
[INFO] Subscription RESUMED, continuing from cycle 2
[INFO] Starting billing cycle 2 for subscription sub-LOW
[TXN:g7h8i9j0] Starting payment (attempt #1)
[TXN:g7h8i9j0] Balance: $55.0, Required: $10.0
[TXN:g7h8i9j0] ✓ Payment SUCCESS - Charged $10.0. New balance: $45.0
[INFO] Payment successful for cycle 2 (total: 2)
[INFO] Waiting 1 minute until next billing cycle...
```

**What Happened:**
- ✅ Signal received
- ✅ Workflow unblocked from `Workflow.await()`
- ✅ Retry counter reset to 0
- ✅ Tried billing again
- ✅ This time balance sufficient ($55)
- ✅ Payment succeeded!
- ✅ Back to ACTIVE state

---

### **Step 9: Verify Status (Should Be ACTIVE)**

```bash
curl http://localhost:8081/status/sub-LOW | jq
```

**Now Shows:**
```json
{
  "state": "ACTIVE",
  "billingCycle": 2,
  "retryAttempts": 0,
  "lastPaymentStatus": "SUCCESS",
  "totalPaymentsProcessed": 2,
  "currentBalance": 45.0
}
```

**Verify:**
- ✅ state = "ACTIVE" (was PAUSED)
- ✅ retryAttempts = 0 (reset)
- ✅ lastPaymentStatus = "SUCCESS"
- ✅ totalPaymentsProcessed = 2 (now includes cycle 2)
- ✅ currentBalance = 45.0 (was 55, charged 10)

**✅ TEST 2 PASSED: Pause and Resume works!**

---

## 📋 TEST 3: Cancellation

**Goal:** Cancel a running subscription gracefully.

### **Step 1: Create Subscription**

```bash
curl -X POST "http://localhost:8081/subscribe?initialBalance=100" | jq
```

Save the ID. Let's call it `sub-CANCEL`.

---

### **Step 2: Let It Run for 1 Cycle**

Wait for first billing cycle to complete. Worker logs show:
```
[INFO] Payment successful for cycle 1 (total: 1)
[INFO] Waiting 1 minute until next billing cycle...
```

---

### **Step 3: Send Cancel Signal**

```bash
curl -X POST http://localhost:8081/cancel/sub-CANCEL | jq
```

**Response:**
```json
{
  "message": "Cancel signal sent to subscription: sub-CANCEL",
  "action": "CANCELLED"
}
```

**Worker logs show:**
```
[INFO] Cancel signal received
[INFO] Subscription cancelled after 1 cycles and 1 successful payments
```

**What Happened:**
- ✅ Signal received
- ✅ `cancelled` flag set to true
- ✅ While loop exits: `while (!cancelled)`
- ✅ Workflow terminates gracefully

---

### **Step 4: Check Final Status**

```bash
curl http://localhost:8081/status/sub-CANCEL | jq
```

**Expected:**
```json
{
  "state": "CANCELLED",
  "billingCycle": 1,
  "totalPaymentsProcessed": 1,
  "currentBalance": 90.0
}
```

---

### **Step 5: Verify in Temporal UI**

**Browser → Find `sub-CANCEL`:**
- Status: Completed
- Close Time: Just now
- Result: Completed successfully

**Event History shows:**
- WorkflowExecutionStarted
- Activity executions
- Timer
- **SignalReceived** (cancel)
- WorkflowExecutionCompleted

**✅ TEST 3 PASSED: Cancellation works!**

---

## 📋 TEST 4: Payment Gateway Down

**Goal:** Simulate complete gateway outage, see pausing behavior.

### **Step 1: Enable Gateway Down Simulation**

```bash
curl -X POST http://localhost:8081/simulate/gateway-down/enable | jq
```

**Response:**
```json
{
  "message": "Gateway down simulation ENABLED"
}
```

**Worker logs:**
```
⚠️  Gateway down simulation ENABLED
```

---

### **Step 2: Create Subscription**

```bash
curl -X POST "http://localhost:8081/subscribe?initialBalance=100" | jq
```

Save ID as `sub-GATEWAY`.

---

### **Step 3: Watch All Retries Fail**

**Worker logs:**
```
[INFO] Starting billing cycle 1 for subscription sub-GATEWAY

[TXN:k1l2m3n4] Starting payment (attempt #1)
[TXN:k1l2m3n4] ⚠️  Payment gateway is temporarily unavailable (simulated)
[WARN] Payment failed (attempt 1/3): Payment gateway temporarily unavailable

[Waits 5 seconds]

[TXN:l2m3n4o5] Starting payment (attempt #2)
[TXN:l2m3n4o5] ⚠️  Payment gateway is temporarily unavailable (simulated)
[WARN] Payment failed (attempt 2/3): Payment gateway temporarily unavailable

[Waits 5 seconds]

[TXN:m3n4o5p6] Starting payment (attempt #3)
[TXN:m3n4o5p6] ⚠️  Payment gateway is temporarily unavailable (simulated)
[WARN] Payment failed (attempt 3/3): Payment gateway temporarily unavailable

[WARN] Payment gateway unavailable. Waiting 30 seconds before retry (cycle 1)...
```

**What Happened:**
- ✅ All 3 retry attempts failed (gateway down)
- ✅ Workflow detects gateway issue (not insufficient funds)
- ✅ Automatically waits 30 seconds and will retry
- ✅ NO manual intervention needed!

---

### **Step 4: Check Status (Should Be WAITING_FOR_GATEWAY)**

```bash
curl http://localhost:8081/status/sub-GATEWAY | jq
```

**Shows:**
```json
{
  "state": "WAITING_FOR_GATEWAY",
  "billingCycle": 1,
  "retryAttempts": 3,
  "lastPaymentStatus": "FAILED - Payment gateway temporarily unavailable",
  "totalPaymentsProcessed": 0
}
```

**Note:** State is "WAITING_FOR_GATEWAY", not "PAUSED" (no manual resume needed)

---

### **Step 5: Watch Automatic Retry (Gateway Still Down)**

After 30 seconds, workflow automatically retries:

**Worker logs:**
```
[INFO] Retrying payment after gateway wait (cycle 1)...
[TXN:n4o5p6q7] Starting payment (attempt #1)
[TXN:n4o5p6q7] ⚠️  Payment gateway is temporarily unavailable (simulated)
[WARN] Payment failed (attempt 1/3): Payment gateway temporarily unavailable
[...retries 2 more times...]
[WARN] Payment gateway unavailable. Waiting 30 seconds before retry (cycle 1)...
```

**This will keep happening every 30 seconds until gateway recovers!**

---

### **Step 6: Simulate Gateway Recovery**

```bash
curl -X POST http://localhost:8081/simulate/gateway-down/disable | jq
```

**Response:**
```json
{
  "message": "Gateway down simulation DISABLED",
  "status": "Payments will now process normally"
}
```

**Gateway is "back online" now!**

---

### **Step 7: Watch Automatic Success (No Manual Resume Needed!)**

Within 30 seconds, the next automatic retry will succeed:

**Worker logs:**
```
[INFO] Retrying payment after gateway wait (cycle 1)...
[TXN:p6q7r8s9] Starting payment (attempt #1)
[DEBUG] [TXN:p6q7r8s9] Gateway status check: UP
[INFO] [TXN:p6q7r8s9] Checking balance for subscription sub-GATEWAY...
[INFO] [TXN:p6q7r8s9] Balance: $100.0, Required: $10.0
[INFO] [TXN:p6q7r8s9] ✓ Payment SUCCESS - Charged $10.0. New balance: $90.0
[INFO] Payment successful for cycle 1 (total: 1)
[INFO] Waiting 1 minute until next billing cycle...
```

**What Happened:**
- ✅ Gateway back online
- ✅ Workflow automatically retried (no manual signal needed!)
- ✅ Payment succeeded
- ✅ Subscription continues normally

**✅ TEST 4 PASSED: Handles external system failures!**

---

## 📋 TEST 5: Worker Crash Recovery

**Goal:** Kill worker, see state persists, restart continues seamlessly.

### **Step 1: Create Subscription**

```bash
curl -X POST "http://localhost:8081/subscribe?initialBalance=100" | jq
```

Save ID as `sub-CRASH`.

---

### **Step 2: Wait for First Cycle**

**Worker logs:**
```
[INFO] Payment successful for cycle 1 (total: 1)
[INFO] Waiting 1 minute until next billing cycle...
```

---

### **Step 3: Check Status**

```bash
curl http://localhost:8081/status/sub-CRASH | jq
```

**Shows:**
```json
{
  "state": "ACTIVE",
  "billingCycle": 1,
  "totalPaymentsProcessed": 1,
  "currentBalance": 90.0
}
```

**Note the values!**

---

### **Step 4: Kill the Worker**

**Terminal 2 (Worker):**
```
Press Ctrl+C
```

**Output:**
```
^C
Process terminated
```

**Worker is dead! Terminal 2 is now idle.**

---

### **Step 5: Check Status (Worker Dead!)**

```bash
curl http://localhost:8081/status/sub-CRASH | jq
```

**Still works!**
```json
{
  "state": "ACTIVE",
  "billingCycle": 1,
  "totalPaymentsProcessed": 1,
  "currentBalance": 90.0
}
```

**Why?** State is in Temporal, not in worker memory!

---

### **Step 6: Check Temporal UI (Worker Dead!)**

**Browser → http://localhost:8080 → Find `sub-CRASH`:**
- Status: Running
- Shows complete history
- Timer is still counting down

**Temporal server is still managing the workflow!**

---

### **Step 7: Wait for Timer to Expire**

Wait 1 minute (or however long is left). Timer expires but no worker to process it.

**Check status:**
```bash
curl http://localhost:8081/status/sub-CRASH | jq
```

**Still shows cycle 1** (task waiting in queue).

---

### **Step 8: Restart Worker**

**Terminal 2:**
```bash
mvn exec:java -Dexec.mainClass="com.example.subscription.worker.WorkerApp"
```

**Worker starts:**
```
✓ Worker started and listening on SUBSCRIPTION_TASK_QUEUE...
```

**Within seconds, logs show:**
```
[INFO] Starting billing cycle 2 for subscription sub-CRASH
[TXN:o5p6q7r8] Starting payment (attempt #1)
[TXN:o5p6q7r8] Balance: $90.0, Required: $10.0
[TXN:o5p6q7r8] ✓ Payment SUCCESS - Charged $10.0. New balance: $80.0
[INFO] Payment successful for cycle 2 (total: 2)
[INFO] Waiting 1 minute until next billing cycle...
```

**What Happened:**
- ✅ Worker restarted
- ✅ Immediately picked up waiting task
- ✅ Loaded workflow state from Temporal
- ✅ Continued from exact point (after sleep, cycle 2)
- ✅ No missed billing cycle
- ✅ No duplicate charge

---

### **Step 9: Verify Status**

```bash
curl http://localhost:8081/status/sub-CRASH | jq
```

**Now shows:**
```json
{
  "state": "ACTIVE",
  "billingCycle": 2,
  "totalPaymentsProcessed": 2,
  "currentBalance": 80.0
}
```

**Verify:**
- ✅ billingCycle advanced from 1 to 2
- ✅ totalPaymentsProcessed from 1 to 2
- ✅ currentBalance from 90 to 80
- ✅ Seamless recovery!

**✅ TEST 5 PASSED: Worker crash recovery works!**

---

## 📋 TEST 6: Multiple Workers (High Availability)

**Goal:** Run 2 workers, kill one, see automatic failover.

### **Step 1: Start Second Worker**

**Terminal 5 (New!):**
```bash
cd /Users/nms/Documents/docker-compose/subscription-billing/subscription-billing
mvn exec:java -Dexec.mainClass="com.example.subscription.worker.WorkerApp"
```

**Output:**
```
✓ Worker started and listening on SUBSCRIPTION_TASK_QUEUE...
```

**Now you have:**
- Terminal 2: Worker 1 ✅
- Terminal 5: Worker 2 ✅

---

### **Step 2: Create Multiple Subscriptions**

```bash
curl -X POST "http://localhost:8081/subscribe?initialBalance=100"
curl -X POST "http://localhost:8081/subscribe?initialBalance=100"
curl -X POST "http://localhost:8081/subscribe?initialBalance=100"
curl -X POST "http://localhost:8081/subscribe?initialBalance=100"
curl -X POST "http://localhost:8081/subscribe?initialBalance=100"
```

Save all IDs: `sub-1`, `sub-2`, `sub-3`, `sub-4`, `sub-5`.

---

### **Step 3: Watch Both Worker Terminals**

**Terminal 2 (Worker 1):**
```
[INFO] Starting billing cycle 1 for subscription sub-1
[INFO] Starting billing cycle 1 for subscription sub-3
[INFO] Starting billing cycle 1 for subscription sub-5
```

**Terminal 5 (Worker 2):**
```
[INFO] Starting billing cycle 1 for subscription sub-2
[INFO] Starting billing cycle 1 for subscription sub-4
```

**What Happened:**
- ✅ Tasks distributed between workers
- ✅ Temporal load balancing
- ✅ Worker 1 got: sub-1, sub-3, sub-5
- ✅ Worker 2 got: sub-2, sub-4

---

### **Step 4: Kill Worker 1**

**Terminal 2:**
```
Press Ctrl+C
```

**Worker 1 is dead.**

---

### **Step 5: Wait for Next Billing Cycle (1 Minute)**

**Terminal 5 (Worker 2) - Now Shows ALL Subscriptions:**
```
[INFO] Starting billing cycle 2 for subscription sub-1  ← Was Worker 1
[INFO] Starting billing cycle 2 for subscription sub-2  ← Always Worker 2
[INFO] Starting billing cycle 2 for subscription sub-3  ← Was Worker 1
[INFO] Starting billing cycle 2 for subscription sub-4  ← Always Worker 2
[INFO] Starting billing cycle 2 for subscription sub-5  ← Was Worker 1
```

**What Happened:**
- ✅ Worker 2 automatically picked up Worker 1's tasks
- ✅ Zero downtime
- ✅ No manual intervention
- ✅ All subscriptions continue billing

---

### **Step 6: Verify All Subscriptions**

```bash
curl http://localhost:8081/status/sub-1 | jq '.billingCycle'  # Should be 2
curl http://localhost:8081/status/sub-2 | jq '.billingCycle'  # Should be 2
curl http://localhost:8081/status/sub-3 | jq '.billingCycle'  # Should be 2
curl http://localhost:8081/status/sub-4 | jq '.billingCycle'  # Should be 2
curl http://localhost:8081/status/sub-5 | jq '.billingCycle'  # Should be 2
```

**All should show `billingCycle: 2`.**

**✅ TEST 6 PASSED: High availability works!**

---

## 📋 TEST 7: Query During Execution

**Goal:** Query workflow status in real-time, see it update.

### **Step 1: Create Subscription**

```bash
curl -X POST "http://localhost:8081/subscribe?initialBalance=100" | jq
```

Save ID as `sub-QUERY`.

---

### **Step 2: Query Immediately**

```bash
curl http://localhost:8081/status/sub-QUERY | jq
```

**Shows:**
```json
{
  "state": "ACTIVE",
  "billingCycle": 1,
  "totalPaymentsProcessed": 1
}
```

---

### **Step 3: Query Multiple Times**

Run this in a loop:
```bash
watch -n 5 'curl -s http://localhost:8081/status/sub-QUERY | jq ".billingCycle, .totalPaymentsProcessed, .state"'
```

**What You See:**
```
Cycle 1, Payments: 1, State: ACTIVE
[Wait 55 seconds]
Cycle 2, Payments: 2, State: ACTIVE
[Wait 55 seconds]
Cycle 3, Payments: 3, State: ACTIVE
```

**Observations:**
- ✅ Query is instant (doesn't wait for workflow)
- ✅ Shows real-time state
- ✅ Non-blocking
- ✅ Accurate

**✅ TEST 7 PASSED: Real-time queries work!**

---

## 📋 TEST 8: Complete Demo Flow

**Goal:** Test all features in sequence.

### **Step 1: Subscribe**
```bash
SUB_ID=$(curl -s -X POST "http://localhost:8081/subscribe?initialBalance=25" | jq -r '.subscriptionId')
echo "Created: $SUB_ID"
```

### **Step 2: Check Status**
```bash
curl http://localhost:8081/status/$SUB_ID | jq
```

### **Step 3: Wait for First Cycle**
Wait 1 minute. Check status:
```bash
curl http://localhost:8081/status/$SUB_ID | jq
```
Should show: cycle 1, balance 15.0

### **Step 4: Wait for Second Cycle (Will Fail)**
Wait another minute. Should pause due to insufficient funds.

### **Step 5: Verify Paused**
```bash
curl http://localhost:8081/status/$SUB_ID | jq '.state'
```
Should show: "PAUSED"

### **Step 6: Add Money**
```bash
curl -X POST "http://localhost:8081/wallet/$SUB_ID/add?amount=100" | jq
```

### **Step 7: Resume**
```bash
curl -X POST http://localhost:8081/resume/$SUB_ID | jq
```

### **Step 8: Verify Active**
```bash
curl http://localhost:8081/status/$SUB_ID | jq '.state'
```
Should show: "ACTIVE"

### **Step 9: Cancel**
```bash
curl -X POST http://localhost:8081/cancel/$SUB_ID | jq
```

### **Step 10: Verify Cancelled**
```bash
curl http://localhost:8081/status/$SUB_ID | jq '.state'
```
Should show: "CANCELLED"

**✅ TEST 8 PASSED: Complete flow works!**

---

## 🧹 Cleanup Commands

### **Cancel All Active Subscriptions**

```bash
# Get all subscription IDs you created
# Cancel each one
curl -X POST http://localhost:8081/cancel/sub-1737451234567
curl -X POST http://localhost:8081/cancel/sub-1737451234568
# ... etc
```

### **Stop All Services**

```bash
# Terminal 2 & 5: Stop workers
Ctrl+C

# Terminal 3: Stop API
Ctrl+C

# Terminal 1: Stop Temporal
Ctrl+C
```

### **Clean Temporal Data**

```bash
cd /Users/nms/Documents/docker-compose/subscription-billing/subscription-billing
./reset-temporal.sh
```

This deletes all workflow history.

---

## 📊 Summary Checklist

After running all tests, you should have verified:

- ✅ **Basic Billing**: Charges every minute, balance decreases
- ✅ **Insufficient Funds**: Retries 3 times, then pauses
- ✅ **Resume**: Adding money + signal resumes billing
- ✅ **Cancel**: Graceful termination
- ✅ **Gateway Down**: Pauses when external system fails
- ✅ **Worker Crash**: State persists, continues on restart
- ✅ **Multiple Workers**: Load balancing and failover
- ✅ **Real-time Queries**: Instant, non-blocking status

**You've tested the entire system! 🎉**

---

## 🎯 Quick Reference: Common Commands

```bash
# Subscribe
curl -X POST "http://localhost:8081/subscribe?initialBalance=100"

# Check status
curl http://localhost:8081/status/{id} | jq

# Check balance
curl http://localhost:8081/wallet/{id} | jq

# Add money
curl -X POST "http://localhost:8081/wallet/{id}/add?amount=50"

# Resume
curl -X POST http://localhost:8081/resume/{id}

# Cancel
curl -X POST http://localhost:8081/cancel/{id}

# Enable gateway down
curl -X POST http://localhost:8081/simulate/gateway-down/enable

# Disable gateway down
curl -X POST http://localhost:8081/simulate/gateway-down/disable
```

---

## 🔍 Where to Look for Confirmation

### **1. Worker Logs (Terminal 2)**
- Workflow execution logs
- Activity execution logs
- Transaction IDs
- Payment results
- State transitions

### **2. API Responses**
- Subscription IDs
- Current state
- Balance information
- Billing cycle numbers

### **3. Temporal UI (http://localhost:8080)**
- Workflow status
- Event history
- Timer information
- Activity executions
- Signal history

### **4. Balance Changes**
- Query wallet endpoint
- See balance decrease by $10 each cycle
- Verify after adding money

---

**You now have a complete testing guide! Follow each test step-by-step and observe the magic of Temporal. Happy testing! 🚀**
