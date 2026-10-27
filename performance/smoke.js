// Smoke test: minimal load, just prove the system and the script work end to end.
// Safe to run in CI and on a laptop. Does NOT assert the acceptance latency target.
import { sleep } from 'k6';
import { exploratoryThresholds } from './lib/config.js';
import { lightReadJourney } from './lib/journeys.js';

export const options = {
	vus: 1,
	iterations: 10,
	thresholds: exploratoryThresholds(),
};

export default function () {
	lightReadJourney();
	sleep(0.5);
}
