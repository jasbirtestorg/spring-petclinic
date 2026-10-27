// Ramp-up test: raise VUs from 0 to VUS over RAMP, hold for DURATION, then ramp down.
// Useful to watch how latency degrades as concurrency climbs.
import { sleep } from 'k6';
import { VUS, DURATION, RAMP, acceptanceThresholds } from './lib/config.js';
import { readJourney } from './lib/journeys.js';

export const options = {
	scenarios: {
		ramp: {
			executor: 'ramping-vus',
			startVUs: 0,
			stages: [
				{ duration: RAMP, target: VUS },
				{ duration: DURATION, target: VUS },
				{ duration: RAMP, target: 0 },
			],
		},
	},
	thresholds: acceptanceThresholds(),
};

export default function () {
	readJourney();
	sleep(0.5);
}
