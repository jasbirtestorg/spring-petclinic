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

package org.springframework.samples.petclinic.system;

import java.time.Duration;

import com.github.benmanes.caffeine.cache.Caffeine;

import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Cache configuration backed by Caffeine, an in-process cache.
 *
 * <p>
 * This application previously wired caching through the JCache (JSR-107) abstraction, but
 * no JCache provider was present on the classpath, so the cache manager was never created
 * and every {@code @Cacheable} call fell through to the database. Spring Boot's native
 * Caffeine support is used instead: Caffeine is already a dependency, it needs no extra
 * provider jar, and it is fully portable across cloud providers (no infrastructure
 * dependency such as Redis).
 * </p>
 *
 * <p>
 * Two caches are declared:
 * </p>
 * <ul>
 * <li>{@code vets} — the full veterinarian collection returned by
 * {@code VetRepository.findAll()}.</li>
 * <li>{@code vets_page} — the paginated veterinarian result returned by
 * {@code VetRepository.findAll(Pageable)}. It is kept separate from {@code vets} because
 * the two overloads return different value types ({@code Collection} vs {@code Page}) and
 * sharing a cache risks a key collision that would return the wrong type.</li>
 * </ul>
 *
 * <p>
 * Both caches are bounded (maximum size) and expire after a fixed window. Veterinarian
 * data is effectively static in this application (there is no write path for vets), so a
 * long TTL is safe; the bound and TTL still protect against unbounded growth and
 * guarantee eventual refresh. Statistics are recorded so cache hit ratios are visible via
 * {@code /actuator/metrics/cache.gets}.
 * </p>
 */
@Configuration(proxyBeanMethods = false)
@EnableCaching
class CacheConfiguration {

	/**
	 * Bounded, expiring, statistics-recording Caffeine specification applied to every
	 * cache created by the auto-configured Caffeine cache manager. Spring Boot's
	 * {@code CaffeineCacheConfiguration} detects this bean and uses it as the builder for
	 * all named caches. The cache names themselves are declared via
	 * {@code spring.cache.cache-names} in {@code application.properties}.
	 * @return the shared Caffeine builder
	 */
	@Bean
	Caffeine<Object, Object> caffeineCacheBuilder() {
		return Caffeine.newBuilder().maximumSize(1_000).expireAfterWrite(Duration.ofHours(24)).recordStats();
	}

}
