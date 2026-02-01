#!/bin/bash

# Complete Setup Script for Subscription Billing Demo
# This starts everything you need for testing

set -e

echo "=================================="
echo "  Subscription Billing Demo Setup"
echo "=================================="
echo ""

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Check if Temporal is running
echo "1. Checking Temporal Server..."
if ! docker ps | grep -q "temporal"; then
    echo -e "${YELLOW}Starting Temporal Server...${NC}"
    cd /Users/nms/Documents/Temporal-Demo
    docker-compose up -d
    echo "Waiting 30 seconds for Temporal to be ready..."
    sleep 30
    echo -e "${GREEN}✓ Temporal Server started${NC}"
else
    echo -e "${GREEN}✓ Temporal Server already running${NC}"
fi

echo ""
echo "2. Starting API Server..."
cd /Users/nms/Documents/Temporal-Demo/subscription-billing/subscription-billing

# Kill existing API if running
pkill -f "spring-boot:run" 2>/dev/null || true
sleep 2

# Start API in background
mvn spring-boot:run > /tmp/api.log 2>&1 &
API_PID=$!
echo "API PID: $API_PID"
echo "Waiting for API to start..."
sleep 15

# Check API health
if curl -s http://localhost:8081/actuator/health | grep -q "UP"; then
    echo -e "${GREEN}✓ API Server started successfully${NC}"
else
    echo -e "${RED}✗ API Server failed to start${NC}"
    exit 1
fi

echo ""
echo "3. Starting Worker (No Versioning Mode)..."

# Kill existing workers
pkill -f "WorkerApp" 2>/dev/null || true
sleep 2

# Start worker in background
mvn exec:java -Dexec.mainClass="com.example.subscription.worker.WorkerAppNoVersion" > /tmp/worker.log 2>&1 &
WORKER_PID=$!
echo "Worker PID: $WORKER_PID"
echo "Waiting for worker to start..."
sleep 10

echo -e "${GREEN}✓ Worker started successfully${NC}"

echo ""
echo "=================================="
echo -e "${GREEN}  ✓ ALL SERVICES RUNNING${NC}"
echo "=================================="
echo ""
echo "Services:"
echo "  • Temporal Server:  http://localhost:7233"
echo "  • Temporal UI:      http://localhost:8080"
echo "  • API:              http://localhost:8081"
echo "  • Worker:           Running (PID: $WORKER_PID)"
echo ""
echo "Logs:"
echo "  • API:    tail -f /tmp/api.log"
echo "  • Worker: tail -f /tmp/worker.log"
echo ""
echo "Quick Test:"
echo "  curl -X POST 'http://localhost:8081/subscribe?initialBalance=100'"
echo ""
echo "View workflows:"
echo "  temporal workflow list"
echo ""
echo "=================================="
echo -e "${YELLOW}Note: Worker versioning requires additional Temporal configuration."
echo "See WORKER_VERSIONING_ENABLEMENT_GUIDE.md for details.${NC}"
echo "=================================="
