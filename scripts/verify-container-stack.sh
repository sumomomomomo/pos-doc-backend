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

# --- credentials and temporary environment file --------------------------------
#
# The same values are written to the temporary env file (consumed by every
# `docker compose` command) and kept in shell variables (used by the direct
# management-API curl calls). They are never printed.

MINIO_ROOT_USER="task2-test-access"
MINIO_ROOT_PASSWORD="task2-test-secret-change-me"
MINIO_BUCKET="pos-documents-test"
RABBITMQ_USERNAME="task45-test-rabbit"
RABBITMQ_PASSWORD="task45-test-rabbit-secret-change-me"

# Create the temp env file as a path relative to the working directory so that
# both this (POSIX) shell and native Docker resolve it identically. An MSYS
# /tmp/... path would be translated differently by the Windows docker client.
ENV_FILE="$(mktemp "pos-doc-task2-test-env.XXXXXX")"
cat > "${ENV_FILE}" <<EOF
MINIO_ROOT_USER=${MINIO_ROOT_USER}
MINIO_ROOT_PASSWORD=${MINIO_ROOT_PASSWORD}
MINIO_BUCKET=${MINIO_BUCKET}
RABBITMQ_USERNAME=${RABBITMQ_USERNAME}
RABBITMQ_PASSWORD=${RABBITMQ_PASSWORD}
EOF

# Repository root. The script operates from here so that relative paths work
# consistently (including for curl, which on Windows cannot read Git Bash
# /c/Users/... paths) and so `docker compose` finds compose.yaml.
ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "${ROOT_DIR}"

UPLOAD_RESPONSE_FILE=""

cleanup() {
    docker compose --env-file "${ENV_FILE}" -p "${PROJECT_NAME}" down --volumes --remove-orphans >/dev/null 2>&1 || true
    if [ -n "${ENV_FILE}" ]; then
        rm -f "${ENV_FILE}"
        ENV_FILE=""
    fi
    if [ -n "${UPLOAD_RESPONSE_FILE}" ]; then
        rm -f "${UPLOAD_RESPONSE_FILE}"
        UPLOAD_RESPONSE_FILE=""
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

# --- 11: RabbitMQ health and management API (Task 4-5) -------------------------

echo "== rabbitmq health check =="
i=0
RABBIT_STATUS="starting"
while [ "${i}" -lt 60 ]; do
    RABBIT_STATUS="$(docker inspect --format '{{.State.Health.Status}}' "$(docker compose --env-file "${ENV_FILE}" -p "${STACK_ID}" ps -q rabbitmq)" 2>/dev/null || echo starting)"
    if [ "${RABBIT_STATUS}" = "healthy" ]; then
        break
    fi
    i=$((i + 1))
    sleep 1
done
if [ "${RABBIT_STATUS}" != "healthy" ]; then
    echo "ERROR: rabbitmq did not become healthy." >&2
    exit 1
fi
echo "rabbitmq: healthy"

# Management API answers with the test credentials. The --user argument is
# assembled from variables and the script keeps tracing disabled, so the
# expanded value is never echoed.
echo "== rabbitmq management API check =="
# Bounded retry: the management listener can lag a moment behind the broker
# health check. The --user value is assembled from variables and shell tracing
# is disabled, so the expanded credentials are never echoed.
MGMT_OK=""
i=0
while [ "${i}" -lt 30 ]; do
    if curl --fail --silent --show-error --max-time 3 \
        --user "${RABBITMQ_USERNAME}:${RABBITMQ_PASSWORD}" \
        http://127.0.0.1:15672/api/whoami >/dev/null 2>&1; then
        MGMT_OK="yes"
        break
    fi
    i=$((i + 1))
    sleep 1
done
if [ -z "${MGMT_OK}" ]; then
    echo "ERROR: rabbitmq management API did not accept the test credentials." >&2
    exit 1
fi
echo "rabbitmq: management API authenticated"

# --- 12: real upload through the stack ----------------------------------------

echo "== stack upload (committed fixture) =="
# Relative path: the script has already cd to the repository root, so curl
# (including Windows curl) can read the file.
FIXTURE="src/test/resources/fixtures/valid-two-pdf.zip"
[ -f "${FIXTURE}" ] || { echo "ERROR: fixture ${FIXTURE} is missing." >&2; exit 1; }
UPLOAD_RESPONSE_FILE="$(mktemp "pos-doc-task2-test-upload.XXXXXX")"
UPLOAD_CODE="$(curl --silent --output "${UPLOAD_RESPONSE_FILE}" --write-out '%{http_code}' \
    --form "file=@${FIXTURE};filename=EREF-STACK-001.zip;type=application/zip" \
    --form "policyNumber=POLICY-STACK-001" \
    http://localhost:8080/api/v1/pos-records)"
if [ "${UPLOAD_CODE}" != "202" ]; then
    echo "ERROR: stack upload returned http ${UPLOAD_CODE}:" >&2
    cat "${UPLOAD_RESPONSE_FILE}" >&2
    exit 1
fi
UPLOAD_RESPONSE="$(cat "${UPLOAD_RESPONSE_FILE}")"
POS_RECORD_ID="$(printf '%s' "${UPLOAD_RESPONSE}" | sed -n 's/.*"posRecordId":"\([0-9a-f-]\{36\}\)".*/\1/p')"
JOB_ID="$(printf '%s' "${UPLOAD_RESPONSE}" | sed -n 's/.*"jobId":"\([0-9a-f-]\{36\}\)".*/\1/p')"
[ -n "${POS_RECORD_ID}" ] || { echo "ERROR: could not capture posRecordId from: ${UPLOAD_RESPONSE}" >&2; exit 1; }
[ -n "${JOB_ID}" ] || { echo "ERROR: could not capture jobId from: ${UPLOAD_RESPONSE}" >&2; exit 1; }
echo "upload: 202 with posRecordId and jobId"

echo "== job queryable and QUEUED =="
i=0
JOB_RESPONSE=""
while [ "${i}" -lt 30 ]; do
    JOB_RESPONSE="$(curl --fail --silent --show-error http://localhost:8080/api/v1/ingestion-jobs/${JOB_ID} 2>/dev/null || true)"
    case "${JOB_RESPONSE}" in
        *"QUEUED"*) break ;;
    esac
    i=$((i + 1))
    sleep 1
done
case "${JOB_RESPONSE}" in
    *"QUEUED"*) echo "job: QUEUED" ;;
    *) echo "ERROR: job did not report QUEUED: ${JOB_RESPONSE}" >&2; exit 1 ;;
esac

# --- 13: relay published exactly one message to the durable queue -------------

echo "== queue ready message count =="
i=0
READY="0"
while [ "${i}" -lt 30 ]; do
    READY="$(curl --fail --silent --show-error \
        --user "${RABBITMQ_USERNAME}:${RABBITMQ_PASSWORD}" \
        http://127.0.0.1:15672/api/queues/%2F/pos.ingestion.jobs \
        | sed -n 's/.*"messages_ready":\([0-9]\{1,\}\).*/\1/p')"
    if [ "${READY}" = "1" ]; then
        break
    fi
    i=$((i + 1))
    sleep 1
done
if [ "${READY}" != "1" ]; then
    echo "ERROR: pos.ingestion.jobs ready count is '${READY}', expected 1." >&2
    exit 1
fi
echo "queue: exactly one ready message"

echo "== message body contains identifiers only =="
# POST /get with count=1 and ackmode=reject_requeue_true inspects the message
# without consuming it (it is rejected and requeued). On RabbitMQ 4.x these
# options go in the JSON body, not the query string.
MESSAGE_JSON="$(curl --fail --silent --show-error --request POST \
    --user "${RABBITMQ_USERNAME}:${RABBITMQ_PASSWORD}" \
    -H 'content-type: application/json' \
    -d '{"count":1,"ackmode":"reject_requeue_true","encoding":"base64"}' \
    'http://127.0.0.1:15672/api/queues/%2F/pos.ingestion.jobs/get')"
PAYLOAD_B64="$(printf '%s' "${MESSAGE_JSON}" | sed -n 's/.*"payload":"\([^"]*\)".*/\1/p')"
[ -n "${PAYLOAD_B64}" ] || { echo "ERROR: could not read the queue message payload." >&2; exit 1; }
MESSAGE_BODY="$(printf '%s' "${PAYLOAD_B64}" | base64 -d)"
case "${MESSAGE_BODY}" in
    *EREF-STACK-001*|*POLICY-STACK-001*|*%PDF*|*valid-two-pdf*)
        echo "ERROR: queue message body leaks fixture metadata or ZIP/PDF bytes." >&2
        exit 1 ;;
esac
# Order-independent positive checks for each required field.
for FIELD in '"eventId"' "\"jobId\":\"${JOB_ID}\"" "\"posRecordId\":\"${POS_RECORD_ID}\"" '"schemaVersion":1' '"occurredAt"'; do
    case "${MESSAGE_BODY}" in
        *"${FIELD}"*) : ;;
        *)
            echo "ERROR: queue message body is missing required field: ${FIELD}" >&2
            exit 1 ;;
    esac
done
echo "message: identifiers only, no fixture metadata"

# --- 14: RabbitMQ restart keeps the durable queue and its message -------------

echo "== restart rabbitmq =="
docker compose --env-file "${ENV_FILE}" -p "${STACK_ID}" restart rabbitmq
i=0
RABBIT_STATUS="starting"
while [ "${i}" -lt 60 ]; do
    RABBIT_STATUS="$(docker inspect --format '{{.State.Health.Status}}' "$(docker compose --env-file "${ENV_FILE}" -p "${STACK_ID}" ps -q rabbitmq)" 2>/dev/null || echo starting)"
    if [ "${RABBIT_STATUS}" = "healthy" ]; then
        break
    fi
    i=$((i + 1))
    sleep 1
done
if [ "${RABBIT_STATUS}" != "healthy" ]; then
    echo "ERROR: rabbitmq did not become healthy after restart." >&2
    exit 1
fi
echo "rabbitmq: healthy after restart"

echo "== verify durable message survived rabbitmq restart =="
i=0
READY="0"
while [ "${i}" -lt 30 ]; do
    READY="$(curl --silent --fail \
        --user "${RABBITMQ_USERNAME}:${RABBITMQ_PASSWORD}" \
        http://127.0.0.1:15672/api/queues/%2F/pos.ingestion.jobs 2>/dev/null \
        | sed -n 's/.*"messages_ready":\([0-9]\{1,\}\).*/\1/p' || true)"
    if [ "${READY}" = "1" ]; then
        break
    fi
    i=$((i + 1))
    sleep 1
done
if [ "${READY}" != "1" ]; then
    echo "ERROR: durable message did not survive the rabbitmq restart (ready '${READY}')." >&2
    exit 1
fi
echo "queue: persistent message survived rabbitmq restart"

# --- 15: backend restart keeps the job queryable and queued -------------------

echo "== restart backend after rabbitmq restart =="
docker compose --env-file "${ENV_FILE}" -p "${STACK_ID}" restart backend
i=0
STATUS="starting"
while [ "${i}" -lt 60 ]; do
    STATUS="$(docker inspect --format '{{.State.Health.Status}}' "$(docker compose --env-file "${ENV_FILE}" -p "${STACK_ID}" ps -q backend)" 2>/dev/null || echo starting)"
    if [ "${STATUS}" = "healthy" ]; then
        break
    fi
    i=$((i + 1))
    sleep 1
done
if [ "${STATUS}" != "healthy" ]; then
    echo "ERROR: backend did not become healthy after the second restart." >&2
    exit 1
fi
JOB_RESPONSE="$(curl --fail --silent --show-error http://localhost:8080/api/v1/ingestion-jobs/${JOB_ID})"
case "${JOB_RESPONSE}" in
    *"QUEUED"*) echo "job: still QUEUED after backend restart" ;;
    *) echo "ERROR: job no longer QUEUED after backend restart: ${JOB_RESPONSE}" >&2; exit 1 ;;
esac

echo ""
echo "verify-container-stack: ALL CHECKS PASSED"
