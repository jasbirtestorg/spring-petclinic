# Performance Runbook — GorillaClinic Spring PetClinic

Operational guide for running the performance suite, reading the metrics, and sizing the
application for the GKE + Cloud SQL target.

## 1. Run the app for load testing

### Local, H2 (quick, not representative of prod DB)
```bash
./mvnw spring-boot:run
# serves on http://localhost:8080
```

### Local, Postgres (representative; use for baseline/stress)
```bash
# Start a disposable Postgres
docker run --rm -e POSTGRES_USER=petclinic -e POSTGRES_PASSWORD=petclinic \
  -e POSTGRES_DB=petclinic -p 5432:5432 postgres:18.3 &

# Create schema+seed on first boot of a fresh DB (idempotent SQL):
SQL_INIT_MODE=always SPRING_PROFILES_ACTIVE=postgres ./mvnw spring-boot:run

# Grow the dataset to something load-test-worthy:
performance/seed/generate-seed.sh 10000 > /tmp/seed.sql
PGPASSWORD=petclinic psql -h localhost -U petclinic -d petclinic -f /tmp/seed.sql
# Then set MAX_OWNER_ID to roughly (10 + 10000) when running read journeys.
```

### Production artifact
```bash
./mvnw -DskipTests package
java -jar target/*.jar --spring.profiles.active=postgres
```

## 2. Run the k6 suite

See `performance/README.md` for the full matrix. Quick reference:

```bash
cd performance
k6 run smoke.js                                   # sanity
BASE_URL=http://localhost:8080 k6 run baseline.js # clean latency
RATE=200 DURATION=3m k6 run sustained.js          # acceptance-style (open model)
k6 run stress.js                                  # find saturation
WRITE_RATIO=0.1 RATE=100 k6 run workload.js       # mixed read/write
```

Key knobs: `BASE_URL`, `RATE` (arrival/sec), `VUS`, `DURATION`, `RAMP`, `WRITE_RATIO`,
`MAX_OWNER_ID`, `P98_MS`, `ERROR_RATE`.

## 3. Metrics to watch (and where)

Actuator exposes `health,info,metrics,prometheus` by default (configurable via
`MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE`). Scrape `/actuator/prometheus` or query
`/actuator/metrics/<name>`.

| Concern | Metric(s) |
| --- | --- |
| HTTP latency by route template | `http.server.requests` (tags: `uri`, `status`, `method`) |
| Connection pool saturation | `hikaricp.connections.active`, `.idle`, `.pending`, `.max`, `hikaricp.connections.acquire`, `hikaricp.connections.timeout` |
| Cache effectiveness | `cache.gets{result=hit|miss}`, `cache.size` (caches `vets`, `vets_page`) |
| JVM heap | `jvm.memory.used`, `jvm.memory.max` (area=heap) |
| GC pauses | `jvm.gc.pause` (count + max) |
| CPU | `system.cpu.usage`, `process.cpu.usage` |
| Threads / queueing | `jvm.threads.live`, Tomcat `tomcat.threads.busy`, `tomcat.threads.config.max` |

`http.server.requests` is tagged by **route template** (`/owners/{ownerId}`), not raw URL,
so metric cardinality stays bounded.

### Correlating a slow request
1. `http.server.requests` p98 by `uri` → which route is slow.
2. If one DB-backed route is slow, check `hikaricp.connections.pending` and
   `hikaricp.connections.acquire` → pool saturation / acquisition latency.
3. Check `jvm.gc.pause` max and `process.cpu.usage` → GC or CPU saturation.
4. If `/vets*` is slow, check `cache.gets{result=miss}` climbing → cache not warming.

## 4. Connection-pool sizing for GKE + Cloud SQL

Every pod runs its own HikariCP pool. The database sees:
```
total_connections = replicas * HIKARI_MAX_POOL_SIZE   (+ ops/migration headroom)
```
The Cloud SQL Auth Proxy is a secure transport, **not** a pool — it does not reduce this
count. Procedure:

1. Find Cloud SQL `max_connections` (tier-dependent; e.g. 400).
2. Reserve headroom for admin/migrations/other clients (e.g. 50).
3. `usable = max_connections - headroom`.
4. `max_replicas = floor(usable / HIKARI_MAX_POOL_SIZE)`.

Defaults here: `HIKARI_MAX_POOL_SIZE=10`. With usable=350 → ~35 pods safe. To go beyond:
lower per-pod pool, raise `max_connections` (bigger tier), or introduce PgBouncer
(infra follow-up, not in this change). Override per environment:
```
HIKARI_MAX_POOL_SIZE, HIKARI_MIN_IDLE, HIKARI_CONNECTION_TIMEOUT,
HIKARI_MAX_LIFETIME, HIKARI_KEEPALIVE, HIKARI_LEAK_DETECTION
```

## 5. JVM / container settings

Modern JDK (17+) is container-aware and sizes the heap from the cgroup memory limit by
default — do **not** hardcode `-Xmx` in the image. Set the pod memory limit and
optionally `-XX:MaxRAMPercentage=75.0`. Ensure CPU requests/limits match observed
`process.cpu.usage` under load (measure with the stress scenario before setting limits).

## 6. Probes and zero-downtime deploys

- Liveness `/livez` → `livenessState` only (no DB). A DB blip must not restart pods.
- Readiness `/readyz` → `readinessState,db`. A pod with no DB is pulled from the Service.
  Keep the readiness probe period reasonable (e.g. 10s), not sub-second, so the DB
  validation it performs is not run at excessive frequency across many pods.
- Spring Boot graceful shutdown + a preStop delay let in-flight requests drain during a
  rolling deploy, supporting the "no user-visible interruption" and 2–5 min deploy goals.

## 7. Rollback

Each optimization is independently revertible — see `optimization-plan.md` "Rollback"
notes per item and the ADR. The highest-risk change (making `Pet.visits` lazy) is covered
by `OwnerRepositoryQueryTests`; if a `LazyInitializationException` ever appears in prod
logs for `/owners/{id}`, revert `Pet.visits` to `FetchType.EAGER` and redeploy.
