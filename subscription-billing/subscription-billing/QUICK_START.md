# 🚀 Quick Start - Test Your Implementation NOW

## One-Line Start

```bash
cd /Users/nms/Documents/Temporal-Demo/subscription-billing/subscription-billing && ./START_EVERYTHING.sh
```

**Done!** Everything is running. Now test.

---

## Quick Test - 2 Minutes

**Terminal 1**: Already running (from script)

**Terminal 2**:
```bash
# Create subscription
curl -X POST "http://localhost:8081/subscribe?initialBalance=15"

# Watch it work
temporal workflow list
```

**Open Browser**: http://localhost:8080

**See**: Workflow running in Temporal UI

---

## What You CAN Test Locally (No Cloud Needed)

### ✅ YES - Works Perfectly

| What | How |
|------|-----|
| **All 3 workflow versions** | Run workers one at a time |
| **v1: Immediate pause** | Start WorkerApp |
| **v2: 30s grace period** | Start WorkerAppV2 |
| **v3: Escalating grace (10s→20s→30s)** | Start WorkerAppV3 |
| **Compare versions** | Switch workers, create subscriptions |
| **Auto-complete (12 cycles)** | Create $200 balance sub |
| **Auto-cancel (3min pause)** | Create $15 balance sub |
| **Resume paused workflows** | Add money, call /resume |
| **Worker crash recovery** | Kill worker, restart |
| **Multiple subscriptions** | Create 10+ at once |
| **Temporal UI exploration** | View workflows, event history |

### ❌ NO - Needs Versioning Enabled

| What | Why |
|------|-----|
| **Multiple workers running together** | Needs build ID routing |
| **Rainbow deployment (30% v2, 70% v3)** | Needs traffic splitting |
| **Live version migration** | Needs draining states |
| **Version pinning** | Needs build ID routing |
| **Rollback testing** | Needs multiple active versions |

---

## Test Versions One-by-One

### Test v1 (Immediate Pause)

```bash
# Terminal 1: Start v1 worker
pkill -f "WorkerApp"
mvn exec:java -Dexec.mainClass="com.example.subscription.worker.WorkerApp"

# Terminal 2: Create subscription
curl -X POST "http://localhost:8081/subscribe?initialBalance=15"

# Watch: Pauses immediately after 3 retries (~15 seconds)
```

### Test v2 (30-Second Grace)

```bash
# Terminal 1: Start v2 worker
pkill -f "WorkerApp"
mvn exec:java -Dexec.mainClass="com.example.subscription.worker.WorkerAppV2"

# Terminal 2: Create subscription
curl -X POST "http://localhost:8081/subscribe?initialBalance=15"

# Watch: 30-second grace period before pausing (~45 seconds)
```

### Test v3 (Escalating Grace)

```bash
# Terminal 1: Start v3 worker
pkill -f "WorkerApp"
mvn exec:java -Dexec.mainClass="com.example.subscription.worker.WorkerAppV3"

# Terminal 2: Create subscription
curl -X POST "http://localhost:8081/subscribe?initialBalance=15"

# Watch: 10s → 20s → 30s grace periods (~90 seconds)
```

---

## Complete Testing Guide

📖 **See**: `LOCAL_TESTING_GUIDE_NO_VERSIONING.md`

8 comprehensive test scenarios:
1. Version 1.0 - Immediate Pause
2. Version 2.0 - Single Grace Period
3. Version 3.0 - Escalating Grace Periods
4. Compare All Versions
5. Automatic Completion (12 Cycles)
6. Automatic Cancellation (3-Min Timeout)
7. Worker Crash Recovery
8. Multiple Subscriptions Stress Test

---

## Key Insights

### Version Comparison

```
Timeline After 3 Failed Retries:

v1: ━━━━━━━━━━━━━━━┫ PAUSED (0s grace)
    15 seconds

v2: ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┫ PAUSED (30s grace)
    45 seconds

v3: ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┫ PAUSED
    90 seconds (10s + 20s + 30s grace)
```

### Customer Impact

- **v1**: Harsh (service cut immediately)
- **v2**: Balanced (one chance to fix)
- **v3**: Generous (three chances, escalating urgency)

---

## Bottom Line

### ✅ What's Complete

Your **entire implementation** is done:
- 3 workflow versions ✅
- Automatic completion ✅
- Grace period logic ✅
- All API endpoints ✅
- Worker versioning code ✅

### ⚠️ What's Missing

Only **one thing**: Temporal server config to enable multi-version routing

This is **not your code** - it's a server setting.

### 🎯 What You Can Do

**TODAY**: Test everything in the local testing guide
**LATER**: Enable versioning and test multi-version scenarios

---

## Logs & Monitoring

```bash
# Worker logs
tail -f /tmp/worker.log

# API logs
tail -f /tmp/api.log

# Temporal UI
open http://localhost:8080

# List workflows
temporal workflow list

# Check status
curl http://localhost:8081/status/{subscriptionId} | jq '.'
```

---

## Stop Everything

```bash
pkill -f "WorkerApp"
pkill -f "spring-boot:run"
docker-compose down
```

---

**Ready to test? Start with**: `LOCAL_TESTING_GUIDE_NO_VERSIONING.md` 🚀
