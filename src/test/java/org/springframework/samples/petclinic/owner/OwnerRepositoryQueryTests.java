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

package org.springframework.samples.petclinic.owner;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

/**
 * Regression tests that assert the <em>query behaviour</em> of {@link OwnerRepository},
 * not just that the data comes back correctly. These lock in the N+1 fixes:
 * <ul>
 * <li>the owner detail read ({@code findById}) loads the owner, its pets, their types,
 * and their visits in a bounded number of SQL statements via an entity graph, so the
 * Thymeleaf detail view never triggers a lazy load after the transaction closes
 * ({@code spring.jpa.open-in-view=false});</li>
 * <li>the owner list read ({@code findByLastNameStartingWith}) does not issue one query
 * per owner to fetch pets (Hibernate batch fetching keeps it bounded).</li>
 * </ul>
 * Hibernate {@link Statistics} is used to count the real SQL statements executed.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
class OwnerRepositoryQueryTests {

	@Autowired
	private OwnerRepository owners;

	@Autowired
	private EntityManagerFactory entityManagerFactory;

	@Autowired
	private EntityManager entityManager;

	private Statistics statistics;

	@BeforeEach
	void resetStatistics() {
		this.statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
		this.statistics.setStatisticsEnabled(true);
		this.statistics.clear();
	}

	@Test
	void findByIdLoadsFullOwnerGraphWithoutLazyAccessAfterClose() {
		Optional<Owner> found = this.owners.findById(1);
		assertThat(found).isPresent();
		Owner owner = found.get();

		// Detach everything so any un-fetched association would fail if touched.
		entityManager.clear();

		long queryCountAfterLoad = this.statistics.getPrepareStatementCount();

		// The detail view touches pets, pet types and visits. With the entity graph in
		// place these are already initialised, so touching them must NOT issue more SQL.
		assertThat(owner.getPets()).isNotEmpty();
		owner.getPets().forEach(pet -> {
			assertThat(pet.getType()).isNotNull();
			assertThat(pet.getType().getName()).isNotBlank();
			// visits collection is initialised even though the entity is detached
			pet.getVisits().forEach(visit -> assertThat(visit.getDate()).isNotNull());
		});

		assertThat(this.statistics.getPrepareStatementCount())
			.as("touching pets/type/visits on a detached owner must not trigger extra SQL")
			.isEqualTo(queryCountAfterLoad);

		// The whole graph is fetched in a small, bounded number of statements (a single
		// entity-graph select; allow a little slack for the SQL dialect).
		assertThat(queryCountAfterLoad).as("owner detail graph should load in a bounded query count")
			.isLessThanOrEqualTo(2);
	}

	@Test
	void findByLastNameDoesNotIssueOneQueryPerOwnerForPets() {
		Page<Owner> page = this.owners.findByLastNameStartingWith("", PageRequest.of(0, 5));
		assertThat(page).isNotEmpty();

		// Force pet names to be read (the list view renders them).
		page.forEach(owner -> owner.getPets().forEach(pet -> pet.getName()));

		long queries = this.statistics.getPrepareStatementCount();

		// Without batching this would be 1 (owners) + N (one pet query per owner). With
		// hibernate.default_batch_fetch_size it stays small and independent of N.
		assertThat(queries).as("owner list + pets should stay bounded, not scale with owner count")
			.isLessThanOrEqualTo(4);
	}

}
