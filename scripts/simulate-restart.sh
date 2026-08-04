#!/bin/bash
echo "=== Scenario B: Worker Crash Recovery Demo ==="
echo ""
echo "Step 1: Submitting 20 jobs for Shopify..."
for i in $(seq 1 20); do
  curl -s -X POST http://localhost:8080/api/v1/jobs \
    -H "X-API-Key: sk-shopify-abc123" \
    -H "Content-Type: application/json" \
    -d "{\"type\":\"order-processing\",\"payload\":{\"orderId\":\"ORD-$i\"},\"maxRetries\":3,\"delayMs\":0}" > /dev/null
done
echo "20 jobs submitted."
echo ""
echo "Step 2: Waiting 5 seconds for some jobs to start RUNNING..."
sleep 5
echo ""
echo "Step 3: Restarting Spring Boot app (simulating crash)..."
docker compose restart app
echo ""
echo "Step 4: Waiting for app to come back online..."
until curl -s http://localhost:8080/actuator/health | grep -q '"status":"UP"'; do
  echo "  Waiting..."
  sleep 3
done
echo "App is back online!"
echo ""
echo "Step 5: Check dashboard at http://localhost:3000"
echo "RUNNING jobs should recover automatically within 30 seconds via Lease Reaper."
