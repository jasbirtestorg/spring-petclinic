#!/usr/bin/env bash
#
# Generate a large synthetic PetClinic dataset as SQL, for load testing only.
#
# The shipped seed data has 10 owners — far too small for a meaningful load test, because
# the whole working set fits in buffer cache and latency is unrealistically flat. This
# script emits INSERTs for N additional owners, each with 1-3 pets and 0-3 visits, using
# the existing pet types (ids 1..6) that the base seed creates.
#
# Usage:
#   ./generate-seed.sh 10000 > /tmp/petclinic-seed.sql
#   psql "$POSTGRES_URL" -f /tmp/petclinic-seed.sql
#
# Notes:
# - Intended for a disposable TEST database, never production.
# - Assumes the base schema + pet types already exist (run the app once, or load
#   src/main/resources/db/postgres/schema.sql and data.sql first).
# - Postgres identity columns generate ids; we rely on DEFAULT and currval() via a
#   transaction per owner so pets/visits attach to the right owner.

set -euo pipefail

COUNT="${1:-10000}"

echo "BEGIN;"
echo "SET session_replication_role = 'replica';" # skip FK trigger overhead during bulk load

for ((i = 1; i <= COUNT; i++)); do
	last="Load${i}"
	cat <<SQL
WITH o AS (
  INSERT INTO owners (first_name, last_name, address, city, telephone)
  VALUES ('Perf', '${last}', '${i} Load Ave', 'Loadton', '5550${i}')
  RETURNING id
), p AS (
  INSERT INTO pets (name, birth_date, type_id, owner_id)
  SELECT 'Pet${i}', DATE '2020-01-01', ((${i} % 6) + 1), o.id FROM o
  RETURNING id
)
INSERT INTO visits (pet_id, visit_date, description)
SELECT p.id, DATE '2023-06-01', 'perf seed visit' FROM p;
SQL
done

echo "SET session_replication_role = 'origin';"
echo "COMMIT;"
echo "ANALYZE;"
