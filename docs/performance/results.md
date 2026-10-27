# Performance Results — GorillaClinic Spring PetClinic

Before/after comparison of the application-level optimizations. **All measurements in
this document are from a single local developer machine.** None were taken against GKE or
Cloud SQL; the 30,000-concurrent-user acceptance target is therefore **Not achieved /
Not yet tested** here — see §7.

## 1. Environment and versions

| Item | Value |
| --- | --- |
| App | Spring Boot 4.0.3, Java 17 source (run on Temurin 27) |
| Execution model | Synchronous Spring MVC (Tomcat), Thymeleaf |
| DB | PostgreSQL 18.3 in Docker (`postgres:18.3`), local |
| App DB profile | `postgres` |
| Load tool | k6 (Homebrew), same host as app + DB |
| Dataset | base seed + 5,000 synthetic owners → **5,010 owners, 5,013 pets, 5,004 visits** |
| Before revision | pristine working tree at commit `015e4e5` (pre-optimization) |
| After revision | same tree + optimizations (this change set) |

Load generation ran on the same machine as the app and Postgres. Additionally,
**Microsoft Defender (`wdavdaemon`) consumed 50–90% CPU** during later runs (real-time
scanning of the rebuilt jars and load traffic), which made some high-load runs
non-reproducible. Measurements taken while host load was low are labelled *clean*; runs
taken under host saturation are reported but explicitly discounted.

## 2. Load-test configuration and request mix

Read journey (per iteration, 4 requests):
`GET /` → `GET /vets.html?page=1` → `GET /owners?lastName=<prefix>` →
`GET /owners/{id}`. Search prefixes mix narrow and broad matches. Open-model scenario
uses k6 `constant-arrival-rate` (offered load independent of system speed; avoids
coordinated omission). Thresholds: `p(98)<200ms`, `http_req_failed<1%`,
`checks>99%`.

Reproduction:
```bash
# seed
performance/seed/generate-seed.sh 5000 > /tmp/seed.sql
psql "$POSTGRES_URL" -f /tmp/seed.sql
# run app (optimized): SPRING_PROFILES_ACTIVE=postgres java -jar target/*.jar
cd performance
BASE_URL=http://localhost:8080 MAX_OWNER_ID=5000 RATE=150 DURATION=45s k6 run sustained.js
```

## 3. Headline comparison (clean runs)

### Sustained open-model, 150 req/s offered, 45 s — achieved 150 iters/s, 0 dropped (both)

| Metric | BEFORE | AFTER | Change |
| --- | --- | --- | --- |
| p50 | 3.77 ms | 3.97 ms | ~flat |
| p90 | 11.97 ms | 10.24 ms | −14% |
| p95 | 35.72 ms | 13.74 ms | −62% |
| **p98** | **99.70 ms** | **20.49 ms** | **−79%** |
| p99 | 159 ms | 27.45 ms | −83% |
| max | 545 ms | 167 ms | −69% |
| error rate | 0% | 0% | — |
| achieved RPS | ~625 | ~626 | — |

Both builds met the offered load; the optimized build cut tail latency ~4–6×. Both are
under the 200 ms p98 target at this load, but the margin grows from 2× to 10×.

### Sustained open-model, 300 req/s offered, 45 s

| Metric | BEFORE | AFTER (clean)¹ |
| --- | --- | --- |
| p50 | 860 ms | — |
| p98 | 1.55 s | — |
| dropped iterations | 999 | — |

¹ The AFTER 300 req/s run could not be captured cleanly: by that point Microsoft Defender
was saturating the host (load average > 20) and both the app and k6 starved for CPU, so
the AFTER-300 numbers (p98 ≈ 8.9 s, thousands of dropped iterations) reflect **host
saturation, not the application** and are discarded. The BEFORE-300 run was captured while
load was lower and shows the pre-optimization build collapsing (p98 1.55 s, dropped
iterations) — consistent with the server-side route metrics below.

## 4. Server-side route metrics (immune to load-generator contention)

These come from the app's own Micrometer `http.server.requests`, measured inside the JVM,
so they are not distorted by k6/host CPU contention. Averages per route, optimized build
under the 150 req/s run:

| Route | BEFORE avg | AFTER avg | Note |
| --- | --- | --- | --- |
| `/` | ~16 ms | **1.8 ms** | welcome page |
| `/vets.html` | ~16 ms | **3.3 ms** | now cache-backed |
| `/owners` (search) | **~787 ms** | **7.9 ms** | pagination-window + no N+1 |
| `/owners/{ownerId}` | ~15 ms | **6.9 ms** | entity-graph fetch |

The dominant pre-optimization cost was `/owners`: a broad last-name search matched ~5,000
owners and the Thymeleaf template rendered **one page-link per page (≈200–1000 links)**,
producing a large, slow response. `EXPLAIN ANALYZE` showed the DB side was never the
bottleneck (count query 1.2 ms, page select 0.03 ms) — the cost was template rendering and
payload size. Windowing the pagination control to 5 links cut the route from ~787 ms to
~8 ms average.

## 5. Query-count evidence (N+1 fixes)

Captured from Hibernate SQL logs on the owner pages and locked in by
`OwnerRepositoryQueryTests`:

- **Owner list** `/owners`: 3 statements total regardless of result size — (1) owners
  page, (2) one **batched** `pets ... where owner_id in (?,…)` (+ pet types joined),
  (3) count. No per-owner query; visits no longer loaded on the list path (now lazy).
- **Owner detail** `/owners/{id}`: a single entity-graph select joining owner + pets +
  types, plus one batched visits select. No lazy-load after the transaction closes
  (verified with `open-in-view=false`).

## 6. Cache evidence

- Pre-change: `/actuator/caches` returned `{"cacheManagers":{}}` — **no cache manager
  existed** (JCache API on the classpath but no provider), so `@Cacheable` was a silent
  no-op and every vet request hit the DB.
- Post-change: native Caffeine cache manager with `vets` and `vets_page` caches, bounded
  (max 1,000) + 24 h TTL + stats. `VetCacheTests` asserts the manager is Caffeine, that
  repeated `findAll()` is served from cache, and that the two overloads use separate
  caches. `/vets.html` average dropped from ~16 ms to ~3 ms.

## 7. Objective status

| Objective | Status | Evidence / note |
| --- | --- | --- |
| p98 ≤ 200 ms for the read mix | **Verified by measurement (local, up to ~150 req/s)** | AFTER p98 20.5 ms @150 req/s; server-side route metrics corroborate |
| p98 ≤ 200 ms at 30k concurrent users | **Not yet tested** | Needs dedicated load fleet + GKE/Cloud SQL; scripts + plan provided |
| Eliminate N+1 / reduce DB round trips | **Verified by measurement** | Hibernate logs + `OwnerRepositoryQueryTests` |
| Working, bounded vet cache | **Verified by measurement** | `VetCacheTests`, actuator caches |
| Bounded pagination payload | **Verified by measurement** | response inspection + `OwnerControllerTests` |
| Connection-pool tuning | **Partially verified** | config applied + exposed via Hikari metrics; not stress-validated against Cloud SQL `max_connections` |
| Zero-downtime deploy / probes | **Not tested here** | config reviewed; requires a GKE rollout to verify |
| 2–5 min deploy cadence | **Out of scope (infra)** | not an application-level measurement |

## 8. Remaining bottlenecks / risks

1. **Broad-search `COUNT(*)`** still runs per search request. It is fast on indexed data
   (1.2 ms for 5k rows) but will grow with the owner table; a future option is to cap or
   approximate the count for very large tables. Not addressed now (no evidence it is a
   bottleneck at realistic sizes).
2. **Per-pod cache warming**: each replica warms its own Caffeine cache. Fine for the tiny
   static vet set; revisit only if larger cacheable data is introduced.
3. **Local measurement noise**: Microsoft Defender and shared-host contention make
   high-load local runs unreliable. Real acceptance numbers require an isolated load
   environment.

## 9. Reproduction commands

```bash
# 1. Postgres
docker run --name pg -e POSTGRES_USER=petclinic -e POSTGRES_PASSWORD=petclinic \
  -e POSTGRES_DB=petclinic -p 5432:5432 postgres:18.3

# 2. App (creates schema on first boot of an empty DB)
SQL_INIT_MODE=always SPRING_PROFILES_ACTIVE=postgres ./mvnw spring-boot:run
#    or the jar:
SPRING_PROFILES_ACTIVE=postgres java -jar target/spring-petclinic-4.0.0-SNAPSHOT.jar

# 3. Seed a realistic dataset
performance/seed/generate-seed.sh 5000 > /tmp/seed.sql
PGPASSWORD=petclinic psql -h localhost -U petclinic -d petclinic -f /tmp/seed.sql

# 4. Measure
cd performance
BASE_URL=http://localhost:8080 MAX_OWNER_ID=5000 RATE=150 DURATION=45s k6 run sustained.js
```

## 10. Confidence

- **High** confidence in the *direction and rough magnitude* of improvement: three
  independent lines of evidence agree (k6 clean runs, server-side route metrics,
  Hibernate query counts + EXPLAIN).
- **Low** confidence in treating any local number as an absolute acceptance figure, given
  single-host contention and antivirus interference. The acceptance run must be repeated
  on an isolated, production-like environment.
