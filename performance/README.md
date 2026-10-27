# PetClinic Performance Test Suite (k6)

Reproducible load tests for the Spring PetClinic application, written for
[k6](https://k6.io/). The suite models realistic PetClinic user journeys (browsing vets,
searching owners, viewing owner detail, and — optionally — write flows) rather than a
trivial health-check ping.

## Layout

| File | Purpose |
| --- | --- |
| `lib/config.js` | Shared, env-driven configuration (base URL, VUs, durations, mix). |
| `lib/journeys.js` | The reusable user-journey functions + HTTP checks. |
| `smoke.js` | 1 VU, a few iterations. Proves the system works. Safe in CI. |
| `baseline.js` | Low steady load. Captures a clean latency baseline. |
| `workload.js` | Realistic mixed read/write workload at a modest arrival rate. |
| `ramp.js` | Ramp VUs up and back down to watch latency vs. load. |
| `sustained.js` | Constant arrival rate held for a sustained period (open model). |
| `stress.js` | Pushes arrival rate up in stages to find the saturation point. |
| `spike.js` | Optional sudden burst to test recovery. |

All scenarios import thresholds from `lib/config.js`. The **p98 <= 200 ms** acceptance
threshold plus an error-rate threshold are applied to the read-mix scenarios
(`workload`, `sustained`, `ramp`).

## Why an open workload model

`sustained.js` and `stress.js` use k6's `constant-arrival-rate` / `ramping-arrival-rate`
executors. These push a fixed *offered load* (requests arriving per second) regardless of
how fast the system responds, so a slow system shows up as a growing gap between offered
and achieved throughput instead of silently throttling the generator. This avoids
*coordinated omission* — the classic trap of closed (per-VU loop) models where a slow
response simply delays the next request and hides tail latency. The closed-model
scenarios (`baseline`, `ramp`) are kept for VU-oriented views but the acceptance call is
made on the open-model runs.

## Terminology (do not conflate these)

- **VUs (virtual users):** concurrent execution contexts in k6.
- **Arrival rate:** requests started per second (open model), independent of VU count.
- **In-flight requests:** requests currently awaiting a response.
- **Throughput (RPS):** requests *completed* per second (achieved load).

30,000 *concurrent users* is a peak-concurrency figure. It is NOT the same as 30,000 RPS
or 30,000 open TCP connections. The scenarios below are parameterised so the target load
can be dialed in, but the 30,000-user acceptance run requires a dedicated, horizontally
scaled load-generation fleet and a production-like GKE + Cloud SQL target — see
`../docs/performance/runbook.md`.

## Configuration (environment variables)

| Var | Default | Meaning |
| --- | --- | --- |
| `BASE_URL` | `http://localhost:8080` | Target application base URL. |
| `VUS` | `10` | Virtual users (closed-model scenarios). |
| `RATE` | `50` | Arrival rate (iterations/sec) for open-model scenarios. |
| `DURATION` | `30s` | Steady-state duration. |
| `RAMP` | `30s` | Ramp-up duration. |
| `WRITE_RATIO` | `0` | Fraction (0–1) of iterations that perform a write journey. |
| `MAX_OWNER_ID` | `10` | Highest owner id to request (match your seeded dataset). |
| `P98_MS` | `200` | p98 latency threshold in ms (acceptance objective). |
| `ERROR_RATE` | `0.01` | Max allowed fraction of failed checks. |

## Running locally

Start the app (any profile; H2 is fine for a smoke test, Postgres for realistic runs):

```bash
# App must be running and reachable at BASE_URL first.
cd performance

# 1. Smoke — fast sanity check (safe to run anywhere, including CI)
k6 run smoke.js

# 2. Baseline — clean low-load latency
BASE_URL=http://localhost:8080 k6 run baseline.js

# 3. Realistic workload (reads only by default)
RATE=100 DURATION=1m k6 run workload.js

# 4. Realistic workload including writes (uses controlled test data)
WRITE_RATIO=0.1 RATE=100 DURATION=1m k6 run workload.js

# 5. Ramp
VUS=100 RAMP=1m DURATION=1m k6 run ramp.js

# 6. Sustained (open model)
RATE=200 DURATION=3m k6 run sustained.js

# 7. Stress — find the knee (open model, rising arrival rate)
k6 run stress.js

# 8. Spike (optional)
k6 run spike.js
```

### Against a dedicated test environment

```bash
BASE_URL=https://petclinic.staging.example.com \
  RATE=500 DURATION=5m P98_MS=200 ERROR_RATE=0.01 \
  k6 run sustained.js
```

## Write safety

Write journeys create **new** owners (and optionally pets/visits) with generated,
clearly-labelled test data (`k6-load-<timestamp>-<vu>-<iter>`). They never update or
delete existing records, so reruns only append throwaway rows. Point write tests at a
disposable/test database, never production. See the runbook for cleanup.

## Seeding a realistic dataset

The default seed data (10 owners) is far too small for a meaningful load test — every row
fits in cache and latency is unrealistically low. Use the seeding script to grow the
dataset before baseline/stress runs:

```bash
# Generates a large synthetic owner/pet/visit set as idempotent-ish SQL.
../performance/seed/generate-seed.sh 10000 > /tmp/petclinic-seed.sql
# then load it into your Postgres test DB, e.g.
psql "$POSTGRES_URL" -f /tmp/petclinic-seed.sql
```
