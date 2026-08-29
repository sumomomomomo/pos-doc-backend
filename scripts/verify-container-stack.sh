#!/bin/sh
set -eu

# Whole-stack verification for Task 2.
#
# Builds the backend image, starts the dedicated Compose stack, verifies
# health, the Task 1 dummy endpoint, SQLite persistence, and MinIO
# persistence across restarts. The cleanup trap removes only this script's
# own containers and volumes (project name pos-doc-task2-test) and the
# temporary environment file. It never runs an unscoped compose down or any
# system prune.

PROJECT_NAME="pos-doc-task2-test"
# The plan mandates the fixed project name. The refusal check below filters on
# label com.docker.compose.project=${PROJECT_NAME} (the label Docker Compose
# actually stamps on its containers), so the actual project must equal that
# fixed name for the guarantee to hold (a per-PID suffix would defeat it).
STACK_ID="${PROJECT_NAME}"
ENV_FILE=""

# --- prerequisite checks -----------------------------------------------------

command -v docker >/dev/null 2>&1 || {
    echo "ERROR: docker is not available on PATH." >&2
    exit 1
}
docker compose version >/dev/null 2>&1 || {
    echo "ERROR: docker compose is not available." >&2
    exit 1
}
command -v curl >/dev/null 2>&1 || {
    echo "ERROR: curl is not available on PATH." >&2
    exit 1
}

# --- refuse to reuse an existing stack ---------------------------------------

EXISTING="$(docker ps -a --filter "label=com.docker.compose.project=${PROJECT_NAME}" --format '{{.Names}}' | head -n 1 || true)"
if [ -n "${EXISTING}" ]; then
    echo "ERROR: a container for compose project '${PROJECT_NAME}' already exists (${EXISTING})." >&2
    echo "Refusing to start over an existing stack; run its own cleanup first." >&2
    exit 1
fi

# --- temporary environment file ----------------------------------------------

ENV_FILE="$(mktemp "${TMPDIR:-/tmp}/pos-doc-task2-test-env.XXXXXX")"
cat > "${ENV_FILE}" <<'EOF'
MINIO_ROOT_USER=task2-test-access
MINIO_ROOT_PASSWORD=task2-test-secret-change-me
MINIO_BUCKET=pos-documents-test
EOF

cleanup() {
    docker compose --env-file "${ENV_FILE}" -p "${PROJECT_NAME}" down --volumes --remove-orphans >/dev/null 2>&1 || true
    if [ -n "${ENV_FILE}" ]; then
        rm -f "${ENV_FILE}"
        ENV_FILE=""
    fi
}
trap cleanup EXIT INT TERM

# --- helper: bounded wait for a URL (no arbitrary sleeps to declare readiness)
#
# The waits below poll a health endpoint; the interval is small and the
# attempt count is bounded. Readiness of the stack itself is declared by
# `docker compose up --wait` (Compose health state), not by these probes.

wait_for_url() {
    url="$1"
    attempts="$2"
    i=0
    while [ "${i}" -lt "${attempts}" ]; do
        if curl --fail --silent --show-error --max-time 3 "${url}" >/dev/null 2>&1; then
            return 0
        fi
        i=$((i + 1))
        sleep 1
    done
    echo "ERROR: ${url} did not become ready after ${attempts} attempts." >&2
    return 1
}

# --- 1: validate compose configuration ---------------------------------------

echo "== compose config =="
docker compose --env-file "${ENV_FILE}" -p "${STACK_ID}" config --quiet

# --- 2: build the backend image ----------------------------------------------

echo "== build backend =="
docker compose --env-file "${ENV_FILE}" -p "${STACK_ID}" build backend

# --- 3: start the stack and wait for healthy ----------------------------------

echo "== up --detach --wait =="
docker compose --env-file "${ENV_FILE}" -p "${STACK_ID}" up --detach --wait

# --- 4: MinIO liveness --------------------------------------------------------

echo "== minio live check =="
curl --fail --silent --show-error http://localhost:9000/minio/health/live >/dev/null
echo "minio: live"

# --- 5: backend health --------------------------------------------------------

echo "== backend health check =="
HEALTH="$(curl --fail --silent --show-error http://localhost:8080/api/v1/actuator/health)"
case "${HEALTH}" in
    *"UP"*) echo "backend: UP" ;;
    *) echo "ERROR: backend health does not report UP: ${HEALTH}" >&2; exit 1 ;;
esac

# --- 6: Task 1 dummy endpoint -------------------------------------------------

echo "== dummy pos-record endpoint =="
DUMMY_RESPONSE="$(curl --fail --silent --show-error \
    http://localhost:8080/api/v1/pos-records/11111111-1111-1111-1111-111111111111)"
case "${DUMMY_RESPONSE}" in
    *"11111111-1111-1111-1111-111111111111"*) echo "dummy endpoint: echoes fixed UUID" ;;
    *) echo "ERROR: dummy endpoint did not echo the fixed UUID: ${DUMMY_RESPONSE}" >&2; exit 1 ;;
esac

# --- 7: SQLite file exists inside the backend container -----------------------

echo "== sqlite file check =="
# Wrap in sh -c so Windows shells (MSYS/Git Bash) do not rewrite the absolute
# /data/... path to a host path before it reaches the container.
docker compose --env-file "${ENV_FILE}" -p "${STACK_ID}" exec -T backend sh -c 'test -s /data/sqlite/pos-doc.db'
echo "sqlite: database file present in backend container"

# --- 8: upload a persistence marker with one-shot mc --------------------------

echo "== minio persistence marker =="
MINIO_ALIAS_SETUP='mc alias set --quiet local "http://minio:9000" "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" >/dev/null'
# The minio-init service defines entrypoint: ["/bin/sh", "-c"], which
# `docker compose run` inherits. We do not pass `--entrypoint /bin/sh` here
# because MSYS-based shells (Git Bash) rewrite the absolute path argument to
# a host path before it reaches the CLI.
docker compose --env-file "${ENV_FILE}" -p "${STACK_ID}" run --rm --no-deps \
    -e MINIO_ROOT_USER -e MINIO_ROOT_PASSWORD \
    minio-init "${MINIO_ALIAS_SETUP}; printf 'minio-persistence-check' | mc pipe local/pos-documents-test/smoke/persistence.txt >/dev/null"
echo "minio: marker uploaded"

# --- 9: restart only MinIO and verify the marker survived ---------------------

echo "== restart minio =="
docker compose --env-file "${ENV_FILE}" -p "${STACK_ID}" restart minio
wait_for_url "http://localhost:9000/minio/health/live" 60

echo "== verify marker survived minio restart =="
MARKER="$(docker compose --env-file "${ENV_FILE}" -p "${STACK_ID}" run --rm --no-deps \
    -e MINIO_ROOT_USER -e MINIO_ROOT_PASSWORD \
    minio-init "${MINIO_ALIAS_SETUP}; mc cat local/pos-documents-test/smoke/persistence.txt 2>/dev/null")"
if [ "${MARKER}" = "minio-persistence-check" ]; then
    echo "minio: data survived container restart"
else
    echo "ERROR: marker mismatch after MinIO restart: '${MARKER}'" >&2
    exit 1
fi

# --- 10: restart only the backend and re-verify --------------------------------

echo "== restart backend =="
docker compose --env-file "${ENV_FILE}" -p "${STACK_ID}" restart backend
i=0
while [ "${i}" -lt 60 ]; do
    STATUS="$(docker inspect --format '{{.State.Health.Status}}' "$(docker compose --env-file "${ENV_FILE}" -p "${STACK_ID}" ps -q backend)" 2>/dev/null || echo starting)"
    if [ "${STATUS}" = "healthy" ]; then
        break
    fi
    i=$((i + 1))
    sleep 1
done
if [ "${STATUS}" != "healthy" ]; then
    echo "ERROR: backend did not become healthy after restart." >&2
    exit 1
fi
echo "backend: healthy after restart"

echo "== re-check sqlite file and dummy endpoint =="
docker compose --env-file "${ENV_FILE}" -p "${STACK_ID}" exec -T backend sh -c 'test -s /data/sqlite/pos-doc.db'
echo "sqlite: still present after backend restart"
DUMMY_RESPONSE="$(curl --fail --silent --show-error \
    http://localhost:8080/api/v1/pos-records/11111111-1111-1111-1111-111111111111)"
case "${DUMMY_RESPONSE}" in
    *"11111111-1111-1111-1111-111111111111"*) echo "dummy endpoint: OK after backend restart" ;;
    *) echo "ERROR: dummy endpoint failed after backend restart: ${DUMMY_RESPONSE}" >&2; exit 1 ;;
esac

echo ""
echo "verify-container-stack: ALL CHECKS PASSED"
