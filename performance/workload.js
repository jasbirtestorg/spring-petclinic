// Realistic mixed workload using an OPEN model (constant arrival rate) so offered load
// is independent of system speed (avoids coordinated omission). A configurable fraction
// of iterations (WRITE_RATIO) performs a write journey; the rest are reads. This is the
// primary acceptance scenario: p98 <= P98_MS for the agreed mix.
import { RATE, DURATION, WRITE_RATIO, acceptanceThresholds } from './lib/config.js';
import { readJourney, writeJourney } from './lib/journeys.js';

export const options = {
	scenarios: {
		workload: {
			executor: 'constant-arrival-rate',
			rate: RATE,
			timeUnit: '1s',
			duration: DURATION,
			preAllocatedVUs: Math.max(RATE * 2, 50),
			maxVUs: Math.max(RATE * 10, 200),
		},
	},
	thresholds: acceptanceThresholds(),
};

export default function () {
	if (Math.random() < WRITE_RATIO) {
		writeJourney();
	}
	else {
		readJourney();
	}
}
