# Performance Baseline — GorillaClinic Spring PetClinic

Status: initial inspection captured before any optimization work.

## 1. Environment

| Item | Value |
| --- | --- |
| Date captured | 2026-10-27 |
| Git branch | `KiroSuggestions` |
| Git commit at baseline | `015e4e5` ("Actuator") |
| OS | macOS (darwin, arm64) |
| JDK present | Temurin 27 (build requires Java 17+, `maven-enforcer` pins minimum 17) |
| Build tool | Maven via `./mvnw` (Maven 3.9.12) |
| Load tooling available | `k6` (Homebrew), `docker` |

Note: all measurements in this document are from **local development** on a single
machine. They are explicitly NOT measurements against GKE + Cloud SQL and must not be
read as acceptance evidence for the 30,000-user / p98 <= 200 ms objective.

## 2. Application shape (derived from the repository)

- Spring Boot **4.0.3**, Java **17** source level, packaging a servlet (Tomcat) app.
- **Synchronous Spring MVC** (`spring-boot-starter-webmvc`), Thymeleaf server-side views.
  No reactive stack, no virtual threads configured.
- Persistence: Spring Data JPA / Hibernate. `spring.jpa.open-in-view=false` (good),
  `spring.jpa.properties.hibernate.default_batch_fetch_size=16` (mitigates N+1),
  `ddl-auto=none`.
- Caching: Spring Cache + Caffeine (JCache). One cache, `vets`, configured in
  `CacheConfiguration`.
- Actuator present; `management.endpoints.web.exposure.include=*` (all endpoints exposed).
- Datasource: HikariCP. Profiles: default **H2** (in-memory), `mysql`, `postgres`.

### Connection pool (profile `postgres`, `application-postgres.properties`)

```
spring.datasource.hikari.maximum-pool-size=5
spring.datasource.hikari.minimum-idle=1
spring.datasource.hikari.connection-timeout=3000
spring.datasource.hikari.validation-timeout=1000
```

No `max-lifetime`, `idle-timeout`, `keepalive-time`, or `leak-detection-threshold` set.

## 3. Endpoints and user journeys (from controllers)

| Route | Method | Controller | Notes |
| --- | --- | --- | --- |
| `/` | GET | `WelcomeController` | Static welcome view |
| `/vets.html` | GET | `VetController` | Paginated vet list (HTML), page size 5 |
| `/vets` | GET | `VetController` | All vets as JSON/XML (`@ResponseBody`) |
| `/owners/find` | GET | `OwnerController` | Search form |
| `/owners` | GET | `OwnerController` | Search by last name, paginated, page size 5 |
| `/owners/new` | GET/POST | `OwnerController` | Create owner |
| `/owners/{id}` | GET | `OwnerController` | Owner + pets + visits detail |
| `/owners/{id}/edit` | GET/POST | `OwnerController` | Update owner |
| `/owners/{ownerId}/pets/new` | GET/POST | `PetController` | Create pet |
| `/owners/{ownerId}/pets/{petId}/edit` | GET/POST | `PetController` | Update pet |
| `/owners/{ownerId}/pets/{petId}/visits/new` | GET/POST | `VisitController` | Add visit |
| `/oups` | GET | `CrashController` | Intentional 500 (demo) |

Read-heavy hot paths: `/` , `/vets.html`, `/owners` (search/list), `/owners/{id}`.
Write paths: owner/pet/visit create & update (all go through `owners.save(owner)`).

## 4. Known / suspected bottlenecks (static analysis, pre-measurement)

Ranked by expected impact; confidence noted. Measurement in `results.md`.

1. **N+1 on the owner list / search (`/owners`).** HIGH impact, HIGH confidence.
   `Owner.pets` is `@OneToMany(fetch = EAGER)` and `Pet.visits` is
   `@OneToMany(fetch = EAGER)`. `OwnerRepository.findByLastNameStartingWith` is a
   derived query with **no `JOIN FETCH`**. For a page of N owners, Hibernate issues the
   owner query then additional queries to hydrate pets and visits. `default_batch_fetch_size=16`
   batches these but does not eliminate the extra round trips. Upstream PetClinic uses an
   explicit `@Query("... LEFT JOIN FETCH owner.pets ...")`; it is absent here.

2. **`/owners/{id}` detail loads full entity graph eagerly.** MEDIUM impact, HIGH confidence.
   `findById` pulls owner → pets → visits via eager associations (extra round trips per
   request). Fine for a single owner but multiplied under load.

3. **HikariCP pool sized at 5 per pod with aggressive 3 s connection timeout.**
   MEDIUM impact, HIGH confidence. Under high concurrency a pool of 5 saturates quickly
   and callers block up to 3 s then fail. No `max-lifetime`/`leak-detection`. Pool sizing
   is not reasoned against Cloud SQL `max_connections` or replica count — see
   `optimization-plan.md`.

4. **`vets` cache correctness smell.** MEDIUM impact, MEDIUM confidence.
   `VetRepository` annotates **two overloads** — `findAll()` (Collection) and
   `findAll(Pageable)` (Page) — with the same `@Cacheable("vets")`. They produce
   different value types but share a cache; default key generation can collide
   (e.g. a no-arg call and a call whose single arg hashes to the empty key) and there is
   no size limit or TTL on the `vets` cache. Needs separation + bounded config.

5. **Actuator fully exposed (`include=*`).** LOW perf impact, SECURITY impact, HIGH confidence.
   Exposes `env`, `heapdump`, `threaddump`, etc. publicly. Must be narrowed for prod.

6. **Health/readiness probe DB coupling.** LOW/MEDIUM impact, MEDIUM confidence.
   Readiness group includes `db`, so each readiness probe runs a DB validation query.
   At high probe frequency across many pods this adds avoidable DB load; liveness must
   never depend on the DB (it already uses `livenessState` via `/livez`).

7. **Tiny seed dataset.** Not a bottleneck but a measurement blocker: 10 owners,
   13 pets, 4 visits, 6 vets. A realistic load test needs a larger synthetic dataset,
   otherwise everything fits in buffer cache and latency numbers are meaningless.

## 5. Baseline test run

Command:

```bash
./mvnw -q test
```

Result: **59 tests run, 0 failures, 2 errors** (both in `PostgresIntegrationTests`).

All unit/slice tests, H2 integration tests (`PetClinicIntegrationTests`), and MySQL
Testcontainers tests pass. The two errors:

```
PostgresIntegrationTests.findAll   -> relation "vets" does not exist
PostgresIntegrationTests.ownerDetails -> 500, relation "owners" does not exist
```

### Root cause (pre-existing bug, not performance-related)

`PostgresIntegrationTests` starts a throwaway Postgres (Spring Docker Compose, service
`postgres` in `docker-compose.yml`) and runs with the `postgres` profile. That profile
sets `spring.sql.init.mode=never` and the app uses `spring.jpa.hibernate.ddl-auto=none`,
so **nothing ever creates the schema** in the fresh container → every query hits a
missing relation. The `mysql` profile works because it sets `spring.sql.init.mode=always`.

This is a genuine defect on the Postgres target (the production database family) and is
fixed as part of this work (see `optimization-plan.md`, item "Postgres schema init"),
guarded so it does not re-run DDL against an already-provisioned Cloud SQL instance.

## 6. Baseline latency measurement

Not yet collected at the moment of writing this file. A reproducible k6 suite
(`performance/`) and a larger synthetic dataset are added as part of this work, and the
measured before/after numbers are recorded in `docs/performance/results.md`. Averages
alone are not treated as acceptance evidence; p50/p90/p95/p98/p99, throughput, and error
rate are reported.

### Toolchain note (environment)

The build targets Java 17 but the machine runs Temurin **27**. JaCoCo 0.8.14 cannot
instrument class-file major version 71 (JDK 27), so `./mvnw verify` prints a JaCoCo
"Unsupported class file major version 71" warning. It does not fail tests; on the intended
Java 17 toolchain it does not occur. Run tests with `-Djacoco.skip=true` to silence it
locally. Also note the Testcontainers/Docker-Compose Postgres test binds host port 5432 —
stop any local Postgres on 5432 (e.g. a load-test container) before running the suite.

## 7. Limitations that prevent a reliable acceptance baseline

- No access to GKE or Cloud SQL from this environment; all runs are local single-node.
- The 30,000 concurrent-user acceptance target cannot be exercised here. The k6 scripts
  and an execution plan are provided; that specific target remains **Not yet tested**.
- Local load generation competes with the app for CPU on the same host, which inflates
  latency versus a dedicated load generator. Documented in `results.md`.
