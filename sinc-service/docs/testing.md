# Running tests safely

Spring integration tests fail before context startup unless all of these test-only variables are set:

- `FIPELY_TEST_DB_ISOLATED=true` (explicit opt-in)
- `FIPELY_TEST_DB_URL`
- `FIPELY_TEST_DB_USERNAME`
- `FIPELY_TEST_DB_PASSWORD`
- `FIPELY_TEST_API_TOKEN`

They do not read production `FIPELY_DB_*` or `FIPELY_API_TOKEN`. The initializer rejects a database named
`fipely` and the default local `localhost:5432`/`127.0.0.1:5432` endpoints. Integration tests override the
FIPE URL with `FIPELY_TEST_FIPE_BASE_URL`; default is a deliberately unavailable localhost address, so no
FIPE calls can escape. Tests requiring a price seed a tiny fixture instead.

Example full-suite run using a new disposable PostgreSQL 17 container and a dynamically assigned loopback port
(run from `sinc-service`; this script removes only the container it creates):

```bash
set -euo pipefail
name="fipely-opencode-test-$(openssl rand -hex 4)"
db_user=fipely_test
db_password="$(openssl rand -hex 24)"
container=""
cleanup() { if [[ -n "$container" ]]; then docker rm -f "$container" >/dev/null 2>&1 || true; fi; }
trap cleanup EXIT
container=$(docker run -d --name "$name" \
  -e POSTGRES_USER="$db_user" -e POSTGRES_PASSWORD="$db_password" -e POSTGRES_DB=fipely_test \
  -p 127.0.0.1::5432 postgres:17)
until docker exec "$container" pg_isready -U "$db_user" -d fipely_test >/dev/null 2>&1; do sleep 1; done
port=$(docker port "$container" 5432/tcp | sed 's/.*://')
export FIPELY_TEST_DB_ISOLATED=true
export FIPELY_TEST_DB_URL="jdbc:postgresql://127.0.0.1:${port}/fipely_test"
export FIPELY_TEST_DB_USERNAME="$db_user" FIPELY_TEST_DB_PASSWORD="$db_password"
export FIPELY_TEST_API_TOKEN="test-$(openssl rand -hex 16)"
export FIPELY_TEST_FIPE_BASE_URL=http://127.0.0.1:9/api/veiculos
./mvnw -B clean verify
```
