# ADR-0001: Application-level performance trade-offs for GorillaClinic PetClinic

- Status: Accepted
- Date: 2026-10-27
- Context: cloud landing-zone target — 30k concurrent-user peak, p98 <= 200 ms, zero
  downtime deploys, GKE + Cloud SQL (PostgreSQL) via Cloud SQL Auth Proxy, portable
  across clouds.

This ADR records the significant application-level decisions and their trade-offs. Infra
redesign (autoscaling, PgBouncer, CDN) is explicitly out of scope.

## Decision 1 — Fix fetch strategy instead of adding indexes or caching owner data
**Decision:** Add JPA entity graphs to the owner-detail read and rely on Hibernate batch
fetching for the owner-list read; make `Pet.visits` lazy.
**Why:** The dominant cost on read paths was round-trip count (N+1), not missing indexes.
The schema already indexes every predicate/join column used by the hot queries, so new
indexes would add write/storage cost with no read benefit.
**Trade-off:** `Pet.visits` lazy risks `LazyInitializationException` under
`open-in-view=false`. Mitigated by fetching visits via entity graph on the detail path
(the only view that renders them) and locking it in with a query-count regression test.
**Rejected alternative:** fetch-joining pets in the *paginated* list query — Hibernate
then paginates in memory (`HHH90003004`), which is worse at scale. Batch fetching keeps
the list bounded (~3 statements) without that penalty.

## Decision 2 — Native Caffeine cache, not JCache, not Redis
**Decision:** Replace the JCache abstraction with Spring Boot's native Caffeine support.
**Why:** The project declared `javax.cache:cache-api` and Caffeine core but **no JCache
provider**, so no cache manager was ever created and `@Cacheable` silently did nothing —
every vet request hit the DB. Caffeine core was already present; using it directly needs
no extra provider jar, is in-process (zero added latency, zero infra), and is portable.
**Trade-off:** In-process caches are per-pod (each replica warms its own). Acceptable: vet
data is tiny and static; a distributed cache would add a network hop and an infra
dependency for no measurable benefit.
**Rejected alternative:** Redis — unjustified for a few static rows; violates the
"no unnecessary infra dependency / stay portable" constraint.

## Decision 3 — Separate caches for the two `findAll` overloads
**Decision:** `vets` for the `Collection` overload, `vets_page` for the `Page` overload.
**Why:** Two overloads sharing one cache name can collide on key and return the wrong
value type.
**Trade-off:** Two small caches instead of one. Negligible.

## Decision 4 — Small, fixed, env-tunable HikariCP pool reasoned against Cloud SQL
**Decision:** `maximum-pool-size=minimum-idle=10` per pod (overridable), fast
`connection-timeout=2s`, `max-lifetime`, `keepalive`, and `leak-detection` set.
**Why:** DB connections are a scarce global resource; total demand is
`replicas * pool-size`. A fixed small pool gives predictable behavior and pre-warmed
connections; fast timeout surfaces saturation instead of hiding it.
**Trade-off:** Idle pods still hold connections (fixed pool). Accepted because per-pod
pool is small and connection establishment latency is removed from the hot path. Caps the
safe replica count at `usable_max_connections / pool_size` — documented in the runbook as
the scaling lever (lower pool, bigger tier, or PgBouncer).
**Rejected alternative:** large/unbounded pool — would exhaust Cloud SQL
`max_connections` across replicas and mask saturation.

## Decision 5 — Narrow Actuator exposure, add Prometheus
**Decision:** Expose only `health,info,metrics,prometheus` (env-overridable); add
`micrometer-registry-prometheus`.
**Why:** `include=*` published `env`, `heapdump`, `threaddump` etc. — a security problem.
Route-templated HTTP metrics plus Hikari/JVM/GC metrics are required to diagnose the p98
objective.
**Trade-off:** Developers lose the catch-all locally; re-enable with an env var.

## Decision 6 — Env-guarded Postgres schema init (bug fix)
**Decision:** `spring.sql.init.mode=${SQL_INIT_MODE:never}`; tests/first-boot set
`always`.
**Why:** The `postgres` profile previously had `mode=never` with `ddl-auto=none`, so a
fresh Postgres (including the Testcontainers integration test) had no schema — the test
suite failed and Postgres bring-up was broken. SQL is idempotent, so guarded init is safe.
**Trade-off:** None in prod (defaults to `never`; Cloud SQL schema is provisioned out of
band).

## Non-decisions (explicitly deferred)
- No reactive/WebFlux or virtual-threads switch (no evidence the servlet model is the
  bottleneck).
- No framework major-version change.
- No new indexes without a measured query plan that needs one.
- PgBouncer / autoscaling / CDN — infra, out of this application-scoped change.
