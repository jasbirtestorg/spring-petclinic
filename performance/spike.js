// Optional spike test: a sudden burst of arrival rate to see how the system absorbs and
// recovers from a traffic spike (autoscaling lag, pool saturation, queueing). Open model.
import { RATE, exploratoryThresholds } from './lib/config.js';
import { readJourney } from './lib/journeys.js';

export const options = {
	scenarios: {
		spike: {
			executor: 'ramping-arrival-rate',
			startRate: RATE,
			timeUnit: '1s',
			preAllocatedVUs: Math.max(RATE * 5, 100),
			maxVUs: Math.max(RATE * 50, 2000),
			stages: [
				{ duration: '30s', target: RATE },
				{ duration: '10s', target: RATE * 20 }, // sudden spike
				{ duration: '1m', target: RATE * 20 },
				{ duration: '10s', target: RATE }, // recover
				{ duration: '1m', target: RATE },
			],
		},
	},
	thresholds: exploratoryThresholds(),
};

export default function () {
	readJourney();
}
