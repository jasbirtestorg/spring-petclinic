# Optimization Plan — GorillaClinic Spring PetClinic

Primary acceptance objective: **p98 <= 200 ms** for the agreed HTTP request mix.
Secondary: survive a 30,000-concurrent-user peak, zero-downtime deploys, 2–5 min deploy
cadence. This plan covers only **application-level** work (Java, Spring, JPA, SQL,
pooling, caching, config) and deliberately avoids a GKE/Cloud SQL infra redesign.

Changes are ranked by `(expected impact, confidence, effort, risk)` and implemented
highest-value-lowest-risk first. Each item records the bottleneck it addresses, the
expected benefit, the trade-off, how to test it, and how to roll it back.

## Ranked changes

### 1. Eliminate N+1 on the owner list/search — `@EntityGraph` fetch join
- **Addresses:** bottleneck #1 (owner list N+1).
- **Change:** add a `@EntityGraph(attributePaths = "pets")` to
  `OwnerRepository.findByLastNameStartingWith` so the page of owners and their pets load
  in one round trip instead of one query per owner (plus batched follow-ups).
  Pagination correctness is preserved because the collection is a `@OneToMany` loaded via
  a separate batched select under the hood only when a graph join would break the page
  count; an entity graph on a to-many with pagination makes Hibernate apply the limit in
  memory, so we keep page size small (5) and verify query counts in a test.
- **Benefit:** fewer SQL round trips per `/owners` request; most impactful read path.
- **Trade-off:** the `pets` collection is fetched eagerly for the page. Page size is 5,
  so the row multiplication is bounded.
- **Test:** `OwnerRepository` slice test asserting a single owner+pets fetch; existing
  `OwnerControllerTests` and `ClinicServiceTests` stay green.
- **Rollback:** remove the `@EntityGraph` annotation.

### 2. Make `Pet.visits` lazy (keep `Owner.pets` eager but fetched via graph)
- **Addresses:** bottleneck #1/#2 (visits eagerly loaded on every owner/pet read).
- **Change:** change `Pet.visits` from `FetchType.EAGER` to `FetchType.LAZY`. Visits are
  only needed on the owner-detail page, which renders inside a request where the session
  is open for that read; `default_batch_fetch_size=16` batches the visit loads. The owner
  **list** page does not render visits, so making visits lazy removes work from the hot
  list path entirely.
- **Benefit:** the list/search path stops loading visits; detail path still works.
- **Trade-off:** owner-detail now triggers a batched lazy load for visits. Verified by
  test that the detail page still renders visits correctly and `open-in-view=false` does
  not cause `LazyInitializationException` (the controller reads within the tx/render).
- **Risk:** `LazyInitializationException` if visits are accessed outside a session. The
  detail view accesses them during view rendering; we add a test that exercises
  `/owners/{id}` end to end to prove it.
- **Rollback:** revert fetch type to `EAGER`.

> Note: if item 2 proves to risk `LazyInitializationException` with
> `open-in-view=false` in the Thymeleaf render, the fallback is to keep visits eager but
> add `@EntityGraph(attributePaths = {"pets", "pets.visits"})` to `findById` for the
> detail path only. The implemented choice and its test result are recorded in
> `results.md`.

### 3. HikariCP tuning, reasoned against Cloud SQL capacity
- **Addresses:** bottleneck #3 (pool sizing / timeouts).
- **Change (in `application-postgres.properties`, all overridable by env):**
  - `maximum-pool-size`: keep small per pod and size against the DB. See capacity math
    below. Default proposed: **10** per pod, overridable via
    `${HIKARI_MAX_POOL_SIZE:10}`.
  - `minimum-idle` = maximum-pool-size (fixed-size pool, avoids ramp latency spikes).
  - `connection-timeout` = `${HIKARI_CONNECTION_TIMEOUT:2000}` (fail fast, surface
    saturation rather than hide it).
  - `max-lifetime` = `${HIKARI_MAX_LIFETIME:1800000}` (30 min, below typical server-side
    idle cutoffs and Cloud SQL Auth Proxy behavior).
  - `keepalive-time` = `${HIKARI_KEEPALIVE:120000}`.
  - `idle-timeout` = 0 when pool is fixed-size (ignored), documented.
  - `leak-detection-threshold` = `${HIKARI_LEAK_DETECTION:30000}` to catch connections
    held across non-DB work.
- **Benefit:** predictable behavior under load, fast failure on saturation, leak
  visibility, no connection storms from min-idle ramping.
- **Trade-off:** a fixed pool holds connections even when idle; justified because the
  per-pod pool is small and connections are a scarce DB resource we want pre-warmed.
- **Do NOT:** set an unbounded pool or very long connection-timeout to mask saturation.
- **Test:** app starts with the new props; an integration assertion that Hikari reports
  the configured max pool size via Actuator metric `hikaricp.connections.max`.
- **Rollback:** restore previous four properties.

#### Cloud SQL capacity math (documented, not hardcoded)
PostgreSQL enforces a global `max_connections`. Total app demand is
`replicas * maximum-pool-size` plus headroom for admin/migrations/other clients.

```
total_app_connections = replicas * per_pod_max_pool_size
```

Example: Cloud SQL `max_connections = 400`, reserve ~50 for ops → ~350 usable.
With `per_pod_max_pool_size = 10`, safe replica ceiling ≈ `350 / 10 = 35` pods.
To scale beyond that without raising `max_connections`, either lower per-pod pool size
or introduce a server-side pooler (PgBouncer) — called out as infra follow-up, not done
here. The Cloud SQL Auth Proxy is a secure transport shim, **not** a connection pool; it
does not reduce the connection count the database sees. This is stated so pool sizing is
never justified by "the proxy handles it."

### 4. Fix and bound the `vets` cache; separate the two overloads
- **Addresses:** bottleneck #4 (cache correctness + unbounded cache).
- **Change:**
  - Give the two `VetRepository.findAll` overloads **distinct cache names** (`vets` for
    the full collection, `vets_page` for the paginated variant) so a Collection value and
    a Page value never share a key space.
  - Configure Caffeine with explicit **maximum size** and **TTL** for both caches via
    `application.properties` (`spring.cache.caffeine.spec`) or a Caffeine customizer, and
    keep JCache statistics on.
  - Keep invalidation simple: vet data is effectively static in this app (no write path
    for vets), so a short TTL (e.g. 24h) plus bounded size is sufficient; documented.
- **Benefit:** correct, bounded, observable cache on the `/vets.html` and `/vets` hot
  reads; no stale-type collisions.
- **Trade-off:** TTL means a vet change (if ever added) is visible after TTL expiry;
  acceptable given no vet write path exists. Documented as a known staleness window.
- **Test:** cache test proving (a) second `findAll()` does not hit the repository/DB,
  (b) the two overloads use different caches, (c) cache respects max size.
- **Rollback:** restore single `@Cacheable("vets")` and remove Caffeine spec.

### 5. Production-safe Actuator exposure
- **Addresses:** bottleneck #5 (security) and observability.
- **Change:** replace `management.endpoints.web.exposure.include=*` with an explicit
  allow-list (`health,info,metrics,prometheus`) overridable by env. Keep probes
  (`/livez`, `/readyz`) working. Add `micrometer-registry-prometheus` so HTTP-by-route,
  JVM, GC, and HikariCP metrics are scrapeable.
- **Benefit:** no accidental exposure of `env`/`heapdump`/`threaddump`; route-templated
  latency metrics for correlating slow requests with pool/GC/CPU.
- **Trade-off:** developers lose the catch-all; documented how to re-enable locally.
- **Test:** context loads; `/actuator/prometheus` present, `/actuator/env` absent from
  the web exposure by default.
- **Rollback:** restore `include=*`.

### 6. Probe cost: keep liveness DB-free, keep readiness honest
- **Addresses:** bottleneck #6.
- **Change:** confirm liveness (`/livez`) maps to `livenessState` only (no DB), and that
  readiness (`/readyz`) includes `db` deliberately (a pod with no DB should not receive
  traffic). Document the probe periods so DB validation on readiness is not run at an
  excessive frequency. No code change if already correct; otherwise adjust the health
  group config. Ensure graceful shutdown remains enabled for rolling deploys.
- **Test:** `/livez` returns UP without a DB; `/readyz` reflects DB state.
- **Rollback:** restore previous health group config.

### 7. Postgres schema initialization bug (correctness; unblocks Postgres)
- **Addresses:** the 2 failing `PostgresIntegrationTests` and the real Postgres target.
- **Change:** allow schema/data init on Postgres where appropriate, controlled by env
  (`spring.sql.init.mode=${SQL_INIT_MODE:never}`) so throwaway/test and first-boot
  environments can create the schema, while production Cloud SQL (where the schema is
  provisioned out of band) keeps `never`. For the Testcontainers test specifically,
  initialize the schema so the suite passes. The SQL is already written idempotently
  (`CREATE TABLE IF NOT EXISTS`, `ON CONFLICT DO NOTHING`), so re-running is safe.
- **Benefit:** green test suite; reproducible Postgres bring-up for load testing.
- **Trade-off:** none when defaulted to `never` in prod; init only when explicitly
  enabled.
- **Test:** `PostgresIntegrationTests` passes.
- **Rollback:** restore `spring.sql.init.mode=never` literal.

### 8. Reduce log overhead on hot successful requests
- **Addresses:** bottleneck D (Spring/JVM hot path) — minor.
- **Change:** keep `logging.level.org.springframework=INFO` (already set); ensure no
  per-request DEBUG/SQL logging is on by default. No access logging added to the hot
  path. Container-aware JVM and GC defaults (JVM already container-aware on modern JDKs;
  documented in the runbook rather than hardcoding `-Xmx`).

## Explicitly NOT doing (and why)
- **No reactive / WebFlux migration, no virtual threads flip** without evidence the
  servlet model is the bottleneck. Premature per the brief.
- **No Redis / distributed cache.** The only cacheable stable data (vets) fits trivially
  in-process; a network cache would add latency and an infra dependency with no benefit.
- **No speculative indexes.** The existing schema already indexes `owners(last_name)`,
  `pets(owner_id)`, `pets(name)`, `visits(pet_id)`, `vets(last_name)` — exactly the
  predicates/joins the hot queries use. Adding more would cost write/storage with no read
  benefit for the current query set. Revisit only if `results.md` shows a specific plan
  needing it.
- **No framework major-version change.** Stay on Spring Boot 4.0.3 / Java 17.

## Verification gates
- Full `./mvnw verify` (format + checkstyle + tests) green.
- New regression/cache/repository tests green.
- k6 smoke + baseline pass thresholds locally against a seeded dataset.
- Before/after recorded in `results.md` with p50–p99, throughput, error rate.
