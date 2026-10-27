// Baseline: low, steady closed-model load to capture a clean latency profile with no
// saturation. Reports full percentiles; applies the acceptance thresholds so a baseline
// that already violates p98 is visible immediately.
import { sleep } from 'k6';
import { VUS, DURATION, acceptanceThresholds } from './lib/config.js';
import { readJourney } from './lib/journeys.js';

export const options = {
	scenarios: {
		baseline: {
			executor: 'constant-vus',
			vus: VUS,
			duration: DURATION,
		},
	},
	thresholds: acceptanceThresholds(),
};

export default function () {
	readJourney();
	sleep(1);
}
