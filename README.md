# Distributed Task Queue

![Java](https://img.shields.io/badge/Java-21-orange)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2.5-brightgreen)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-blue)
![Redis](https://img.shields.io/badge/Redis-7.2-red)
![React](https://img.shields.io/badge/React-18-61DAFB)
![License](https://img.shields.io/badge/license-MIT-lightgrey)

A multi-tenant, horizontally scalable background job processing platform — the kind of engine that powers Sidekiq, Celery, or BullMQ — built from first principles on Java 21, Spring Boot, PostgreSQL, and Redis.

Jobs are persisted in PostgreSQL before being queued in Redis, retried with exponential backoff on failure, and protected by Redis-based distributed leases so exactly one worker executes a job at a time — even across multiple app instances. If a worker crashes mid-execution, the job is automatically detected and recovered. Delivery guarantee: **at-least-once**, with idempotent submission.

---

## Highlights

- **Distributed locking** — Redis `SET NX EX` leases with background renewal, so no two workers ever double-process a job
- **Crash recovery** — a scheduled reaper detects orphaned jobs from dead workers and re-queues them within 15 seconds
- **Multi-tenant isolation** — per-tenant rate limiting (sliding window) and concurrency quotas (atomic counters), enforced with Redis `INCR`
- **Exactly-once submission, at-least-once execution** — idempotency keys prevent duplicate job creation; execution guarantees are documented honestly, not oversold
- **Autoscaling workers** — thread pool grows and shrinks with queue depth, with anti-thrash guards
- **Full observability** — Prometheus metrics, a pre-built Grafana dashboard, and 100%-sampled distributed tracing via Zipkin
- **Live dashboard** — React + WebSocket UI showing every job transition in real time, no polling
- **Dead-letter queue** — exhausted jobs land in a DLQ with full error history, retryable or purgeable from the UI

---

## Table of Contents

1. [What is a Job?](#what-is-a-job)
2. [Architecture](#architecture)
3. [Quick Start — Single Instance](#quick-start--single-instance)
4. [Using the Dashboard](#using-the-dashboard)
5. [Running Multiple App Instances](#running-multiple-app-instances)
6. [Pre-seeded Tenants](#pre-seeded-tenants)
7. [API Reference](#api-reference)
8. [Observability](#observability)
9. [Simulation & Failure Scenarios](#simulation--failure-scenarios)
10. [Design Decisions](#design-decisions)
11. [Test Suite](#test-suite)
12. [Project Structure](#project-structure)

---

## What is a Job?

A **job** is a unit of asynchronous work submitted by a tenant. It has:

| Field | Description |
|---|---|
| `type` | Logical work category (`order-processing`, `video-encoding`, etc.) |
| `payload` | Arbitrary JSON passed to the worker |
| `maxRetries` | How many times to retry on failure before sending to DLQ |
| `delayMs` | Optional delay — job won't start before `now + delayMs` |
| `idempotencyKey` | Optional. Duplicate submissions with the same key return the original job |

The worker execution is simulated using two payload fields:

- `durationMs` — how long the job runs (`Thread.sleep`)
- `failureRate` — probability 0.0–1.0 of a simulated exception

To connect real work, swap `executeJob()` in `WorkerPool` with a handler dispatch keyed on `type`. Everything else — scheduling, persistence, retries, leases, concurrency quotas — stays as-is.

**Job lifecycle:**

```
PENDING → RUNNING → COMPLETED
                 ↘ PENDING (retry with exponential backoff)
                       ↘ DLQ (after maxRetries exhausted)
PENDING → CANCELLED (explicit cancel before pickup)
```

---

## Architecture

```
┌─────────────┐    HTTP + API Key    ┌──────────────────────────────────────┐
│   Clients   │ ──────────────────▶  │          Spring Boot App             │
│  (curl /    │                      │                                      │
│  dashboard) │  WebSocket (status)  │  JobController  TenantController     │
│             │ ◀─────────────────── │  DLQController                       │
└─────────────┘                      │                                      │
                                     │  JobService  RateLimiterService      │
                                     │  TenantConcurrencyService            │
                                     │                                      │
                                     │  WorkerPool (5–50 threads)           │
                                     │    LeaseManager    LeaseReaper       │
                                     │    WorkerPoolManager (autoscale)     │
                                     └──────┬──────────────┬───────────────┘
                                            │              │
                               ┌────────────▼───┐  ┌──────▼──────────────┐
                               │   PostgreSQL   │  │        Redis         │
                               │  (durable job  │  │  job_queue (sorted)  │
                               │    store)      │  │  lease:{jobId}       │
                               └────────────────┘  │  concurrency:{tid}   │
                                                   │  ratelimit:{tid}:{w} │
                                                   └─────────────────────┘
```

**Component responsibilities:**

| Component | Role |
|---|---|
| PostgreSQL | Source of truth for all job state. Survives Redis restarts. |
| Redis sorted set `job_queue` | Priority queue ordered by `executeAt` epoch ms. Workers pop atomically via Lua script. |
| Redis `lease:{jobId}` | Mutex per job. `SET NX EX` ensures only one worker across all instances executes each job. |
| Redis `concurrency:{tenantId}` | Atomic INCR/DECR counter enforcing per-tenant in-flight job limit. |
| Redis `ratelimit:{tenantId}:{window}` | Sliding-window API rate limiter, per tenant per minute. |
| LeaseReaper | Scheduled every 15s. Recovers jobs whose workers crashed (RUNNING with expired Redis lease). |
| WorkerPoolManager | Autoscales thread pool every 10s based on Redis queue depth. |
| WebSocket `/ws` | Broadcasts every job status change in real time to connected dashboards. |

---

## Quick Start — Single Instance

**Prerequisites:** Docker and Docker Compose installed.

```bash
git clone <this-repo>
cd distributed-task-queue
docker compose up --build
```

Wait ~30 seconds for all health checks to pass, then:

| Service | URL | Credentials |
|---|---|---|
| React Dashboard | http://localhost:3000 | — |
| Swagger UI | http://localhost:3000/swagger-ui/index.html | — |
| Grafana | http://localhost:3001 | admin / admin |
| Prometheus | http://localhost:9090 | — |
| Zipkin | http://localhost:9411 | — |
| App health | http://localhost:8080/actuator/health | — |

Tenants and API keys are seeded automatically on startup from `config/tenants.json`. The simulation also starts automatically — open the dashboard to see live job flow.

> All tenant API keys shipped in this repo (`sk-shopify-abc123`, etc.) are fixture data for local demo purposes only, seeded fresh on every container start — not production credentials.

---

## Using the Dashboard

The primary interface is the React dashboard at **http://localhost:3000**. Everything below can be done from there — no Postman or curl needed.

**Tenant selector (top header):** Switch between the four pre-seeded tenants. The API key switches automatically — the dashboard uses each tenant's key for all requests.

**Metrics bar:** Shows live counts of PENDING / RUNNING / COMPLETED / DLQ jobs for the active tenant, updated in real time via WebSocket.

**Submit Job form:**
- **Job Type** — any string label (e.g. `order-processing`, `video-encoding`)
- **JSON Payload** — the parameters passed to the worker. Use `durationMs` to control how long the job runs and `failureRate` (0.0–1.0) to simulate failures
- **Idempotency Key** — optional. Hit "Generate" to create a UUID, or type one manually. Submitting the same key again returns the original job instead of creating a new one
- **Delay (ms)** — job won't start before `now + delay`
- **Max Retries** — how many times to retry before moving to DLQ

**Job table:** Tabs for each status (PENDING / RUNNING / COMPLETED / DLQ). Status badges update live as the WebSocket pushes changes. Each row has Cancel (PENDING only) and Retry actions. Click any row to open a detail modal with the full job payload and error history.

**DLQ panel:** Expandable section at the bottom. Shows all dead-letter entries for the active tenant with Retry (resubmit as a new job) and Purge (delete permanently) actions.

**Swagger UI** at **http://localhost:3000/swagger-ui/index.html** has the full API schema if you want to explore request/response shapes or test edge cases programmatically.

---

## Running Multiple App Instances

The system is designed to run any number of app instances against the same PostgreSQL and Redis. The Redis lease (`SET NX EX`) is the distributed mutex — only one worker across all instances will execute each job.

To scale horizontally, remove the fixed host port from the `app` service in `docker-compose.yml` first (multiple containers cannot share port 8080 on the host). The dashboard's nginx already proxies `/api/` → `http://app:8080` using Docker's internal DNS, which load-balances across all `app` replicas automatically.

```yaml
# docker-compose.yml — comment out the app port binding:
# ports:
#   - "8080:8080"
```

Then start 3 app instances:

```bash
docker compose up --build --scale app=3
```

**What this demonstrates:**

- 3 instances × 5 threads minimum = 15 workers competing for the same Redis queue
- The Redis `SET NX` lease ensures no job runs twice — even with 15 concurrent workers
- Submit a batch of jobs and restart one instance mid-flight (`docker compose restart tq-app`): the LeaseReaper on the surviving instances recovers orphaned RUNNING jobs within 15 seconds
- Zipkin traces show different `worker-N` thread names, proving jobs ran on different instances

---

## Pre-seeded Tenants

| Tenant ID | API Key | Rate Limit/min | Max Concurrent | Max Retries | Notes |
|---|---|---|---|---|---|
| `tenant-shopify` | `sk-shopify-abc123` | 100 | 10 | 3 | Steady normal load |
| `tenant-uber` | `sk-uber-def456` | 200 | 20 | 5 | Bursty, high volume |
| `tenant-netflix` | `sk-netflix-ghi789` | 500 | 50 | 3 | Heavy long jobs, requires idempotency key |
| `tenant-badactor` | `sk-bad-jkl000` | 10 | 2 | 1 | Tight limits to demonstrate rate limiting |

All API calls require: `X-API-Key: <tenant-api-key>`

---

## API Reference

### Authentication

Every request must include `X-API-Key: <key>`. The key identifies the tenant — all job operations are automatically scoped to that tenant.

---

### Submit a Job — `POST /api/v1/jobs`

```bash
curl -X POST http://localhost:8080/api/v1/jobs \
  -H "X-API-Key: sk-shopify-abc123" \
  -H "Content-Type: application/json" \
  -d '{
    "type": "order-processing",
    "payload": {
      "orderId": "ORD-001",
      "durationMs": 2000,
      "failureRate": 0.0
    },
    "idempotencyKey": "order-ORD-001-v1",
    "maxRetries": 3,
    "delayMs": 0
  }'
```

| payload field | Effect |
|---|---|
| `durationMs` | How long the simulated job runs (milliseconds) |
| `failureRate` | 0.0–1.0 probability of throwing a simulated exception |

**Delayed job** (starts in 30 seconds):
```bash
"delayMs": 30000
```

**Job that will fail and retry to DLQ:**
```bash
"payload": {"durationMs": 500, "failureRate": 1.0}, "maxRetries": 3
# Retries 3 times (2s, 4s, 8s backoff), then moves to DLQ
```

**Response `201 Created`:**
```json
{
  "id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "tenantId": "tenant-shopify",
  "type": "order-processing",
  "status": "PENDING",
  "attempt": 0,
  "maxRetries": 3,
  "scheduledAt": "2025-01-01T10:00:00",
  "createdAt": "2025-01-01T10:00:00"
}
```

**Idempotency:** Submitting the same `idempotencyKey` twice returns the original job (same `id`, current `status`) — no second row is created.

**Rate limit exceeded — `429 Too Many Requests`:**
```json
{"error": "Rate limit exceeded for tenant: tenant-badactor"}
```

---

### Get a Job — `GET /api/v1/jobs/{id}`

```bash
curl http://localhost:8080/api/v1/jobs/3fa85f64-5717-4562-b3fc-2c963f66afa6 \
  -H "X-API-Key: sk-shopify-abc123"
```

Returns `404` if not found. Returns `403` if the job belongs to a different tenant.

---

### List Jobs — `GET /api/v1/jobs`

```bash
# All jobs, paginated
curl "http://localhost:8080/api/v1/jobs?page=0&size=20" \
  -H "X-API-Key: sk-shopify-abc123"

# Filter by status
curl "http://localhost:8080/api/v1/jobs?status=RUNNING" \
  -H "X-API-Key: sk-shopify-abc123"
```

Valid status values: `PENDING`, `RUNNING`, `COMPLETED`, `CANCELLED`, `DLQ`

---

### Cancel a Job — `DELETE /api/v1/jobs/{id}`

Only `PENDING` jobs can be cancelled. Returns `409` if the job is already RUNNING or in a terminal state.

```bash
curl -X DELETE http://localhost:8080/api/v1/jobs/{id} \
  -H "X-API-Key: sk-shopify-abc123"
```

---

### Retry a Job — `POST /api/v1/jobs/{id}/retry`

Re-submits a failed or DLQ job from scratch (resets attempt counter to 0).

```bash
curl -X POST http://localhost:8080/api/v1/jobs/{id}/retry \
  -H "X-API-Key: sk-shopify-abc123"
```

---

### Tenant Stats — `GET /api/v1/tenants/{id}/stats`

```bash
curl http://localhost:8080/api/v1/tenants/tenant-shopify/stats \
  -H "X-API-Key: sk-shopify-abc123"
```

```json
{
  "tenantId": "tenant-shopify",
  "pending": 12,
  "running": 3,
  "completed": 847,
  "failed": 23,
  "dlq": 2
}
```

---

### Create a Tenant — `POST /api/v1/tenants`

```bash
curl -X POST http://localhost:8080/api/v1/tenants \
  -H "Content-Type: application/json" \
  -d '{
    "id": "tenant-acme",
    "name": "Acme Corp",
    "apiKey": "sk-acme-xyz",
    "rateLimitPerMinute": 60,
    "maxConcurrentJobs": 5,
    "maxRetriesDefault": 3,
    "requireIdempotencyKey": false
  }'
```

---

### DLQ — `GET /api/v1/dlq`

List all DLQ entries for the authenticated tenant.

### DLQ Retry — `POST /api/v1/dlq/{id}/retry`

Resubmit a DLQ entry as a new job (fresh attempt counter).

### DLQ Purge — `DELETE /api/v1/dlq/{id}`

Permanently remove a DLQ entry.

---

### WebSocket — `ws://localhost:3000/ws`

Connect to receive real-time job status updates. Every state transition is broadcast to all connected clients as a JSON job object. The React dashboard connects here — no polling.

---

## Observability

### Swagger UI

Interactive API explorer with full request/response schemas:
**http://localhost:3000/swagger-ui/index.html**

### Grafana — http://localhost:3001 (admin / admin)

The pre-provisioned **Task Queue Overview** dashboard shows:
- Jobs submitted, completed, and failed per minute
- Queue depth over time
- Worker thread pool size (autoscaling in action)
- DLQ accumulation
- Per-tenant job breakdowns

### Prometheus — http://localhost:9090

Raw metrics scraped from `/actuator/prometheus` every 15 seconds:

| Metric | Description |
|---|---|
| `taskqueue_jobs_submitted_total` | Counter by tenant and job type |
| `taskqueue_jobs_completed_total` | Counter by tenant |
| `taskqueue_jobs_failed_total` | Counter by tenant |
| `taskqueue_jobs_dlq_total` | Counter by tenant |
| `taskqueue_job_duration_ms` | Histogram of execution times |
| `taskqueue_queue_depth` | Current Redis queue length |
| `taskqueue_worker_pool_size` | Current thread pool size |

### Zipkin — http://localhost:9411

Every job submission and processing step emits a trace. Search for service `task-queue`. Open any trace to see the full waterfall: HTTP receive → DB write → Redis enqueue → worker poll → execute → DB update. Sampling is 100%.

### Structured Logs

All logs are JSON, correlated by `traceId`, `jobId`, and `tenantId` via MDC:

```json
{"time":"2025-01-01T10:00:01","level":"INFO","traceId":"abc123","jobId":"3fa85f64","tenantId":"tenant-shopify","msg":"Completed job 3fa85f64 in 812ms"}
```

---

## Simulation & Failure Scenarios

### Built-in Simulation

`SimulationRunner` starts automatically and submits jobs per `config/simulation.json`:

| Tenant | Scenario | Rate | Job Duration | Failure Rate |
|---|---|---|---|---|
| Shopify | Steady normal | 40/min | 800ms | 5% |
| Uber | Bursty | 150/min | 300ms | 15% |
| Netflix | Heavy jobs | 20/min | 3000ms | 20% |
| BadActor | Rate limit breach | 500/min | 100ms | 0% |

Open http://localhost:3000 to watch jobs flow through all states. BadActor hits its 10/min rate limit immediately — the dashboard shows `429` rejections while the other three tenants process normally.

---

### Scenario A — Worker Crash Recovery

```bash
./scripts/simulate-restart.sh
```

Submits 20 Shopify jobs, waits for some to reach RUNNING, then kills and restarts the app container. Within 15 seconds of restart, `LeaseReaper` finds RUNNING jobs with no Redis lease (crash evidence) and re-queues them. All 20 jobs eventually reach COMPLETED.

---

### Scenario B — Redis Restart (Durability)

```bash
./scripts/simulate-redis-restart.sh
```

Submits 30 Netflix jobs, then restarts Redis. Jobs that were in the Redis sorted set are lost. But PostgreSQL is the source of truth — all PENDING rows survive. On reconnect, the app rehydrates the Redis queue from PostgreSQL and resumes processing. No jobs are lost.

---

### Manual Scenarios

**Trigger rate limiting (BadActor):**
```bash
for i in $(seq 1 20); do
  curl -s -X POST http://localhost:8080/api/v1/jobs \
    -H "X-API-Key: sk-bad-jkl000" \
    -H "Content-Type: application/json" \
    -d '{"type":"spam","payload":{"durationMs":100},"maxRetries":1,"delayMs":0}' &
done
# First 10 accepted, remaining 10 get 429
```

**Fill the DLQ:**
```bash
curl -X POST http://localhost:8080/api/v1/jobs \
  -H "X-API-Key: sk-shopify-abc123" \
  -H "Content-Type: application/json" \
  -d '{"type":"always-fails","payload":{"durationMs":100,"failureRate":1.0},"maxRetries":3,"delayMs":0}'
# Retries at 2s, 4s, 8s then moves to DLQ
```

**Schedule a delayed job:**
```bash
curl -X POST http://localhost:8080/api/v1/jobs \
  -H "X-API-Key: sk-shopify-abc123" \
  -H "Content-Type: application/json" \
  -d '{"type":"nightly-report","payload":{"durationMs":500},"maxRetries":3,"delayMs":60000}'
# Stays PENDING for 60 seconds, then executes
```

---

## Design Decisions

### 1. Redis Sorted Set as the Job Queue

`ZADD job_queue {executeAtMs} {jobId}` — workers pop via a Lua script that atomically checks `score <= now` and calls `ZPOPMIN`.

**Why:** A sorted set gives O(log N) insert and O(1) peek. Delayed and scheduled jobs are first-class — they sit at a future score and are simply not returned until their time arrives. No separate "delayed job" table is needed. The Lua pop is atomic: no two workers can pop the same entry.

**Trade-off:** Redis is not durable by default. Append-only persistence is enabled (`--appendonly yes`) and PostgreSQL is the recovery source — if Redis loses its queue, `QueueService` rehydrates all PENDING jobs from the DB on the next poll cycle.

---

### 2. Delivery Guarantee — At-Least-Once with Lease-Bounded Windows

The system is **at-least-once execution**, not exactly-once. It is important to be clear about what each layer guarantees:

| Layer | Guarantee |
|---|---|
| Job submission with idempotency key | Exactly-once job creation — same `(tenantId, idempotencyKey)` always returns the same job record, never creates a second row |
| Lease (`SET NX EX`) | At-most-once delivery *per attempt* — only one worker can hold the lease at a time, so two workers cannot execute the same job concurrently |
| Overall execution | At-least-once — if a worker crashes mid-execution, the LeaseReaper re-queues the job and it runs again on a different worker |
| COMPLETED marking | Protected by `isHeldBy` — a stale worker cannot mark a job COMPLETED after the lease was reclaimed |

**Why not exactly-once execution?** True exactly-once requires distributed transactions across Redis and PostgreSQL — each step would need to be atomic with the job execution itself. That is impractical for a general-purpose queue. The standard approach is at-least-once delivery with idempotent job handlers.

**Idempotency key + duplicate submission:** If the same `idempotencyKey` is submitted a second time, the API returns the existing job record with its current `status` — no new row, no second execution. If the original job is already COMPLETED, the response shows COMPLETED. If it is still RUNNING, the response shows RUNNING. The job runs once (or more than once only if a worker crashed mid-way, which is the at-least-once case above).

Before executing a job, each worker calls `SET lease:{jobId} {workerId} NX EX {ttl}`. Only one worker can win this across any number of instances — Redis `SET NX` is atomic by definition. The `EX {ttl}` auto-expires the lock if the worker crashes.

---

### 3. Lease Renewal Prevents False Expiry

**The problem:** If `durationMs > leaseTtlSeconds` (default 30s), Redis auto-expires the lease. `LeaseReaper` sees a RUNNING job with no lease — identical to a crashed worker — and re-queues it. A second worker picks it up. The first worker finishes and blindly marks it COMPLETED. **Result: double execution.**

**The fix (two layers):**

1. **Lease renewal:** A `ScheduledExecutorService` fires every `leaseTtl / 2` seconds during execution. Each renewal calls `EXPIRE lease:{jobId} {ttl}` only if the calling worker is still the current holder (GET + compare). The lease TTL is continuously reset, so it never expires during normal execution.

2. **Ownership check:** After `executeJob()` returns, the worker verifies `isHeldBy(jobId, workerId)` before marking COMPLETED. If the lease was reclaimed (e.g., renewal failed during a brief Redis outage), the result is silently discarded — the job continues under the worker that now holds the lease.

---

### 4. Atomic Concurrency Quota via Redis INCR

**The problem (original code):**
```java
long running = jobRepository.countByTenantIdAndStatus(tenantId, RUNNING);
if (running >= max) { re-queue; }
```
Two workers reading simultaneously both see `running = 0`. Both proceed. Quota is breached. This is a classic check-then-act race condition.

**The fix:** Replace the DB count with `INCR concurrency:{tenantId}`. Redis `INCR` is atomic — two concurrent calls return distinct values (e.g., 1 and 2). The worker whose counter exceeds the limit immediately calls `DECR` and re-queues. The slot is released in a `finally` block, so every exit path (success, failure, lease-lost) decrements correctly.

**Crash recovery:** If a worker crashes without decrementing, `LeaseReaper` calls `releaseSlot()` when recovering the orphaned job. A clamp-to-zero guard prevents the counter from going negative. On app restart, `WorkerPool.init()` seeds counters from the DB's RUNNING row count to account for jobs that survived the restart.

---

### 5. Transactional Enqueueing via afterCommit Hook

`JobService.submitJob()` is `@Transactional`. Enqueuing to Redis inside the transaction risks a worker popping a job ID before the DB row is committed. Instead, `TransactionSynchronizationManager.registerSynchronization()` delays the Redis enqueue until `afterCommit()`. The DB row is durably committed before any worker can see the job.

---

### 6. Exponential Backoff with Jitter-free Cap

On failure: `delay = min(baseDelayMs × 2^attempt, maxDelayMs)` — defaults: base 2s, max 60s.

| Attempt | Delay |
|---|---|
| 1 | 2s |
| 2 | 4s |
| 3 | 8s |
| 4 | 16s |
| 5+ | 60s (capped) |

After `maxRetries` attempts, the job moves to DLQ with its full error history preserved.

---

### 7. Fail-Open Strategy for Redis Errors

Both `RateLimiterService` and `TenantConcurrencyService` return `true` (allow) when Redis throws an exception. A Redis outage should not halt job processing entirely. Accepting some excess requests during an outage is preferred over a complete service blackout — the same policy used by most high-availability rate limiters.

---

### 8. Worker Autoscaling

`WorkerPoolManager` checks queue depth every 10 seconds:
- Queue depth ≥ 50 and pool < max (50) → add 2 threads
- Queue depth = 0 for 60+ consecutive seconds and pool > min (5) → remove 2 threads

Step-based scaling (±2) avoids the overhead spike of spinning up 40 threads at once and prevents thrashing when a bursty tenant alternates between flood and idle.

---

## Test Suite

```bash
cd backend
mvn test
```

30 tests across 5 classes, using H2 in-memory DB — no Docker required:

| Test Class | Coverage |
|---|---|
| `IdempotencyTest` | Duplicate key returns same job; different keys create new jobs; status preserved on duplicate |
| `ConcurrencyTest` | Redis lease prevents double execution under 10 concurrent workers; different jobs run concurrently without interference |
| `LeaseExpiryTest` | LeaseReaper re-queues jobs with expired leases; healthy leases are not touched |
| `RateLimiterTest` | Requests within limit are allowed; excess requests are rejected; window resets correctly |
| `RetryBackoffTest` | Failed jobs retry with exponential backoff delays; jobs exceeding maxRetries land in DLQ |

---

## Project Structure

```
distributed-task-queue/
├── backend/                        # Spring Boot application (Java 21)
│   └── src/main/java/com/taskqueue/
│       ├── api/                    # REST controllers + GlobalExceptionHandler
│       ├── config/                 # AppProperties, RedisConfig, SecurityConfig
│       ├── domain/                 # Job, Tenant, DLQEntry, JobStatus
│       ├── dto/                    # JobSubmitRequest, JobResponse
│       ├── metrics/                # Micrometer counters and histograms
│       ├── repository/             # Spring Data JPA repositories
│       ├── security/               # ApiKeyAuthFilter, TenantContext (ThreadLocal)
│       ├── service/                # JobService, QueueService, RateLimiterService,
│       │                           # TenantConcurrencyService, DLQService
│       ├── simulation/             # SimulationRunner, DataSeeder
│       ├── websocket/              # JobStatusBroadcaster
│       └── worker/                 # WorkerPool, LeaseManager, LeaseReaper,
│                                   # WorkerPoolManager
├── config/
│   ├── tenants.json                # Pre-seeded tenant definitions
│   └── simulation.json             # Per-tenant simulation scenarios
├── dashboard/                      # React UI + nginx (proxies /api/, /ws, /swagger-ui/)
├── grafana/
│   ├── dashboards/taskqueue.json   # Pre-built Grafana dashboard
│   └── provisioning/               # Auto-wired datasource and dashboard
├── scripts/
│   ├── simulate-restart.sh         # Crash recovery demo (Scenario A)
│   └── simulate-redis-restart.sh   # Redis durability demo (Scenario B)
├── prometheus.yml                  # Scrapes /actuator/prometheus every 15s
└── docker-compose.yml              # postgres, redis, app, dashboard,
                                    # prometheus, grafana, zipkin
```

---

## Author

**Jay Limbasiya**
[GitHub](https://github.com/jaylimbasiya1) · [LinkedIn](https://www.linkedin.com/in/jay-limbasiya-b47249141/)
