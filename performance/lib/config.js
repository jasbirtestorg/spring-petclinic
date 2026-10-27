// Shared, environment-driven configuration for the PetClinic k6 suite.
// Every tunable is read from an environment variable with a sensible default so the same
// scripts run as a developer smoke test and as a full load test without edits.

export const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

// Closed-model knobs
export const VUS = parseInt(__ENV.VUS || '10', 10);

// Open-model knobs (requests started per second)
export const RATE = parseInt(__ENV.RATE || '50', 10);

export const DURATION = __ENV.DURATION || '30s';
export const RAMP = __ENV.RAMP || '30s';

// Fraction (0..1) of iterations that run a write journey instead of a read journey.
export const WRITE_RATIO = parseFloat(__ENV.WRITE_RATIO || '0');

// Highest owner id to request on read journeys. Match this to your seeded dataset.
export const MAX_OWNER_ID = parseInt(__ENV.MAX_OWNER_ID || '10', 10);

// Acceptance thresholds.
export const P98_MS = parseInt(__ENV.P98_MS || '200', 10);
export const ERROR_RATE = parseFloat(__ENV.ERROR_RATE || '0.01');

// Standard threshold block applied to acceptance-grade read scenarios. Latency is
// evaluated as p98 <= P98_MS; correctness via a custom "errors" rate and http failure
// rate. Percentiles p50/p90/p95/p99 are also reported for the before/after comparison.
export function acceptanceThresholds() {
	return {
		http_req_duration: [
			'p(50)<' + P98_MS,
			`p(98)<${P98_MS}`,
			'p(99)<' + P98_MS * 2,
		],
		http_req_failed: [`rate<${ERROR_RATE}`],
		checks: [`rate>${1 - ERROR_RATE}`],
	};
}

// Looser thresholds for smoke/stress where we are exploring, not asserting acceptance.
export function exploratoryThresholds() {
	return {
		http_req_failed: ['rate<0.05'],
	};
}
