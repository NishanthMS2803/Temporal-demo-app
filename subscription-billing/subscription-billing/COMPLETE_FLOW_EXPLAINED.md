# Complete System Flow - Every Single Detail Explained

Let's understand **exactly** what happens in every scenario, line by line.

---

## 🏗️ System Setup: What's Running

Before anything happens, you need these 3 processes running:

### 1. **Temporal Server (Docker)**
```bash
docker-compose up
```
- Runs on `localhost:7233` (gRPC API)
- Runs UI on `localhost:8080` (Web UI)
- Stores all workflow history in database
- **Job**: Manages workflows, timers, signals, and queues

### 2. **Worker Process** (Terminal 1)
```bash
mvn exec:java -Dexec.mainClass="com.example.subscription.worker.WorkerApp"
```
- Separate Java process
- **Job**: Executes workflow code and activity code
- Polls ;ral for tasks to execute
- Does NOT store any data

### 3. **API Process** (Terminal 2)
```bash
mvn spring-boot:run
```
- Spring Boot app on port 8081
- **Job**: Receives user requests, stores wallet data, sends commands to Temporal
- This is where wallet balances are stored

---

## 📊 Architecture Overview

```
┌─────────────────┐
│     USER        │
└────────┬────────┘
         │ HTTP
         ▼
┌─────────────────────────────┐
│    API (Spring Boot)        │  ← Stores wallet balances
│    Port 8081                │  ← Receives user commands
│    UserWallet (in memory)   │
└────────┬────────────────────┘
         │ Temporal Client
         ▼
┌─────────────────────────────┐
│    TEMPORAL SERVER          │  ← Orchestrates everything
│    Port 7233                │  ← Stores workflow state
│    (Docker)                 │  ← Manages timers, signals
└────────┬────────────────────┘
         │ Poll for tasks
         ▼
┌─────────────────────────────┐
│    WORKER                   │  ← Executes workflow logic
│    (Separate JVM)           │  ← Executes activities
│    SubscriptionWorkflowImpl │  ← Calls API for wallet ops
│    PaymentActivityImpl      │
└─────────────────────────────┘
```

---

## 🎬 SCENARIO 1: User Subscribes (Complete Flow)

Let's trace **every single step** when a user subscribes.

### **Step 1: User Makes HTTP Request**

```bash
curl -X POST "http://localhost:8081/subscribe?initialBalance=100"
```

**What happens**: Browser/curl sends HTTP POST to API server.

---

### **Step 2: API Receives Request**

**File**: `SubscriptionController.java`
**Method**: `subscribe(double initialBalance)`

```java
@PostMapping("/subscribe")
public Map<String, String> subscribe(@RequestParam(required = false, defaultValue = "100.0") double initialBalance) {
    // Line 27: Generate unique subscription ID
    String subscriptionId = "sub-" + System.currentTimeMillis();
    // Result: subscriptionId = "sub-1737451234567"

    // Line 30: ADD MONEY TO WALLET (IMPORTANT!)
    wallet.addBalance(subscriptionId, initialBalance);
    // This stores balance in API's memory
    // UserWallet.balances.put("sub-1737451234567", 100.0)
```

**What's happening here:**
- `subscriptionId` is created (e.g., "sub-1737451234567")
- `wallet.addBalance()` is called
- This STORES $100 in the API's memory
- The UserWallet has a map: `{"sub-1737451234567": 100.0}`

**CRITICAL**: The wallet data lives in the **API process**, not the worker!

---

### **Step 3: Start the Workflow**

Still in `SubscriptionController.subscribe()`:

```java
    // Line 32-39: Create workflow stub
    SubscriptionWorkflow workflow =
            client.newWorkflowStub(
                    SubscriptionWorkflow.class,
                    WorkflowOptions.newBuilder()
                            .setWorkflowId(subscriptionId)
                            .setTaskQueue("SUBSCRIPTION_TASK_QUEUE")
                            .build()
            );
```

**What's a "stub"?**
- It's a proxy object
- When you call methods on it, it sends commands to Temporal
- It's NOT the actual workflow implementation

```java
    // Line 41: START THE WORKFLOW (ASYNC!)
    WorkflowClient.start(workflow::start, subscriptionId);
```

**What happens:**
1. API sends command to Temporal Server: "Start workflow with ID sub-1737451234567"
2. Temporal Server adds task to queue: "SUBSCRIPTION_TASK_QUEUE"
3. API continues immediately (doesn't wait for workflow to finish)
4. Returns response to user

```java
    // Line 43-48: Build response
    Map<String, String> response = new HashMap<>();
    response.put("subscriptionId", subscriptionId);
    response.put("initialBalance", String.valueOf(initialBalance));
    response.put("subscriptionPrice", String.valueOf(wallet.getSubscriptionPrice()));
    response.put("status", "STARTED");

    return response;
```

**User receives:**
```json
{
  "subscriptionId": "sub-1737451234567",
  "initialBalance": "100.0",
  "subscriptionPrice": "10.0",
  "status": "STARTED"
}
```

**At this point:**
- API has finished processing the request
- Wallet has $100 stored in API's memory
- Workflow task is in Temporal's queue, waiting for a worker

---

### **Step 4: Worker Picks Up Task**

Remember, the worker is constantly polling Temporal:

```java
// WorkerApp.java - This runs in a loop
while (true) {
    // Poll Temporal: "Any tasks for me?"
    Task task = temporal.pollTask("SUBSCRIPTION_TASK_QUEUE");

    if (task != null) {
        // Found a task! Execute it.
        executeTask(task);
    }
}
```

**What happens:**
1. Worker polls Temporal: "Any workflow tasks for SUBSCRIPTION_TASK_QUEUE?"
2. Temporal: "Yes! Start workflow sub-1737451234567"
3. Worker: "OK, I'll execute SubscriptionWorkflowImpl.start()"

---

### **Step 5: Workflow Starts Executing**

**File**: `SubscriptionWorkflowImpl.java`
**Method**: `start(String subscriptionId)`

```java
@Override
public void start(String subscriptionId) {
    // Line 31-32: Initialize
    this.subscriptionId = subscriptionId;  // Save it
    currentState = "ACTIVE";               // Set initial state

    // Line 34-35: Log
    Workflow.getLogger(this.getClass())
            .info("Subscription started: {}", subscriptionId);
    // OUTPUT: "Subscription started: sub-1737451234567"
```

**Important:**
- This code runs in the **worker process**
- Variables like `subscriptionId`, `currentState` are workflow variables
- They're stored by Temporal (not in worker's memory)

---

### **Step 6: Enter the Main Loop**

```java
    // Line 37: Start infinite loop
    while (!cancelled) {

        // Line 38-40: Increment cycle
        billingCycle++;  // First time: billingCycle = 1
        currentRetryAttempts = 0;
        boolean success = false;

        // Line 42-43: Log
        Workflow.getLogger(this.getClass())
                .info("Starting billing cycle {} for subscription {}",
                      billingCycle, subscriptionId);
        // OUTPUT: "Starting billing cycle 1 for subscription sub-1737451234567"
```

**What's happening:**
- `while (!cancelled)` means: "Loop forever until cancelled"
- `billingCycle++` increments the cycle counter
- This loop represents the **entire subscription lifetime**

---

### **Step 7: Try to Charge (First Attempt)**

```java
        // Line 45: Inner retry loop
        while (currentRetryAttempts < 3 && !cancelled) {
            try {
                // Line 46-47: Set state, call activity
                currentState = "PROCESSING_PAYMENT";
                paymentActivity.charge(subscriptionId);
```

**CRITICAL MOMENT**: `paymentActivity.charge(subscriptionId)` is called.

**What happens:**
1. Workflow says: "I need to execute an activity"
2. Temporal Server creates an activity task
3. Worker picks up the activity task
4. Worker executes `PaymentActivityImpl.charge()`

**Let's go into the activity...**

---

### **Step 8: Activity Executes - Check Balance**

**File**: `PaymentActivityImpl.java`
**Method**: `charge(String subscriptionId)`

```java
@Override
public void charge(String subscriptionId) {
    // Line 27-30: Setup
    callCount++;
    String transactionId = UUID.randomUUID().toString().substring(0, 8);
    // transactionId = "a1b2c3d4"

    logger.info("[TXN:{}] Starting payment for subscription: {} (attempt #{})",
                transactionId, subscriptionId, callCount);
    // OUTPUT: "[TXN:a1b2c3d4] Starting payment for subscription: sub-1737451234567 (attempt #1)"
```

```java
    // Line 45-57: STEP 1 - CHECK BALANCE VIA API
    logger.info("[TXN:{}] Checking balance for subscription {}...",
                transactionId, subscriptionId);

    // Make HTTP GET request to API
    URL checkUrl = new URL(API_URL + "/wallet/" + subscriptionId);
    // URL: http://localhost:8081/wallet/sub-1737451234567

    HttpURLConnection checkConn = (HttpURLConnection) checkUrl.openConnection();
    checkConn.setRequestMethod("GET");

    int checkResponseCode = checkConn.getResponseCode();
    // Response code: 200 (OK)
```

**What just happened:**
- Activity makes **HTTP GET** request to API
- URL: `http://localhost:8081/wallet/sub-1737451234567`
- This calls `SubscriptionController.getBalance()`

**On the API side** (`SubscriptionController.getBalance()`):

```java
@GetMapping("/wallet/{id}")
public Map<String, Object> getBalance(@PathVariable String id) {
    // id = "sub-1737451234567"

    double balance = wallet.getBalance(id);
    // Looks up: balances.get("sub-1737451234567")
    // Returns: 100.0

    Map<String, Object> response = new HashMap<>();
    response.put("balance", balance);           // 100.0
    response.put("subscriptionPrice", 10.0);    // 10.0
    response.put("canSubscribe", true);         // true (100 >= 10)

    return response;  // Sends back as JSON
}
```

**API returns:**
```json
{
  "balance": 100.0,
  "subscriptionPrice": 10.0,
  "canSubscribe": true
}
```

**Back in the activity:**

```java
    // Line 59-70: Parse response
    BufferedReader in = new BufferedReader(new InputStreamReader(checkConn.getInputStream()));
    String inputLine;
    StringBuilder response = new StringBuilder();
    while ((inputLine = in.readLine()) != null) {
        response.append(inputLine);
    }
    in.close();
    // response = '{"balance":100.0,"subscriptionPrice":10.0,...}'

    String jsonResponse = response.toString();
    double balance = parseBalance(jsonResponse);  // Extract 100.0
    double price = parsePrice(jsonResponse);      // Extract 10.0

    logger.info("[TXN:{}] Balance: ${}, Required: ${}",
                transactionId, balance, price);
    // OUTPUT: "[TXN:a1b2c3d4] Balance: $100.0, Required: $10.0"
```

```java
    // Line 74-78: Check if sufficient
    if (balance < price) {
        // This would throw if balance was too low
        throw new RuntimeException("Insufficient funds...");
    }
    // Balance is OK, continue...
```

---

### **Step 9: Activity Executes - Charge**

```java
    // Line 80-81: STEP 2 - CHARGE VIA API
    logger.info("[TXN:{}] Sufficient balance found. Processing charge...",
                transactionId);

    // Make HTTP POST request to API
    URL chargeUrl = new URL(API_URL + "/wallet/" + subscriptionId + "/charge");
    // URL: http://localhost:8081/wallet/sub-1737451234567/charge

    HttpURLConnection chargeConn = (HttpURLConnection) chargeUrl.openConnection();
    chargeConn.setRequestMethod("POST");
    chargeConn.setDoOutput(true);

    int chargeResponseCode = chargeConn.getResponseCode();
    // Response code: 200 (OK)
```

**What just happened:**
- Activity makes **HTTP POST** to API
- URL: `http://localhost:8081/wallet/sub-1737451234567/charge`
- This calls `SubscriptionController.chargeWallet()`

**On the API side** (`SubscriptionController.chargeWallet()`):

```java
@PostMapping("/wallet/{id}/charge")
public Map<String, Object> chargeWallet(@PathVariable String id) {
    // id = "sub-1737451234567"

    double price = wallet.getSubscriptionPrice();  // 10.0
    boolean success = wallet.charge(id, price);
    // This calls: balances.put("sub-1737451234567", 100.0 - 10.0)
    // New balance: 90.0

    if (!success) {
        throw new RuntimeException("Insufficient funds");
    }

    double newBalance = wallet.getBalance(id);  // 90.0

    Map<String, Object> response = new HashMap<>();
    response.put("amountCharged", price);      // 10.0
    response.put("newBalance", newBalance);    // 90.0
    response.put("message", "Payment processed successfully");

    return response;  // Sends back as JSON
}
```

**What happened in API's memory:**
- Before: `balances = {"sub-1737451234567": 100.0}`
- After: `balances = {"sub-1737451234567": 90.0}`

**Back in activity:**

```java
    // Line 95-104: Parse response
    BufferedReader chargeIn = new BufferedReader(
        new InputStreamReader(chargeConn.getInputStream()));
    StringBuilder chargeResponse = new StringBuilder();
    while ((inputLine = chargeIn.readLine()) != null) {
        chargeResponse.append(inputLine);
    }
    chargeIn.close();

    double newBalance = parseBalance(chargeResponse.toString());  // 90.0

    logger.info("[TXN:{}] ✓ Payment SUCCESS - Charged ${}. New balance: ${}",
               transactionId, price, newBalance);
    // OUTPUT: "[TXN:a1b2c3d4] ✓ Payment SUCCESS - Charged $10.0. New balance: $90.0"
```

**Activity completes successfully!**

---

### **Step 10: Workflow Continues After Activity**

**Back in** `SubscriptionWorkflowImpl.start()`:

```java
                paymentActivity.charge(subscriptionId);
                // ↑ This just finished successfully

                // Line 49-53: Payment succeeded!
                success = true;
                lastPaymentStatus = "SUCCESS";
                totalPaymentsProcessed++;  // Now = 1
                currentState = "ACTIVE";

                Workflow.getLogger(this.getClass())
                        .info("Payment successful for cycle {} (total: {})",
                              billingCycle, totalPaymentsProcessed);
                // OUTPUT: "Payment successful for cycle 1 (total: 1)"

                // Line 55: Exit retry loop
                break;
            } catch (Exception e) {
                // Not executed (payment succeeded)
            }
        }
```

---

### **Step 11: Wait for Next Cycle**

```java
        // Line 69-85: Check if payment failed (it didn't)
        if (!success && !cancelled) {
            // Skip this - payment succeeded
        }

        // Line 87-91: Wait for next billing cycle
        if (!cancelled && success) {
            Workflow.getLogger(this.getClass())
                    .info("Waiting 1 minute until next billing cycle...");
            // OUTPUT: "Waiting 1 minute until next billing cycle..."

            Workflow.sleep(Duration.ofMinutes(1));
            // ↑ WORKFLOW SLEEPS FOR 1 MINUTE
        }
    }
    // Loop back to start of while loop
}
```

**CRITICAL**: What happens during `Workflow.sleep(Duration.ofMinutes(1))`?

1. **Worker doesn't block**: The worker thread is freed up
2. **Temporal stores timer**: Temporal remembers "wake up this workflow in 1 minute"
3. **Worker can process other workflows**: During the 1 minute wait
4. **No resources used**: Workflow is "paused" but uses zero CPU/memory
5. **After 1 minute**: Temporal puts a new task in the queue
6. **Worker picks up task**: Continues execution after the sleep

**This is the magic of Temporal!** You can sleep for days/months without holding resources.

---

### **Step 12: After 1 Minute - Second Cycle**

After 1 minute, workflow wakes up:

```java
while (!cancelled) {
    billingCycle++;  // Now = 2
    currentRetryAttempts = 0;
    boolean success = false;

    Workflow.getLogger(this.getClass())
            .info("Starting billing cycle {} for subscription {}",
                  billingCycle, subscriptionId);
    // OUTPUT: "Starting billing cycle 2 for subscription sub-1737451234567"

    // Tries to charge again...
    paymentActivity.charge(subscriptionId);
    // This time balance is 90.0
    // After charge: 80.0

    // ... success ...

    // Sleep for another minute
    Workflow.sleep(Duration.ofMinutes(1));
}
```

**This continues forever** (or until cancelled/paused).

---

## 💰 SCENARIO 2: Insufficient Balance (Complete Flow)

Now let's see what happens when money runs out.

### **Setup: Subscribe with Low Balance**

```bash
curl -X POST "http://localhost:8081/subscribe?initialBalance=15"
```

**What happens:**
- API stores: `balances = {"sub-1737451234568": 15.0}`
- Workflow starts
- First cycle charges $10 → Balance becomes $5
- Workflow waits 1 minute
- Second cycle tries to charge $10 but balance is only $5!

### **Step 1: Activity Tries to Charge (Insufficient Funds)**

```java
// PaymentActivityImpl.charge()

// Check balance
URL checkUrl = new URL(API_URL + "/wallet/" + subscriptionId);
// ... HTTP GET ...
double balance = parseBalance(response);  // 5.0
double price = parsePrice(response);      // 10.0

logger.info("[TXN:{}] Balance: ${}, Required: ${}",
            transactionId, balance, price);
// OUTPUT: "[TXN:b2c3d4e5] Balance: $5.0, Required: $10.0"

// Line 74-78: Check if sufficient
if (balance < price) {
    logger.error("[TXN:{}] Insufficient funds - Balance: ${}, Required: ${}",
                transactionId, balance, price);
    // OUTPUT: "[TXN:b2c3d4e5] Insufficient funds - Balance: $5.0, Required: $10.0"

    throw new RuntimeException("Insufficient funds - Balance: $5.0, Required: $10.0");
    // ↑ THROWS EXCEPTION!
}
```

**Activity fails with exception!**

---

### **Step 2: Workflow Catches Exception (First Retry)**

**Back in** `SubscriptionWorkflowImpl.start()`:

```java
while (currentRetryAttempts < 3 && !cancelled) {
    try {
        currentState = "PROCESSING_PAYMENT";
        paymentActivity.charge(subscriptionId);
        // ↑ This threw exception

    } catch (Exception e) {
        // Line 56-61: Exception caught!
        currentRetryAttempts++;  // Now = 1
        lastPaymentStatus = "FAILED - " + e.getMessage();
        // lastPaymentStatus = "FAILED - Insufficient funds - Balance: $5.0, Required: $10.0"

        currentState = "RETRYING";

        Workflow.getLogger(this.getClass())
                .warn("Payment failed (attempt {}/3): {}",
                      currentRetryAttempts, e.getMessage());
        // OUTPUT: "Payment failed (attempt 1/3): Insufficient funds - Balance: $5.0, Required: $10.0"

        // Line 63-65: Wait before retry
        if (currentRetryAttempts < 3) {
            Workflow.sleep(Duration.ofSeconds(5));
            // ↑ Sleep 5 seconds before retrying
        }
    }
}
```

**What happens:**
1. Exception is caught
2. Retry counter incremented (1/3)
3. State changed to "RETRYING"
4. Sleep 5 seconds
5. Loop continues (tries again)

---

### **Step 3: Second Retry Attempt**

After 5 seconds:

```java
// Loop continues
while (currentRetryAttempts < 3 && !cancelled) {
    // currentRetryAttempts = 1, so continue...

    try {
        paymentActivity.charge(subscriptionId);
        // ↑ Tries again

        // Activity checks balance: still $5.0
        // Throws same exception again!

    } catch (Exception e) {
        currentRetryAttempts++;  // Now = 2

        Workflow.getLogger(this.getClass())
                .warn("Payment failed (attempt {}/3): {}",
                      currentRetryAttempts, e.getMessage());
        // OUTPUT: "Payment failed (attempt 2/3): Insufficient funds..."

        Workflow.sleep(Duration.ofSeconds(5));
        // Wait 5 seconds again
    }
}
```

---

### **Step 4: Third Retry Attempt**

After another 5 seconds:

```java
while (currentRetryAttempts < 3 && !cancelled) {
    // currentRetryAttempts = 2, still < 3, continue...

    try {
        paymentActivity.charge(subscriptionId);
        // ↑ Third attempt

        // Still fails!

    } catch (Exception e) {
        currentRetryAttempts++;  // Now = 3

        Workflow.getLogger(this.getClass())
                .warn("Payment failed (attempt {}/3): {}",
                      currentRetryAttempts, e.getMessage());
        // OUTPUT: "Payment failed (attempt 3/3): Insufficient funds..."

        if (currentRetryAttempts < 3) {
            // 3 is NOT < 3, so skip sleep
        }
    }
}
// Loop exits because currentRetryAttempts = 3
```

**All 3 retries failed!**

---

### **Step 5: Workflow Pauses**

```java
// Line 69-73: Check if all retries failed
if (!success && !cancelled) {
    // success = false, cancelled = false, so enter this block

    paused = true;
    currentState = "PAUSED";

    Workflow.getLogger(this.getClass())
            .warn("Subscription PAUSED after {} failed attempts in cycle {}",
                  currentRetryAttempts, billingCycle);
    // OUTPUT: "Subscription PAUSED after 3 failed attempts in cycle 2"

    // Line 76: WAIT FOR SIGNAL
    Workflow.await(() -> !paused || cancelled);
    // ↑ THIS IS CRITICAL!
}
```

**What is `Workflow.await(() -> !paused || cancelled)`?**

This says: "Block this workflow until either":
- `paused` becomes `false` (someone calls resume())
- `cancelled` becomes `true` (someone calls cancel())

**How is this different from sleep?**
- `Workflow.sleep(duration)` waits for a specific time
- `Workflow.await(condition)` waits for a condition to become true
- Could wait forever (until external signal changes the condition)

**During this wait:**
- Workflow is "blocked" in Temporal
- Worker doesn't hold any thread
- Workflow state is persisted
- Can check status anytime (query still works)

**The workflow is now PAUSED and waiting for a signal.**

---

### **Step 6: User Checks Status**

```bash
curl http://localhost:8081/status/sub-1737451234568
```

**What happens:**

```java
// SubscriptionController.getStatus()
@GetMapping("/status/{id}")
public Map<String, Object> getStatus(@PathVariable String id) {
    // id = "sub-1737451234568"

    // Create workflow stub
    SubscriptionWorkflow workflow = client.newWorkflowStub(
        SubscriptionWorkflow.class, id);

    // Query the workflow (non-blocking!)
    SubscriptionStatus status = workflow.getStatus();
    // ↑ This calls the @QueryMethod in the workflow
```

**In the workflow:**

```java
// SubscriptionWorkflowImpl.getStatus()
@Override
public SubscriptionStatus getStatus() {
    return new SubscriptionStatus(
            subscriptionId,        // "sub-1737451234568"
            currentState,          // "PAUSED"
            billingCycle,          // 2
            currentRetryAttempts,  // 3
            0.0,                   // (placeholder)
            lastPaymentStatus,     // "FAILED - Insufficient funds..."
            totalPaymentsProcessed // 1
    );
}
```

**API returns:**
```json
{
  "subscriptionId": "sub-1737451234568",
  "state": "PAUSED",
  "billingCycle": 2,
  "retryAttempts": 3,
  "lastPaymentStatus": "FAILED - Insufficient funds - Balance: $5.0, Required: $10.0",
  "totalPaymentsProcessed": 1,
  "currentBalance": 5.0
}
```

**User sees: Subscription is PAUSED, needs more money!**

---

### **Step 7: User Adds Balance**

```bash
curl -X POST "http://localhost:8081/wallet/sub-1737451234568/add?amount=50"
```

**What happens:**

```java
// SubscriptionController.addBalance()
@PostMapping("/wallet/{id}/add")
public Map<String, Object> addBalance(@PathVariable String id,
                                       @RequestParam double amount) {
    // id = "sub-1737451234568"
    // amount = 50.0

    wallet.addBalance(id, amount);
    // balances.put("sub-1737451234568", 5.0 + 50.0)
    // New balance: 55.0

    double newBalance = wallet.getBalance(id);  // 55.0

    Map<String, Object> response = new HashMap<>();
    response.put("amountAdded", amount);        // 50.0
    response.put("newBalance", newBalance);     // 55.0

    return response;
}
```

**In API's memory:**
- Before: `balances = {"sub-1737451234568": 5.0}`
- After: `balances = {"sub-1737451234568": 55.0}`

**But workflow is still PAUSED!** We added money, but workflow doesn't know yet.

---

### **Step 8: User Sends Resume Signal**

```bash
curl -X POST http://localhost:8081/resume/sub-1737451234568
```

**What happens:**

```java
// SubscriptionController.resume()
@PostMapping("/resume/{id}")
public Map<String, String> resume(@PathVariable String id) {
    // id = "sub-1737451234568"

    // Get workflow stub
    SubscriptionWorkflow workflow = client.newWorkflowStub(
        SubscriptionWorkflow.class, id);

    // Send signal to workflow
    workflow.resume();
    // ↑ This sends a signal to the workflow
```

**What happens in Temporal:**
1. API sends signal command to Temporal
2. Temporal adds signal to workflow's event history
3. Temporal wakes up the workflow
4. Worker picks up the workflow task

**In the workflow:**

```java
// SubscriptionWorkflowImpl.resume()
@SignalMethod
@Override
public void resume() {
    paused = false;  // Change from true to false!
    currentState = "ACTIVE";

    Workflow.getLogger(this.getClass())
            .info("Resume signal received");
    // OUTPUT: "Resume signal received"
}
```

**What just happened:**
- Signal handler executed
- `paused` changed from `true` to `false`
- Now the condition in `Workflow.await(() -> !paused || cancelled)` is true!

---

### **Step 9: Workflow Unblocks**

**Back where workflow was waiting:**

```java
// Line 76: Was blocked here
Workflow.await(() -> !paused || cancelled);
// !paused = !false = true
// Condition is true! Unblock!

// Line 78-79: Check if cancelled
if (cancelled) {
    break;  // Not cancelled, skip this
}

// Line 82-84: Reset and log
currentRetryAttempts = 0;  // Reset to 0!
Workflow.getLogger(this.getClass())
        .info("Subscription RESUMED, continuing from cycle {}", billingCycle);
// OUTPUT: "Subscription RESUMED, continuing from cycle 2"
```

---

### **Step 10: Workflow Continues**

```java
// Line 87-91: Continue with next cycle
if (!cancelled && success) {
    // success is false (payment failed), so skip
}
// Don't sleep, go directly to next iteration

// Loop back to top
while (!cancelled) {
    billingCycle++;  // Still 2 (doesn't increment, tries same cycle again)
    // Actually, it will continue from where it left off

    // ... Try to charge again ...
    paymentActivity.charge(subscriptionId);
    // This time balance is $55, charge succeeds!
    // New balance: $45

    // ... SUCCESS! ...

    // Sleep for next cycle
    Workflow.sleep(Duration.ofMinutes(1));
}
```

**Workflow successfully resumed and continues billing!**

---

## 💥 SCENARIO 3: Worker Crash (Complete Flow)

This is the **most impressive** Temporal feature. Let's see exactly what happens.

### **Step 1: Subscribe**

```bash
curl -X POST "http://localhost:8081/subscribe?initialBalance=100"
```

Workflow starts, first payment succeeds, workflow sleeps for 1 minute...

---

### **Step 2: Workflow is Sleeping**

```java
// In SubscriptionWorkflowImpl.start()

Workflow.getLogger(this.getClass())
        .info("Waiting 1 minute until next billing cycle...");

Workflow.sleep(Duration.ofMinutes(1));
// ↑ Workflow enters sleep state
```

**What happens in Temporal:**

1. Worker tells Temporal: "This workflow wants to sleep for 1 minute"
2. Temporal records in database:
   ```
   Workflow ID: sub-1737451234569
   State: SLEEPING
   Wake up at: 2024-01-21 14:01:00
   Current variables:
     - subscriptionId = "sub-1737451234569"
     - billingCycle = 1
     - totalPaymentsProcessed = 1
     - currentState = "ACTIVE"
     - ... all other variables ...
   ```
3. Worker releases the workflow (no longer in memory)
4. Worker is free to process other workflows

**At this moment:**
- Workflow state is in Temporal's database
- Worker has nothing in memory about this workflow
- Timer is set in Temporal for 1 minute

---

### **Step 3: Kill the Worker**

```bash
# In the worker terminal
Ctrl+C
```

**What happens:**

```
^C
Worker process terminated
```

- Worker process exits
- All memory cleared
- No workflows in memory
- But Temporal still has all the data!

---

### **Step 4: Check Status (Worker is Dead!)**

```bash
curl http://localhost:8081/status/sub-1737451234569
```

**What happens:**

API still works! API tries to query workflow:

```java
// SubscriptionController.getStatus()
SubscriptionWorkflow workflow = client.newWorkflowStub(
    SubscriptionWorkflow.class, id);

SubscriptionStatus status = workflow.getStatus();
// ↑ This sends query to Temporal
```

**But worker is dead! How does query work?**

**Temporal responds from its database:**
```json
{
  "subscriptionId": "sub-1737451234569",
  "state": "ACTIVE",
  "billingCycle": 1,
  "retryAttempts": 0,
  "totalPaymentsProcessed": 1,
  "currentBalance": 90.0
}
```

**All data is preserved!** Temporal has the complete workflow state.

---

### **Step 5: Timer Expires (Worker Still Dead)**

After 1 minute, Temporal's timer expires:

**In Temporal:**
1. Timer fires: "Workflow sub-1737451234569 should wake up now"
2. Temporal creates a new workflow task
3. Adds task to queue: "SUBSCRIPTION_TASK_QUEUE"
4. Task description: "Continue workflow after sleep"
5. Waits for a worker to pick it up...

**But no worker is running!**
- Task sits in queue
- Workflow is "ready to run" but no worker available
- Temporal keeps task in queue indefinitely

---

### **Step 6: Restart Worker**

```bash
mvn exec:java -Dexec.mainClass="com.example.subscription.worker.WorkerApp"
```

**What happens:**

```java
// WorkerApp.main()
WorkflowServiceStubs service = WorkflowServiceStubs.newInstance();
WorkflowClient client = WorkflowClient.newInstance(service);
WorkerFactory factory = WorkerFactory.newInstance(client);
Worker worker = factory.newWorker("SUBSCRIPTION_TASK_QUEUE");

worker.registerWorkflowImplementationTypes(SubscriptionWorkflowImpl.class);
worker.registerActivitiesImplementations(new PaymentActivityImpl());

factory.start();  // Start polling for tasks

System.out.println("✓ Worker started and listening on SUBSCRIPTION_TASK_QUEUE...");
```

**Worker starts polling:**
```java
while (true) {
    Task task = temporal.pollTask("SUBSCRIPTION_TASK_QUEUE");
    // Finds the waiting task for sub-1737451234569!

    if (task != null) {
        executeTask(task);
    }
}
```

---

### **Step 7: Worker Resumes Workflow**

**Temporal tells worker:**
"Execute workflow sub-1737451234569, continue after sleep at line 90"

**Worker loads workflow state from Temporal:**
```
Variables:
  - subscriptionId = "sub-1737451234569"
  - billingCycle = 1
  - totalPaymentsProcessed = 1
  - currentState = "ACTIVE"
  - paused = false
  - cancelled = false
  - ... all variables restored ...

Execution point: After Workflow.sleep(Duration.ofMinutes(1))
```

**Workflow continues execution:**

```java
// Continues from after the sleep!
Workflow.sleep(Duration.ofMinutes(1));
// ↑ Was here when crashed

// Continue to next line
}  // End of if block

// Loop back to top
while (!cancelled) {
    billingCycle++;  // Now = 2

    Workflow.getLogger(this.getClass())
            .info("Starting billing cycle {} for subscription {}",
                  billingCycle, subscriptionId);
    // OUTPUT: "Starting billing cycle 2 for subscription sub-1737451234569"

    // Try to charge again
    paymentActivity.charge(subscriptionId);
    // Success! Charges another $10

    // ... continues normally ...
}
```

**Workflow continued as if nothing happened!**

**What's amazing:**
- Worker crashed during sleep
- Was down for who knows how long
- Restarted and immediately picked up where it left off
- No data loss
- No duplicate charges
- No missed billing cycles

**This is Temporal's durability in action!**

---

## 🔄 SCENARIO 4: Cancel Signal

### **User Sends Cancel**

```bash
curl -X POST http://localhost:8081/cancel/sub-1737451234569
```

**What happens:**

```java
// SubscriptionController.cancel()
@PostMapping("/cancel/{id}")
public Map<String, String> cancel(@PathVariable String id) {
    SubscriptionWorkflow workflow = client.newWorkflowStub(
        SubscriptionWorkflow.class, id);

    workflow.cancel();  // Send cancel signal
```

**In Temporal:**
1. API sends cancel signal
2. Temporal adds to workflow event history
3. If workflow is running, signal is delivered immediately
4. If workflow is sleeping/waiting, signal is queued
5. Next time workflow executes, signal handler runs

---

**In the workflow:**

```java
// SubscriptionWorkflowImpl.cancel()
@SignalMethod
@Override
public void cancel() {
    cancelled = true;  // Set flag to true
    currentState = "CANCELLED";

    Workflow.getLogger(this.getClass())
            .info("Cancel signal received");
    // OUTPUT: "Cancel signal received"
}
```

---

**Back in main workflow loop:**

```java
// Next time workflow executes any line, checks this:
while (!cancelled) {
    // cancelled = true, so condition is false
    // Exit loop!
}

// After loop
currentState = "CANCELLED";
Workflow.getLogger(this.getClass())
        .info("Subscription cancelled after {} cycles and {} successful payments",
              billingCycle, totalPaymentsProcessed);
// OUTPUT: "Subscription cancelled after 2 cycles and 2 successful payments"

// Method ends
}  // end of start() method
```

**Workflow terminates!**

- All cleanup completed
- Final state saved
- Can query final status
- Workflow marked as "COMPLETED" in Temporal

---

## 🗺️ Complete Picture: Data Flow

Let me show you where everything lives:

### **API Process (Spring Boot) - Port 8081**
```
UserWallet (in memory)
  ├── balances = {
  │     "sub-123": 90.0,
  │     "sub-456": 50.0
  │   }
  └── SUBSCRIPTION_PRICE = 10.0

SubscriptionController
  ├── GET /wallet/{id} → Returns balance
  ├── POST /wallet/{id}/charge → Deducts $10
  ├── POST /subscribe → Adds balance, starts workflow
  ├── GET /status/{id} → Queries workflow
  └── POST /resume/{id} → Sends signal to workflow
```

### **Temporal Server (Docker) - Port 7233**
```
Database stores:
  ├── Workflow: sub-123
  │     ├── State: ACTIVE
  │     ├── Variables: {subscriptionId, billingCycle, ...}
  │     ├── Event History: [WorkflowStarted, ActivityCompleted, TimerFired, ...]
  │     └── Pending Timers: [Wake at 14:02:00]
  │
  └── Task Queues:
        └── SUBSCRIPTION_TASK_QUEUE
              ├── Workflow tasks (execute workflow code)
              └── Activity tasks (execute activity code)
```

### **Worker Process (Standalone Java)**
```
Polls Temporal every 100ms:
  "Any tasks for SUBSCRIPTION_TASK_QUEUE?"

When task arrives:
  1. Load workflow state from Temporal
  2. Execute workflow code (SubscriptionWorkflowImpl)
  3. When needs activity:
       a. Execute activity (PaymentActivityImpl)
       b. Activity makes HTTP calls to API
  4. Save state back to Temporal
  5. Release task

Currently in memory: (nothing when idle)
```

---

## 📊 Complete Execution Timeline

Let's see everything on a timeline:

```
Time    | API                  | Temporal             | Worker               | Wallet
--------|---------------------|----------------------|----------------------|--------
00:00   | Subscribe           |                      |                      | +$100
        | POST /subscribe     |                      |                      |
        |   ↓                 |                      |                      |
00:00   | wallet.add(id,100)  |                      |                      | $100
        | Start workflow →    |                      |                      |
        |                     |                      |                      |
00:00   |                     | Create workflow      |                      |
        |                     | Add task to queue    |                      |
        |                     |   ↓                  |                      |
00:00   |                     |                      | Poll: Found task!    |
        |                     |                      | Execute workflow     |
        |                     |                      |   ↓                  |
00:00   |                     |                      | Start billing cycle 1|
        |                     |                      | Call activity →      |
        |                     |                      |   ↓                  |
00:00   |                     | Create activity task |                      |
        |                     |   ↓                  |                      |
00:00   |                     |                      | Execute activity     |
        |                     |                      | HTTP GET /wallet/id →|
        |   ← Return $100     |                      |                      | $100
        |                     |                      | HTTP POST /charge → |
00:00   | wallet.charge(id)   |                      |                      | -$10
        |   ← Return $90      |                      |                      | $90
        |                     |                      | Activity complete    |
        |                     |                      | Workflow continues   |
        |                     |                      | Sleep 1 min →        |
        |                     |                      |   ↓                  |
00:00   |                     | Set timer: wake 01:00|                      |
        |                     | Save workflow state  |                      |
        |                     |   ↓                  |                      |
00:00   |                     |                      | Task complete        |
        |                     |                      | (free to do other    |
        |                     |                      |  work)               |
        |                     |                      |                      |
... 1 minute passes ...       |                      |                      |
        |                     |                      |                      |
01:00   |                     | Timer fires!         |                      |
        |                     | Add task to queue    |                      |
        |                     |   ↓                  |                      |
01:00   |                     |                      | Poll: Found task!    |
        |                     |                      | Load workflow state  |
        |                     |                      | Continue after sleep |
        |                     |                      |   ↓                  |
01:00   |                     |                      | Start billing cycle 2|
        |                     |                      | Call activity →      |
        |                     |                      |   ↓                  |
01:00   |                     |                      | HTTP GET /wallet/id →|
        |   ← Return $90      |                      |                      | $90
        |                     |                      | HTTP POST /charge →  |
01:00   | wallet.charge(id)   |                      |                      | -$10
        |   ← Return $80      |                      |                      | $80
        |                     |                      | Activity complete    |
        |                     |                      | Sleep 1 min →        |
        |                     |                      |                      |
... continues forever ...
```

---

## 🎯 Key Concepts Summary

### **1. API Process**
- Receives user HTTP requests
- Stores wallet balances in memory
- Starts workflows via Temporal client
- Sends signals (resume, cancel)
- Queries workflow state

### **2. Temporal Server**
- Orchestrates everything
- Stores complete workflow state
- Manages task queues
- Handles durable timers
- Provides event history

### **3. Worker Process**
- Polls Temporal for tasks
- Executes workflow code
- Executes activity code
- Makes HTTP calls to API (for wallet operations)
- Stateless (all state in Temporal)

### **4. Workflow (SubscriptionWorkflowImpl)**
- Long-running business logic
- Maintains state in variables
- Survives crashes (state in Temporal)
- Can sleep for days/months
- Receives signals (resume, cancel)
- Provides queries (getStatus)

### **5. Activity (PaymentActivityImpl)**
- Calls external systems
- Makes HTTP requests to API
- Can fail and be retried
- Should be idempotent

### **6. Wallet (UserWallet)**
- Lives in API's memory
- Stores user balances
- Modified by API endpoints
- Read by activities via HTTP

---

## 💡 Critical Insights

### **Why Separate Processes?**
- **API**: User-facing, needs to be highly available
- **Worker**: Can scale independently, can crash without affecting API
- **Separation**: Better fault isolation and scalability

### **Why HTTP Between Worker and API?**
- Worker and API are separate processes
- Can't share memory directly
- HTTP calls simulate real external systems
- In production, activities call databases, payment gateways, etc.

### **Why Temporal in the Middle?**
- Manages workflow state reliably
- Provides durable timers
- Handles retries automatically
- Ensures exactly-once execution
- Survives crashes

### **What Makes This Special?**
Without Temporal, you'd need:
- Database to store state
- Cron jobs for scheduling
- Message queues for tasks
- Manual retry logic
- Distributed locks
- Complex error handling

With Temporal:
- Write code as if it never fails
- Temporal handles everything else
- Simple, readable business logic

---

This is the complete picture! Every scenario, every line of code, every data flow explained. Does this help clarify everything?
