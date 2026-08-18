#!/bin/bash
echo "=== Scenario C: Redis Restart (Durability Test) ==="
echo ""
echo "Step 1: Submitting 30 jobs..."
for i in $(seq 1 30); do
  curl -s -X POST http://localhost:8080/api/v1/jobs \
    -H "X-API-Key: demo-netflix-key" \
    -H "Content-Type: application/json" \
    -d "{\"type\":\"video-encoding\",\"payload\":{\"videoId\":\"VID-$i\"},\"idempotencyKey\":\"vid-$i-v1\",\"maxRetries\":3,\"delayMs\":0}" > /dev/null
done
echo "30 jobs submitted."
echo ""
echo "Step 2: Restarting Redis..."
docker compose restart redis
echo ""
echo "Step 3: Waiting for Redis to come back..."
sleep 5
echo ""
echo "Step 4: App will rehydrate queue from PostgreSQL on next poll."
echo "Check dashboard — all PENDING jobs should reappear within 15 seconds."
