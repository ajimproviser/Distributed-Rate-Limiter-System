# Distributed Rate Limiter System

A payment gateway-scale rate limiting service built from scratch on **Java 25, Spring Boot, Redis and Docker**.

Two interchangeable algorithms — **Token Bucket** and **Sliding Window Log** — run entirely inside Redis as atomic
Lua scripts, so any number of application instances enforce a single shared quota. Enforcement is replay-safe:
a retried request returns its original verdict instead of consuming quota twice.

---

## What makes this correct rather than merely working

Most rate limiter implementations are wrong in one of three ways. This one addresses each explicitly:

| Failure mode | Why it happens | How this project avoids it |
|---|---|---|
| **Over-admission under load** | `GET` counter, decide, `SET` counter — two instances interleave and both admit the request that should have exhausted the quota | The whole decision is one Lua script. Redis executes it atomically, so there is no window to interleave. Proven by a 200-thread contention test. |
| **Clock skew between instances** | Each instance stamps requests with its own wall clock; a few hundred ms of drift silently widens or narrows every window | Scripts read `redis.call('TIME')`. The Redis server is the single clock for the entire fleet. |
| **Double-charging on retries** | A client times out, retries, and pays quota twice for one logical request | An optional idempotency key caches the original verdict for replay, so N retries cost one unit. |

---

## Verified results

Measured on the Docker Compose stack in this repository (Windows 11, Docker Desktop, WSL2 backend):

```
Redis-side script execution     80.39 µs per call   (INFO commandstats, cmdstat_evalsha, 740 calls)
End-to-end evaluation           1.12 ms mean        (Micrometer timer, includes the network round trip)
Script cache effectiveness      740 EVALSHA / 2 EVAL  (full script body sent twice, at startup, never again)
Redis memory                    1.38 MB
Keys without a TTL              0                   (every key expires; no unbounded growth)
```

The decision itself is comfortably sub-millisecond — 80 µs inside Redis. The 1.12 ms figure is the honest
end-to-end number in this environment, and it is dominated by the Docker Desktop network hop, not by the
algorithm. `payments-standard` is configured at 1,700 requests/second, i.e. **102,000 requests/minute** sustained,
with a 2,000 burst allowance.

### One quota across three instances

Three application containers behind nginx, one shared limit of 3, same caller:

```
req 1 -> HTTP 200  served-by=172.18.0.4  remaining=2
req 2 -> HTTP 200  served-by=172.18.0.5  remaining=1
req 3 -> HTTP 200  served-by=172.18.0.3  remaining=0
req 4 -> HTTP 429  served-by=172.18.0.4  retry-after=60
req 5 -> HTTP 429  served-by=172.18.0.5  retry-after=60
req 6 -> HTTP 429  served-by=172.18.0.3  retry-after=60
```

Every request hit a different instance, the counter still decremented `2 → 1 → 0`, and all three instances
rejected the fourth. Reproduce it with `docker compose --profile distributed up -d`.

### Idempotent enforcement across instances

Same `Idempotency-Key`, retried five times, load-balanced across all three instances:

```
retry 1 -> HTTP 200  served-by=172.18.0.4  remaining=2  replayed=
retry 2 -> HTTP 200  served-by=172.18.0.5  remaining=2  replayed=true
retry 3 -> HTTP 200  served-by=172.18.0.3  remaining=2  replayed=true
retry 4 -> HTTP 200  served-by=172.18.0.4  remaining=2  replayed=true
retry 5 -> HTTP 200  served-by=172.18.0.4  remaining=2  replayed=true
```

Five retries, one token consumed — and instances that never saw the original request still recognised the replay.

### Surviving a Redis outage

Redis stopped mid-flight, then restarted:

```
--- redis down ---
req 1 -> HTTP 200  degraded=true   remaining=3     traffic admitted, enforcement flagged as not applied
req 2 -> HTTP 200  degraded=true   remaining=3
/actuator/health -> HTTP 503                       instance reports unhealthy, so an LB pulls it

--- redis back ---
req 1 -> HTTP 200  degraded=       remaining=2     enforcement resumes immediately
req 2 -> HTTP 200  degraded=       remaining=1
req 3 -> HTTP 200  degraded=       remaining=0
req 4 -> HTTP 429  degraded=
```

The payment path stays up, the degradation is explicit in both the response and the metrics, and the health
endpoint tells the orchestrator the truth.

---

## Architecture

```
                    ┌──────────────────────────────────────┐
   HTTP traffic ───▶ │  RateLimitFilter                     │  runs early in the chain:
                    │  - resolves the subject              │  shed load before auth,
                    │  - 429 + Retry-After when throttled  │  deserialisation or business logic
                    └──────────────┬───────────────────────┘
                                   │
                    ┌──────────────▼───────────────────────┐
                    │  RateLimiterService                  │  metrics, fail-open policy
                    └──────┬───────────────────────┬───────┘
                           │                       │
              ┌────────────▼──────────┐  ┌─────────▼──────────────┐
              │  PolicyCatalog        │  │ StrategyRegistry       │  singletons, built once
              │  (name → limit)       │  │ (algorithm → strategy) │  and validated at startup
              └───────────────────────┘  └─────────┬──────────────┘
                                                   │  Strategy pattern
                              ┌────────────────────┴────────────────────┐
                              │                                         │
                 ┌────────────▼─────────────┐            ┌──────────────▼────────────┐
                 │ TokenBucketRateLimiter   │            │ SlidingWindowRateLimiter  │
                 │ token_bucket.lua         │            │ sliding_window.lua        │
                 └────────────┬─────────────┘            └──────────────┬────────────┘
                              └──────────────┬─────────────────────────-┘
                                             │  one atomic EVALSHA per decision
                                   ┌─────────▼──────────┐
                                   │       Redis        │  authoritative state + clock
                                   └────────────────────┘
```

### Request flow

1. `RateLimitFilter` matches the path against the configured enforcement rules (most specific first).
2. The subject is resolved: `X-API-Key`, then `X-Tenant-Id`, then client IP.
3. `PolicyCatalog` supplies a precomputed policy; `StrategyRegistry` supplies the algorithm.
4. The strategy runs one Lua script, which decides and mutates state atomically.
5. `X-RateLimit-*` headers are written on **every** response, so well-behaved clients can slow down before
   they ever see a 429.

---

## The two algorithms

### Token Bucket — `src/main/resources/scripts/token_bucket.lua`

Tokens accrue continuously up to a capacity. A caller may burst up to the capacity, then settles to the
sustained rate.

State is two hash fields: remaining tokens and the last-touched timestamp. Those are enough to reconstruct the
bucket at any later moment, so refill is computed lazily on read — no sweeper, no timers. The cost of a decision
is therefore **O(1) in time and memory regardless of traffic volume**, which is what makes it the right choice
for six-figure-per-minute quotas.

```
capacity 5, refill 5/s
tokens  5 ████████████████████  burst of 5 admitted instantly
        0                       6th request rejected, retry-after 200ms
        1 ████                  200ms later, one token has accrued
```

### Sliding Window Log — `src/main/resources/scripts/sliding_window.lua`

Each admitted request is one member of a sorted set scored by server time. Counting the window is "drop
everything older than `now - window`, count what remains".

The point is what it prevents. A fixed-window counter resets on wall-clock boundaries, so a caller can spend the
full limit just before a boundary and again just after — **double the intended rate across the seam**. Here the
trailing edge moves continuously, so the limit holds over every possible window position.

```
fixed window (broken)          sliding window (this project)
 |  3 reqs |  3 reqs |          trailing edge moves with the requests
 └─────────┴─────────┘          so 6-in-a-moment is never possible
      ↑ 6 requests in
        a few ms, allowed
```

The trade is memory: one sorted-set member per in-window request. So it is reserved for small,
security-sensitive limits (login attempts, OTP sends) where exactness is worth more than footprint.

### Choosing between them

| | Token Bucket | Sliding Window Log |
|---|---|---|
| Memory per subject | O(1) — one hash | O(requests in window) |
| Burst behaviour | Allows a configured burst | Strictly caps the window |
| Boundary exploit | N/A | Immune |
| Best for | High-volume quotas | Small security limits |
| Configured here | `payments-standard` (1,700/s) | `otp-requests` (3/min) |

---

## Design patterns

**Strategy** — `RateLimiterStrategy` (`core/RateLimiterStrategy.java`) is the seam. `RateLimiterService` and the
filter never branch on algorithm type. Adding an algorithm means adding one enum constant and one
`@Component`; the registry wires it up and nothing else changes.

**Singleton** — two registries are built exactly once and are immutable thereafter:

- `RateLimiterStrategyRegistry` folds every discovered strategy into an immutable `EnumMap`. The request path
  resolves an algorithm through an array index with no locking and no allocation.
- `PolicyCatalog` parses, validates and precomputes every policy at startup, so the hot path does no parsing
  and no division.

Both **verify completeness at boot** rather than on first use: a missing strategy or an undefined
`default-policy` fails the application start with a message naming the offender, instead of surfacing as a
production 500.

Redis scripts are also singletons, cached by SHA-1 and dispatched with `EVALSHA` — verified above as 740
`EVALSHA` against 2 `EVAL`.

---

## Running it

### Docker Compose (recommended)

```bash
docker compose up -d --build          # Redis + one app instance on :8080
docker compose --profile distributed up -d   # + two more instances behind nginx on :8081
docker compose logs -f app
docker compose down -v
```

### Locally

```bash
docker run -d -p 6379:6379 redis:7.4-alpine
mvn spring-boot:run
```

### Endpoints

| | |
|---|---|
| Swagger UI | http://localhost:8080/swagger-ui.html |
| OpenAPI JSON | http://localhost:8080/v3/api-docs |
| Health | http://localhost:8080/actuator/health |
| Prometheus metrics | http://localhost:8080/actuator/prometheus |

---

## API

### Rate limiting as a service

`POST /api/v1/rate-limit/check` — evaluate and consume quota. Always answers `200` with the verdict in the
body; it is an oracle for other services, which act on `allowed` themselves.

```bash
curl -X POST http://localhost:8080/api/v1/rate-limit/check \
  -H 'Content-Type: application/json' \
  -d '{"subject":"merchant-4417","policy":"payments-standard","cost":1,"requestId":"b3d1f0a2"}'
```

```json
{
  "allowed": true,
  "subject": "merchant-4417",
  "policy": "payments-standard",
  "algorithm": "TOKEN_BUCKET",
  "limit": 2000,
  "remaining": 1999,
  "retryAfterMillis": 0,
  "resetAfterMillis": 1,
  "replayed": false,
  "degraded": false
}
```

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/api/v1/rate-limit/check` | Evaluate and consume quota |
| `GET` | `/api/v1/rate-limit/status/{subject}` | Inspect quota without consuming |
| `DELETE` | `/api/v1/rate-limit/{subject}` | Reset a subject to full quota |
| `GET` | `/api/v1/policies` | List configured policies |
| `GET` | `/api/v1/policies/algorithms` | List supported algorithms |

### Guarded demo endpoints

These are protected by the filter, so they return a real `429` — this is where enforcement is visible.

| Method | Path | Policy | Algorithm |
|---|---|---|---|
| `POST` | `/api/v1/payments/authorize` | `payments-standard` (1,700/s, 2,000 burst) | Token bucket |
| `POST` | `/api/v1/payments/otp` | `otp-requests` (3 per rolling minute) | Sliding window |

```bash
# Throttles on the 4th call
for i in 1 2 3 4; do
  curl -s -o /dev/null -w "%{http_code}\n" \
    -X POST http://localhost:8080/api/v1/payments/otp \
    -H 'X-API-Key: merchant-1' -H 'Content-Type: application/json' \
    -d '{"orderId":"ord-1","phone":"+919876543210"}'
done
```

### Response headers

| Header | Meaning |
|---|---|
| `X-RateLimit-Limit` | Units available in a full window |
| `X-RateLimit-Remaining` | Units left for this subject |
| `X-RateLimit-Reset` | Seconds until full quota is restored |
| `X-RateLimit-Policy` / `X-RateLimit-Algorithm` | Which rule applied |
| `X-RateLimit-Replayed` | Present when an idempotency key replayed an earlier verdict |
| `X-RateLimit-Degraded` | Present when Redis was down and fail-open admitted the request |
| `Retry-After` | Seconds to wait, on a 429 |

Rejections are RFC 9457 problem documents:

```json
{
  "type": "https://httpstatuses.io/429",
  "title": "Too Many Requests",
  "status": 429,
  "detail": "Rate limit exceeded for this subject. Retry after 59000 ms.",
  "policy": "otp-requests",
  "algorithm": "SLIDING_WINDOW",
  "limit": 3,
  "remaining": 0,
  "retryAfterMillis": 59000
}
```

---

## Configuration

Limits are data, not code, so quotas retune per environment without a rebuild. See
`src/main/resources/application.yml`.

```yaml
ratelimiter:
  enabled: true
  fail-open: true              # admit traffic if Redis is unreachable
  key-prefix: rl
  default-policy: standard

  identity:
    api-key-header: X-API-Key
    tenant-header: X-Tenant-Id
    idempotency-header: Idempotency-Key
    trust-proxy-headers: false # only enable behind a load balancer you control

  enforcement:                 # matched top to bottom, most specific first
    - path: /api/v1/payments/otp
      policy: otp-requests
    - path: /api/v1/payments/**
      policy: payments-standard

  policies:
    payments-standard:
      algorithm: TOKEN_BUCKET
      limit: 1700              # sustained rate: limit per window
      window: 1s
      burst: 2000              # instantaneous capacity; defaults to limit
      idempotency-ttl: 5m
```

A policy is always "`limit` requests per `window`". For the token bucket, `burst` adds capacity above that
sustained rate; it must be at least `limit`, since a capacity below the sustained rate would make the
`X-RateLimit-Limit` header a lie.

Override anything by environment variable, e.g. `REDIS_HOST`, `REDIS_PASSWORD`,
`RATELIMITER_FAILOPEN=false`.

---

## Design decisions

**Why Lua rather than `WATCH`/`MULTI` or a distributed lock.** Optimistic locking retries under contention,
which is exactly when a rate limiter is busiest; a lock adds a round trip and a liveness risk. A script is one
round trip, cannot interleave, and needs no coordination.

**Why the Redis clock.** Passing a client timestamp would make each instance's drift a correctness bug in
enforcement. `redis.call('TIME')` gives every instance one authoritative clock.

**Why hash tags in keys.** Keys are `rl:{subject}:tb`. Redis Cluster hashes only the braced portion, so a
subject's bucket and its idempotency markers always land in the same slot — without this, every multi-key
script would be rejected by a clustered deployment. `RateLimitKeys` also sanitises and length-bounds the
subject so a hostile header cannot break the tag or bloat memory.

**Why multiplexing instead of a connection pool.** Lettuce is thread-safe and pipelines commands over one
connection. For a workload that is entirely non-blocking `EVALSHA` calls, a pool adds borrow/return overhead
per request plus a new failure mode — exhaustion during a traffic spike, precisely when the limiter matters
most — without adding throughput.

**Why fail-open by default.** A rate limiter must not become a single point of failure for the traffic it
protects. When Redis is unreachable the request is admitted and the decision is tagged `degraded`, so the
outage is visible in metrics rather than silent. Operators who prefer hard enforcement set
`fail-open: false` and get a `503`.

**Why TTLs on every key.** A token bucket expires once it could only have refilled to full anyway; a window
expires one window after its last write. Memory stays proportional to *active* subjects rather than all-time
subjects — verified above as zero keys without a TTL. Redis is configured `maxmemory-policy volatile-ttl`,
which evicts only keys that already carry an expiry.

**Why `reset` leaves idempotency markers alone.** Clearing them would let an in-flight retry consume quota a
second time, defeating the guarantee.

---

## Testing

43 tests: fast unit tests plus Testcontainers-backed integration tests against a real Redis. The algorithms
live in Lua and depend on the server clock and genuine atomicity, so a mock would verify nothing that matters.

```bash
mvn test      # 14 unit tests, no Docker required
mvn verify    # + 29 integration tests (needs Docker)
```

Coverage worth calling out:

- **Atomicity** — 200 virtual threads released simultaneously against a 2-token bucket admit exactly 2. The
  same test against a 3-request window admits exactly 3.
- **Refill** — an exhausted bucket recovers on its own within the expected time.
- **No boundary burst** — a sliding window is still exhausted past its midpoint, where a fixed-window counter
  would have reset.
- **Idempotency** — repeated request ids replay the original verdict and never double-charge, in both
  algorithms and over HTTP.
- **Rule precedence** — a specific enforcement rule wins over the catch-all.
- **Isolation** — one caller exhausting its quota does not affect another, and `/actuator` stays reachable
  while a caller is throttled.

---

## Load testing

```bash
# Sustained 1,700 req/s for 60s (~102K/min) against the token bucket
k6 run loadtest/k6-payments.js
k6 run -e RPS=2500 -e BASE_URL=http://localhost:8081 loadtest/k6-payments.js
```

The k6 scenario uses a **constant arrival rate** rather than a fixed pool of virtual users. With closed-loop
VUs, slow responses quietly reduce the offered load and the limiter flatters itself; a constant arrival rate
keeps offered load at the target regardless of latency.

No k6 installed? A dependency-free burst demo (works on PowerShell 5.1 and 7+):

```powershell
./loadtest/burst-demo.ps1                                  # sliding window, throttles on the 4th
./loadtest/burst-demo.ps1 -Endpoint authorize -Requests 2500
```

---

## Observability

| Metric | Meaning |
|---|---|
| `ratelimiter.evaluation` | Decision latency incl. Redis round trip, tagged by policy and algorithm, with p50/p95/p99 |
| `ratelimiter.decisions` | Counter tagged `outcome=allowed\|blocked\|degraded` |

`degraded` is the one to alert on: it means enforcement was not actually applied.

```bash
curl 'http://localhost:8080/actuator/metrics/ratelimiter.decisions?tag=outcome:blocked'
docker exec rate-limiter-redis redis-cli info commandstats | grep evalsha
```

---

## Project structure

```
src/main/java/com/ayushjha/ratelimiter/
├── core/          Algorithm, RateLimitPolicy, RateLimitContext, RateLimitDecision,
│                  RateLimiterStrategy  ← the Strategy contract
├── strategy/      TokenBucketRateLimiter, SlidingWindowRateLimiter,
│                  RateLimiterStrategyRegistry  ← Singleton registry
├── policy/        PolicyCatalog  ← Singleton catalog, UnknownPolicyException
├── redis/         RateLimitKeys (cluster hash tags), ScriptOutcome
├── service/       RateLimiterService  ← fail-open, metrics
├── config/        RateLimiterProperties, RedisConfig, OpenApiConfig
└── web/           RateLimiterController, PolicyController,
                   PaymentAuthorizationController (guarded demo),
                   RateLimitHeaders, GlobalExceptionHandler,
                   filter/RateLimitFilter  ← 429 enforcement

src/main/resources/scripts/
├── token_bucket.lua        atomic, server-clock, idempotency-aware
└── sliding_window.lua      atomic, server-clock, idempotency-aware
```

---

## Production considerations

Deliberately in scope and handled: atomicity, clock authority, cluster-safe keys, TTL hygiene, fail-open,
idempotent replay, graceful shutdown, non-root container, bounded key length, RFC 9457 errors, health probes
and metrics.

Deliberately out of scope, and what I would add next:

- **Authentication.** The API is unauthenticated; the `X-API-Key` header identifies a caller for quota purposes
  but is not verified. A real deployment puts this behind a gateway or adds Spring Security.
- **Redis high availability.** Single node here. Production wants Sentinel or Cluster — the key design is
  already cluster-safe via hash tags.
- **A local pre-check layer.** For extreme volume, an in-process cache admitting an approximate share of the
  quota would cut Redis traffic, trading a little accuracy for a lot of load.
- **Sliding window counter.** A hybrid that approximates the log with two counters, for cases needing
  window accuracy at token-bucket memory cost.

---

## Tech stack

Java 25 (LTS) · Spring Boot 4.1.1 · Spring Data Redis 4.1.1 (Lettuce 7.5) · Redis 7.4 · Lua ·
Jackson 3 · Micrometer/Prometheus 1.17 · springdoc-openapi 3.1.0 · Testcontainers 2.0 · JUnit 5 ·
AssertJ · Docker & Compose · nginx · Maven

Running on current majors throughout, which required handling four breaking changes in the Spring Boot 4 line:

- **Jackson 3.** The auto-configured mapper moved from `com.fasterxml.jackson` to `tools.jackson`. The filter
  that writes 429 problem documents was updated accordingly. Jackson 2 is still on the classpath because
  swagger-core needs it; Boot 4 manages both BOMs deliberately for this reason.
- **Modular test slices.** `@AutoConfigureMockMvc` is no longer transitive from `spring-boot-starter-test`;
  it now lives in `spring-boot-webmvc-test` under `org.springframework.boot.webmvc.test.autoconfigure`.
- **Testcontainers 2.** Module artifacts are prefixed, so `junit-jupiter` became `testcontainers-junit-jupiter`.
- **springdoc 3.** The 2.x line targets Spring Boot 3; 3.x is required for Spring Framework 7.

## License
