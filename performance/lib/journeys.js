// Reusable PetClinic user journeys for the k6 suite.
//
// Routes and methods are taken from the application's controllers:
//   GET  /                                  welcome page
//   GET  /vets.html                         paginated vet list (HTML)
//   GET  /vets                              all vets (JSON)
//   GET  /owners/find                       search form
//   GET  /owners?lastName=&page=            owner search / list
//   GET  /owners/{id}                       owner detail (pets + visits)
//   GET  /owners/new   POST /owners/new     create owner
//   POST /owners/{id}/pets/new              create pet
//   POST /owners/{id}/pets/{petId}/visits/new  create visit
//
// Read journeys are the latency-sensitive hot paths. Write journeys create brand-new,
// clearly-labelled throwaway records and never mutate existing data.

import http from 'k6/http';
import { check, group } from 'k6';
import { Rate } from 'k6/metrics';
import { BASE_URL, MAX_OWNER_ID } from './config.js';

export const errors = new Rate('errors');

function get(path, name) {
	const res = http.get(`${BASE_URL}${path}`, { tags: { name } });
	const ok = check(res, {
		[`${name} status 2xx/3xx`]: (r) => r.status >= 200 && r.status < 400,
	});
	errors.add(!ok);
	return res;
}

function randomOwnerId() {
	return 1 + Math.floor(Math.random() * MAX_OWNER_ID);
}

// A pool of last-name prefixes used for owner search. Mix of prefixes that match a
// subset (realistic user behaviour) rather than only the pathological empty search that
// matches every owner and renders a huge pagination control.
const SEARCH_PREFIXES = ['Load1', 'Load2', 'Load3', 'Davis', 'Franklin', 'Load'];

function randomPrefix() {
	return SEARCH_PREFIXES[Math.floor(Math.random() * SEARCH_PREFIXES.length)];
}

// A typical read-only visit: land on the home page, browse vets, search owners, open a
// specific owner's detail page.
export function readJourney() {
	group('read', () => {
		get('/', 'home');
		get('/vets.html?page=1', 'vets_list');
		get(`/owners?lastName=${randomPrefix()}`, 'owners_search');
		get(`/owners/${randomOwnerId()}`, 'owner_detail');
	});
}

// Lighter read used by smoke/baseline to keep the mix representative but cheap.
export function lightReadJourney() {
	group('light_read', () => {
		get('/', 'home');
		get(`/owners/${randomOwnerId()}`, 'owner_detail');
	});
}

// A write journey: create a new owner with uniquely-labelled test data. PetClinic uses
// server-rendered forms with a redirect-after-POST, so we post form-encoded fields.
export function writeJourney() {
	group('write_owner', () => {
		const stamp = `${Date.now()}-${__VU}-${__ITER}`;
		const payload = {
			firstName: 'Load',
			lastName: `k6-load-${stamp}`,
			address: '1 Test Way',
			city: 'Testville',
			telephone: '0000000000',
		};
		const res = http.post(`${BASE_URL}/owners/new`, payload, {
			tags: { name: 'owner_create' },
		});
		const ok = check(res, {
			'owner_create redirected/ok': (r) => r.status >= 200 && r.status < 400,
		});
		errors.add(!ok);
	});
}
