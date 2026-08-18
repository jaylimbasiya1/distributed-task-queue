# Distributed Task Queue

A multi-tenant, horizontally scalable background job processing platform, roughly the kind of engine that sits behind Sidekiq, Celery, or BullMQ, built on Java 21, Spring Boot, PostgreSQL, and Redis.

Jobs are persisted in PostgreSQL before being queued in Redis, retried with exponential backoff on failure, and protected by Redis-based distributed leases so exactly one worker executes a job at a time, even across multiple app instances. If a worker crashes mid-execution, the job gets detected and recovered automatically. Delivery guarantee is at-least-once, with idempotent submission.

## Highlights

- Distributed locking with Redis `SET NX EX` leases and background renewal, so no two workers ever double-process a job
- Crash recovery: a scheduled reaper detects orphaned jobs from dead workers and re-queues them within 15 seconds
- Multi-tenant isolation through per-tenant rate limiting (sliding window) and concurrency quotas (atomic counters via Redis `INCR`)
- Job submission is exactly-once (idempotency keys prevent duplicate creation), execution is at-least-once
- Autoscaling worker pool that grows and shrinks with queue depth, with guards against thrashing
- Prometheus metrics, a pre-built Grafana dashboard, and 100%-sampled distributed tracing via Zipkin
- Live React + WebSocket dashboard showing every job transition in real time, no polling
- Dead-letter queue for exhausted jobs, with full error history, retryable or purgeable from the UI

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

> All tenant API keys shipped in this repo (`demo-shopify-key`, etc.) are fixture data for local demo purposes only, seeded fresh on every container start — not production credentials.

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
| `tenant-shopify` | `demo-shopify-key` | 100 | 10 | 3 | Steady normal load |
| `tenant-uber` | `demo-uber-key` | 200 | 20 | 5 | Bursty, high volume |
| `tenant-netflix` | `demo-netflix-key` | 500 | 50 | 3 | Heavy long jobs, requires idempotency key |
| `tenant-badactor` | `demo-badactor-key` | 10 | 2 | 1 | Tight limits to demonstrate rate limiting |

All API calls require: `X-API-Key: <tenant-api-key>`

---

## API Reference

### Authentication

Every request must include `X-API-Key: <key>`. The key identifies the tenant — all job operations are automatically scoped to that tenant.


### Submit a Job — `POST /api/v1/jobs`

```bash
curl -X POST http://localhost:8080/api/v1/jobs \
  -H "X-API-Key: demo-shopify-key" \
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


### Get a Job — `GET /api/v1/jobs/{id}`

```bash
curl http://localhost:8080/api/v1/jobs/3fa85f64-5717-4562-b3fc-2c963f66afa6 \
  -H "X-API-Key: demo-shopify-key"
```

Returns `404` if not found. Returns `403` if the job belongs to a different tenant.


### List Jobs — `GET /api/v1/jobs`

```bash
# All jobs, paginated
curl "http://localhost:8080/api/v1/jobs?page=0&size=20" \
  -H "X-API-Key: demo-shopify-key"

# Filter by status
curl "http://localhost:8080/api/v1/jobs?status=RUNNING" \
  -H "X-API-Key: demo-shopify-key"
```

Valid status values: `PENDING`, `RUNNING`, `COMPLETED`, `CANCELLED`, `DLQ`


### Cancel a Job — `DELETE /api/v1/jobs/{id}`

Only `PENDING` jobs can be cancelled. Returns `409` if the job is already RUNNING or in a terminal state.

```bash
curl -X DELETE http://localhost:8080/api/v1/jobs/{id} \
  -H "X-API-Key: demo-shopify-key"
```


### Retry a Job — `POST /api/v1/jobs/{id}/retry`

Re-submits a failed or DLQ job from scratch (resets attempt counter to 0).

```bash
curl -X POST http://localhost:8080/api/v1/jobs/{id}/retry \
  -H "X-API-Key: demo-shopify-key"
```


### Tenant Stats — `GET /api/v1/tenants/{id}/stats`

```bash
curl http://localhost:8080/api/v1/tenants/tenant-shopify/stats \
  -H "X-API-Key: demo-shopify-key"
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


### DLQ — `GET /api/v1/dlq`

List all DLQ entries for the authenticated tenant.

### DLQ Retry — `POST /api/v1/dlq/{id}/retry`

Resubmit a DLQ entry as a new job (fresh attempt counter).

### DLQ Purge — `DELETE /api/v1/dlq/{id}`

Permanently remove a DLQ entry.


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


### Scenario A — Worker Crash Recovery

```bash
./scripts/simulate-restart.sh
```

Submits 20 Shopify jobs, waits for some to reach RUNNING, then kills and restarts the app container. Within 15 seconds of restart, `LeaseReaper` finds RUNNING jobs with no Redis lease (crash evidence) and re-queues them. All 20 jobs eventually reach COMPLETED.


### Scenario B — Redis Restart (Durability)

```bash
./scripts/simulate-redis-restart.sh
```

Submits 30 Netflix jobs, then restarts Redis. Jobs that were in the Redis sorted set are lost. But PostgreSQL is the source of truth — all PENDING rows survive. On reconnect, the app rehydrates the Redis queue from PostgreSQL and resumes processing. No jobs are lost.


### Manual Scenarios

**Trigger rate limiting (BadActor):**
```bash
for i in $(seq 1 20); do
  curl -s -X POST http://localhost:8080/api/v1/jobs \
    -H "X-API-Key: demo-badactor-key" \
    -H "Content-Type: application/json" \
    -d '{"type":"spam","payload":{"durationMs":100},"maxRetries":1,"delayMs":0}' &
done
# First 10 accepted, remaining 10 get 429
```

**Fill the DLQ:**
```bash
curl -X POST http://localhost:8080/api/v1/jobs \
  -H "X-API-Key: demo-shopify-key" \
  -H "Content-Type: application/json" \
  -d '{"type":"always-fails","payload":{"durationMs":100,"failureRate":1.0},"maxRetries":3,"delayMs":0}'
# Retries at 2s, 4s, 8s then moves to DLQ
```

**Schedule a delayed job:**
```bash
curl -X POST http://localhost:8080/api/v1/jobs \
  -H "X-API-Key: demo-shopify-key" \
  -H "Content-Type: application/json" \
  -d '{"type":"nightly-report","payload":{"durationMs":500},"maxRetries":3,"delayMs":60000}'
# Stays PENDING for 60 seconds, then executes
```

---

## Design Decisions

### 1. Redis sorted set as the job queue

Jobs go in via `ZADD job_queue {executeAtMs} {jobId}`, and workers pop them with a Lua script that atomically checks `score <= now` and calls `ZPOPMIN`. A sorted set gives O(log N) insert and O(1) peek, and it makes delayed/scheduled jobs first-class: a job with a future score just doesn't get returned until its time arrives, so there's no separate "delayed job" table to maintain. The Lua pop is atomic, so two workers can never grab the same entry.

The trade-off is that Redis isn't durable by default. I turned on append-only persistence (`--appendonly yes`), but the real recovery path is PostgreSQL: if Redis loses its queue, `QueueService` rehydrates all PENDING jobs from the DB on the next poll cycle.

### 2. Delivery guarantee: at-least-once, not exactly-once

Worth being precise about what each layer actually guarantees, since "exactly-once" gets thrown around loosely:

| Layer | Guarantee |
|---|---|
| Job submission with idempotency key | Exactly-once job creation. Same `(tenantId, idempotencyKey)` always returns the same job record, never a second row |
| Lease (`SET NX EX`) | At-most-once *per attempt*. Only one worker can hold the lease at a time, so two workers never run the same job concurrently |
| Overall execution | At-least-once. If a worker crashes mid-execution, the LeaseReaper re-queues the job and it runs again on a different worker |
| COMPLETED marking | Guarded by `isHeldBy`, so a stale worker can't mark a job COMPLETED after its lease was reclaimed |

True exactly-once execution would need distributed transactions spanning Redis and Postgres, with each step atomic with the job execution itself. That's not practical for a general-purpose queue, so this follows the standard approach instead: at-least-once delivery, idempotent handlers. If the same `idempotencyKey` comes in twice, the API just returns the existing job record with its current status rather than creating anything new.

The lease mechanism underneath this: before running a job, a worker calls `SET lease:{jobId} {workerId} NX EX {ttl}`. Only one worker can win that call across any number of instances, since Redis `SET NX` is atomic. The `EX {ttl}` means the lock expires on its own if the worker dies.

### 3. Lease renewal to avoid false expiry

If a job runs longer than the lease TTL (30s by default), Redis expires the lease while the job is still legitimately running. The LeaseReaper then sees a RUNNING job with no lease, which looks exactly like a crash, and re-queues it. A second worker picks it up while the first one is still finishing, and when the first worker completes it blindly marks the job COMPLETED too. That's double execution.

Two things fix it. First, a `ScheduledExecutorService` renews the lease every `leaseTtl / 2` seconds while the job runs, calling `EXPIRE lease:{jobId} {ttl}` only if the calling worker still holds it (checked with a GET + compare first). Second, after `executeJob()` returns, the worker checks `isHeldBy(jobId, workerId)` before marking anything COMPLETED — if the lease got reclaimed in the meantime (say, a brief Redis blip broke a renewal), the result is just discarded and the job stays with whoever holds the lease now.

### 4. Atomic concurrency quota via Redis INCR

The first version of this counted running jobs straight from the DB:

```java
long running = jobRepository.countByTenantIdAndStatus(tenantId, RUNNING);
if (running >= max) { re-queue; }
```

which is a textbook check-then-act race: two workers can both read `running = 0` at the same time, both proceed, and the quota gets breached. Swapping in `INCR concurrency:{tenantId}` fixes it, since Redis `INCR` is atomic and concurrent calls always return distinct values. Whichever worker's counter comes back over the limit immediately `DECR`s and re-queues. The slot gets released in a `finally` block so every exit path (success, failure, lost lease) decrements correctly, and if a worker crashes without decrementing, the LeaseReaper's `releaseSlot()` cleans it up when it recovers the orphaned job (with a clamp-to-zero guard so the counter can't go negative). On restart, `WorkerPool.init()` seeds the counters from the DB's RUNNING row count.

### 5. Enqueueing after commit, not inside the transaction

`JobService.submitJob()` runs inside a transaction, and enqueuing to Redis from inside that transaction risks a worker popping the job ID before the DB row is actually committed. So the Redis enqueue is deferred to `afterCommit()` via `TransactionSynchronizationManager.registerSynchronization()` — the DB row is durably committed before any worker can even see the job exists.

### 6. Exponential backoff, capped

`delay = min(baseDelayMs × 2^attempt, maxDelayMs)`, with a 2s base and a 60s cap by default:

| Attempt | Delay |
|---|---|
| 1 | 2s |
| 2 | 4s |
| 3 | 8s |
| 4 | 16s |
| 5+ | 60s (capped) |

After `maxRetries` is exhausted, the job moves to the DLQ with its full error history intact.

### 7. Fail open on Redis errors

`RateLimiterService` and `TenantConcurrencyService` both return `true` (allow) if Redis throws. A Redis outage shouldn't take down job processing entirely — accepting some excess load during an outage beats a full blackout, which is the same call most high-availability rate limiters make.

### 8. Worker autoscaling

`WorkerPoolManager` checks queue depth every 10 seconds and adds 2 threads if depth is at least 50 and the pool is under its max (50), or removes 2 if depth has been at zero for 60+ seconds and the pool is above its min (5). Scaling in steps of 2, rather than jumping straight to 40 threads, avoids both the startup overhead spike and thrashing when a bursty tenant flips between flood and idle.

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
