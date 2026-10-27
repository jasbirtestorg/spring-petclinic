// Sustained load: hold a constant arrival rate for a longer period to surface issues
// that only appear over time (connection-pool exhaustion, GC pressure, memory growth,
// cache churn). Open model; applies acceptance thresholds.
import { RATE, DURATION, acceptanceThresholds } from './lib/config.js';
import { readJourney } from './lib/journeys.js';

export const options = {
	scenarios: {
		sustained: {
			executor: 'constant-arrival-rate',
			rate: RATE,
			timeUnit: '1s',
			duration: DURATION,
			preAllocatedVUs: Math.max(RATE * 2, 50),
			maxVUs: Math.max(RATE * 10, 500),
		},
	},
	thresholds: acceptanceThresholds(),
};

export default function () {
	readJourney();
}
