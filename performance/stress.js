// Stress test: ramp the ARRIVAL RATE up in stages to find the saturation point (the
// "knee" where latency explodes and/or errors appear). Open model so the generator keeps
// offering load even as the system slows. Exploratory thresholds only — the point is to
// discover limits, not to pass/fail acceptance.
import { RATE, exploratoryThresholds } from './lib/config.js';
import { readJourney } from './lib/journeys.js';

const peak = RATE * 10;

export const options = {
	scenarios: {
		stress: {
			executor: 'ramping-arrival-rate',
			startRate: RATE,
			timeUnit: '1s',
			preAllocatedVUs: Math.max(RATE * 5, 100),
			maxVUs: Math.max(peak * 5, 1000),
			stages: [
				{ duration: '1m', target: RATE },
				{ duration: '2m', target: Math.round(peak / 2) },
				{ duration: '2m', target: peak },
				{ duration: '1m', target: 0 },
			],
		},
	},
	thresholds: exploratoryThresholds(),
};

export default function () {
	readJourney();
}
