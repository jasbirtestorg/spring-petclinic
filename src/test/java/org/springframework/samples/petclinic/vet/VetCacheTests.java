/*
 * Copyright 2012-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.samples.petclinic.vet;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

/**
 * Verifies that veterinarian reads are actually cached.
 * <p>
 * The application previously wired caching through JCache but shipped no JCache provider,
 * so no cache manager was created and every {@code @Cacheable} call fell through to the
 * database. This test fails if that regression returns: it asserts a real Caffeine cache
 * manager exists, that repeated {@code findAll()} calls are served from the cache, and
 * that the two {@code findAll} overloads use separate caches ({@code vets} and
 * {@code vets_page}) so their different value types cannot collide.
 */
@SpringBootTest
class VetCacheTests {

	@Autowired
	private VetRepository vets;

	@Autowired
	private CacheManager cacheManager;

	@BeforeEach
	void clearCaches() {
		cacheManager.getCacheNames().forEach(name -> {
			Cache cache = cacheManager.getCache(name);
			if (cache != null) {
				cache.clear();
			}
		});
	}

	@Test
	void cacheManagerIsCaffeineAndNotANoOp() {
		assertThat(cacheManager).isInstanceOf(CaffeineCacheManager.class);
		assertThat(cacheManager.getCacheNames()).contains("vets", "vets_page");
	}

	@Test
	void findAllCollectionIsServedFromTheVetsCache() {
		Cache vetsCache = cacheManager.getCache("vets");
		assertThat(vetsCache).isNotNull();
		assertThat(vetsCache.get(org.springframework.cache.interceptor.SimpleKey.EMPTY)).isNull();

		this.vets.findAll();

		// After the first call the collection must be cached under the empty key.
		assertThat(vetsCache.get(org.springframework.cache.interceptor.SimpleKey.EMPTY))
			.as("findAll() result should be cached in the 'vets' cache")
			.isNotNull();

		// A second call returns the identical cached instance (no new fetch).
		assertThat(this.vets.findAll()).isSameAs(this.vets.findAll());
	}

	@Test
	void paginatedFindAllUsesASeparateCache() {
		Cache vetsCache = cacheManager.getCache("vets");
		Cache vetsPageCache = cacheManager.getCache("vets_page");
		assertThat(vetsPageCache).isNotNull();

		Page<Vet> page = this.vets.findAll(PageRequest.of(0, 5));
		assertThat(page).isNotNull();

		// The page result lands in vets_page, never in vets.
		assertThat(vetsPageCache.get(PageRequest.of(0, 5))).isNotNull();
		assertThat(vetsCache.get(org.springframework.cache.interceptor.SimpleKey.EMPTY)).isNull();
	}

}
