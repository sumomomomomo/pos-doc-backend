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

# --- security (stack-test mode) ----------------------------------------------
#
# The whole-stack verifier runs the backend in the isolated, fail-closed
# stack-test authentication mode (plan 11). A random 64-hex bearer token is
# generated per run; every authenticated API call below sends it, and a
# pre-happy-path check proves the same endpoint is 401 without it. The Google
# client/subject values are inert placeholders present only to satisfy Compose
# variable substitution (stack-test mode does not use them).
STACK_TEST_TOKEN="$(python3 -c 'import secrets; print(secrets.token_hex(32))')"
GOOGLE_CLIENT_ID="stack-test-google-client-id"
GOOGLE_CLIENT_SECRET="stack-test-google-client-secret"
APP_SECURITY_GOOGLE_VIEWER_SUBJECTS="stack-test-viewer-subject"
APP_SECURITY_GOOGLE_REVIEWER_SUBJECTS="stack-test-reviewer-subject"
# Bearer header sent with every authenticated API call.
AUTH_HEADER="Authorization: Bearer ${STACK_TEST_TOKEN}"

# The security block written to both env-file phases. It is kept as a variable
# so the phase 2 rewrite (consumer re-enable) reproduces it exactly.
SECURITY_ENV="""GOOGLE_CLIENT_ID=${GOOGLE_CLIENT_ID}
GOOGLE_CLIENT_SECRET=${GOOGLE_CLIENT_SECRET}
APP_SECURITY_MODE=stack-test
APP_SECURITY_GOOGLE_VIEWER_SUBJECTS=${APP_SECURITY_GOOGLE_VIEWER_SUBJECTS}
APP_SECURITY_GOOGLE_REVIEWER_SUBJECTS=${APP_SECURITY_GOOGLE_REVIEWER_SUBJECTS}
APP_SECURITY_ALLOWED_ORIGINS=
APP_SECURITY_POST_LOGIN_REDIRECT=/
APP_SECURITY_STACK_TEST_TOKEN=${STACK_TEST_TOKEN}
SPRING_PROFILES_ACTIVE=stack-test"""

# Create the temp env file as a path relative to the working directory so that
# both this (POSIX) shell and native Docker resolve it identically. An MSYS
# /tmp/... path would be translated differently by the Windows docker client.
ENV_FILE="$(mktemp "pos-doc-task2-test-env.XXXXXX")"
# Phase 1 (Tasks 4-5): the consumer is disabled so the queued message
# survives a RabbitMQ restart. Phase 2 (Task 6) re-enables it after the
# durable-message check passes.
cat > "${ENV_FILE}" <<EOF
MINIO_ROOT_USER=${MINIO_ROOT_USER}
MINIO_ROOT_PASSWORD=${MINIO_ROOT_PASSWORD}
MINIO_BUCKET=${MINIO_BUCKET}
RABBITMQ_USERNAME=${RABBITMQ_USERNAME}
RABBITMQ_PASSWORD=${RABBITMQ_PASSWORD}
INGESTION_CONSUMER_ENABLED=false
${SECURITY_ENV}
EOF

# Repository root. The script operates from here so that relative paths work
# consistently (including for curl, which on Windows cannot read Git Bash
# /c/Users/... paths) and so `docker compose` finds compose.yaml.
ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "${ROOT_DIR}"

UPLOAD_RESPONSE_FILE=""
CONTENT_DIR=""

cleanup() {
    docker compose --env-file "${ENV_FILE}" -p "${PROJECT_NAME}" -f compose.yaml -f compose.dev.yaml -f compose.test-ocr.yaml down --volumes --remove-orphans >/dev/null 2>&1 || true
    if [ -n "${ENV_FILE}" ]; then
        rm -f "${ENV_FILE}"
        ENV_FILE=""
    fi
    if [ -n "${UPLOAD_RESPONSE_FILE}" ]; then
        rm -f "${UPLOAD_RESPONSE_FILE}"
        UPLOAD_RESPONSE_FILE=""
    fi
    if [ -n "${CONTENT_DIR}" ]; then
        rm -rf "${CONTENT_DIR}"
        CONTENT_DIR=""
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

# --- helper: count OCR requests via WireMock admin API ------------------------
# Uses POST /__admin/requests/count with urlPath filter. The curl runs
# inside the backend container (which has curl); the JSON is parsed on
# the host with python3.
ocr_request_count() {
    local response
    response="$(docker compose --env-file "${ENV_FILE}" -p "${STACK_ID}" \
        -f compose.yaml -f compose.dev.yaml -f compose.test-ocr.yaml exec -T backend \
        curl --fail --silent --show-error \
            --request POST \
            --header "Content-Type: application/json" \
            --data '{"method":"POST","urlPath":"/v1/chat/completions"}' \
            http://ocr-stub:8080/__admin/requests/count)"
    printf '%s' "${response}" | python3 -c 'import json,sys; print(json.load(sys.stdin)["count"])' | tr -d '[:space:]'
}

# --- helper: arbitrary HTTP call capturing status code + body -----------------
# http_call METHOD URL [json-body] [content-type]
# Sets the globals HTTP_CODE and HTTP_BODY. The body is written to a temp file
# and removed before returning, so no temp files leak out of the function.
http_call() {
    _hc_method="$1"
    _hc_url="$2"
    _hc_data="${3:-}"
    _hc_ctype="${4:-application/json}"
    _hc_body_file="$(mktemp "pos-doc-task2-test-http.XXXXXX")"
    _hc_code_file="$(mktemp "pos-doc-task2-test-code.XXXXXX")"
    if [ -n "${_hc_data}" ]; then
        curl --silent --max-time 15 --output "${_hc_body_file}" --write-out '%{http_code}' \
            --request "${_hc_method}" \
            --header "Content-Type: ${_hc_ctype}" \
            --header "${AUTH_HEADER}" \
            --data "${_hc_data}" \
            "${_hc_url}" > "${_hc_code_file}" 2>/dev/null || true
    else
        curl --silent --max-time 15 --output "${_hc_body_file}" --write-out '%{http_code}' \
            --request "${_hc_method}" \
            --header "${AUTH_HEADER}" \
            "${_hc_url}" > "${_hc_code_file}" 2>/dev/null || true
    fi
    HTTP_CODE="$(tr -d '[:space:]' < "${_hc_code_file}" 2>/dev/null || true)"
    HTTP_BODY="$(cat "${_hc_body_file}" 2>/dev/null || true)"
    rm -f "${_hc_body_file}" "${_hc_code_file}"
}

# --- helper: extract a non-negative integer JSON field from HTTP_BODY ---------
json_int() {
    printf '%s' "${HTTP_BODY}" | sed -n "s/.*\"${1}\"[[:space:]]*:[[:space:]]*\([0-9][0-9]*\).*/\1/p"
}

# --- helper: poll a GET until it returns the expected HTTP code --------------
# http_get_retry URL EXPECTED_CODE LABEL
# Tolerates a transient no-response (curl 000) right after a cold backend start,
# when the compose health check (actuator/health) has not yet exercised the DB.
# On success HTTP_CODE/HTTP_BODY hold the final response and it returns 0; on
# timeout it prints an error and returns 1.
http_get_retry() {
    _gr_url="$1"
    _gr_expect="$2"
    _gr_label="$3"
    i=0
    while [ "${i}" -lt 20 ]; do
        http_call GET "${_gr_url}"
        if [ "${HTTP_CODE}" = "${_gr_expect}" ]; then
            return 0
        fi
        i=$((i + 1))
        sleep 1
    done
    echo "ERROR: ${_gr_label}: expected http ${_gr_expect}, last was ${HTTP_CODE}: ${HTTP_BODY}" >&2
    return 1
}

# --- helper: authenticated binary download capturing headers + status --------
# http_download URL FILE
# Downloads URL (with the stack-test bearer token) to FILE, capturing the
# response headers into FILE.headers and the HTTP status into HTTP_CODE.
http_download() {
    _hd_url="$1"
    _hd_file="$2"
    _hd_code_file="$(mktemp pos-doc-task2-test-code.XXXXXX)"
    curl --silent --max-time 30 --output "${_hd_file}" --write-out '%{http_code}' \
        --dump-header "${_hd_file}.headers" \
        --header "${AUTH_HEADER}" \
        "${_hd_url}" > "${_hd_code_file}" 2>/dev/null || true
    HTTP_CODE="$(tr -d '[:space:]' < "${_hd_code_file}" 2>/dev/null || true)"
    rm -f "${_hd_code_file}"
}

# --- 1: validate compose configuration ---------------------------------------

# Ensure a clean slate: tear down any leftover stack from a previous run.
docker compose --env-file "${ENV_FILE}" -p "${STACK_ID}" -f compose.yaml -f compose.dev.yaml -f compose.test-ocr.yaml down --volumes --remove-orphans >/dev/null 2>&1 || true

echo "== compose config =="
docker compose --env-file "${ENV_FILE}" -p "${STACK_ID}" -f compose.yaml -f compose.dev.yaml -f compose.test-ocr.yaml config --quiet

# --- 2: build the backend image ----------------------------------------------

echo "== build backend =="
docker compose --env-file "${ENV_FILE}" -p "${STACK_ID}" -f compose.yaml -f compose.dev.yaml -f compose.test-ocr.yaml build backend

# --- 3: start the stack and wait for healthy ----------------------------------

echo "== up --detach --wait =="
docker compose --env-file "${ENV_FILE}" -p "${STACK_ID}" -f compose.yaml -f compose.dev.yaml -f compose.test-ocr.yaml up --detach --wait

# --- 4: MinIO liveness --------------------------------------------------------

echo "== minio live check =="
curl --fail --silent --show-error http://localhost:9000/minio/health/live >/dev/null
echo "minio: live"

# --- 5: backend health --------------------------------------------------------

echo "== backend health check =="
HEALTH="$(curl --fail --silent --show-error http://localhost:18080/api/v1/actuator/health)"
case "${HEALTH}" in
    *"UP"*) echo "backend: UP" ;;
    *) echo "ERROR: backend health does not report UP: ${HEALTH}" >&2; exit 1 ;;
esac

# --- 6: POS-record detail 404 for unknown id -------------------------------------------------

echo "== detail 404 for unknown id =="
http_get_retry "http://localhost:18080/api/v1/pos-records/11111111-1111-1111-1111-111111111111" 404 "detail"
printf '%s' "${HTTP_BODY}" | grep -q "POS_RECORD_NOT_FOUND" || { echo "ERROR: detail 404 missing POS_RECORD_NOT_FOUND: ${HTTP_BODY}" >&2; exit 1; }
echo "detail: unknown id -> 404 POS_RECORD_NOT_FOUND"

# --- 7: SQLite file exists inside the backend container -----------------------

echo "== sqlite file check =="
# Wrap in sh -c so Windows shells (MSYS/Git Bash) do not rewrite the absolute
# /data/... path to a host path before it reaches the container.
docker compose --env-file "${ENV_FILE}" -p "${STACK_ID}" -f compose.yaml -f compose.dev.yaml -f compose.test-ocr.yaml exec -T backend sh -c 'test -s /data/sqlite/pos-doc.db'
echo "sqlite: database file present in backend container"

# --- 8: upload a persistence marker with one-shot mc --------------------------

echo "== minio persistence marker =="
MINIO_ALIAS_SETUP='mc alias set --quiet local "http://minio:9000" "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" >/dev/null'
# The minio-init service defines entrypoint: ["/bin/sh", "-c"], which
# `docker compose run` inherits. We do not pass `--entrypoint /bin/sh` here
# because MSYS-based shells (Git Bash) rewrite the absolute path argument to
# a host path before it reaches the CLI.
docker compose --env-file "${ENV_FILE}" -p "${STACK_ID}" -f compose.yaml -f compose.dev.yaml -f compose.test-ocr.yaml run --rm --no-deps \
    -e MINIO_ROOT_USER -e MINIO_ROOT_PASSWORD \
    minio-init "${MINIO_ALIAS_SETUP}; printf 'minio-persistence-check' | mc pipe local/pos-documents-test/smoke/persistence.txt >/dev/null"
echo "minio: marker uploaded"

# --- 9: restart only MinIO and verify the marker survived ---------------------

echo "== restart minio =="
docker compose --env-file "${ENV_FILE}" -p "${STACK_ID}" -f compose.yaml -f compose.dev.yaml -f compose.test-ocr.yaml restart minio
wait_for_url "http://localhost:9000/minio/health/live" 60

echo "== verify marker survived minio restart =="
MARKER="$(docker compose --env-file "${ENV_FILE}" -p "${STACK_ID}" -f compose.yaml -f compose.dev.yaml -f compose.test-ocr.yaml run --rm --no-deps \
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
docker compose --env-file "${ENV_FILE}" -p "${STACK_ID}" -f compose.yaml -f compose.dev.yaml -f compose.test-ocr.yaml restart backend
i=0
while [ "${i}" -lt 60 ]; do
    STATUS="$(docker inspect --format '{{.State.Health.Status}}' "$(docker compose --env-file "${ENV_FILE}" -p "${STACK_ID}" -f compose.yaml -f compose.dev.yaml -f compose.test-ocr.yaml ps -q backend)" 2>/dev/null || echo starting)"
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

echo "== re-check sqlite file and detail 404 =="
docker compose --env-file "${ENV_FILE}" -p "${STACK_ID}" -f compose.yaml -f compose.dev.yaml -f compose.test-ocr.yaml exec -T backend sh -c 'test -s /data/sqlite/pos-doc.db'
echo "sqlite: still present after backend restart"
http_get_retry "http://localhost:18080/api/v1/pos-records/11111111-1111-1111-1111-111111111111" 404 "detail after backend restart"
printf '%s' "${HTTP_BODY}" | grep -q "POS_RECORD_NOT_FOUND" || { echo "ERROR: detail 404 missing POS_RECORD_NOT_FOUND after restart: ${HTTP_BODY}" >&2; exit 1; }
echo "detail: 404 POS_RECORD_NOT_FOUND after backend restart"

# --- 11: RabbitMQ health and management API (Task 4-5) -------------------------

echo "== rabbitmq health check =="
i=0
RABBIT_STATUS="starting"
while [ "${i}" -lt 60 ]; do
    RABBIT_STATUS="$(docker inspect --format '{{.State.Health.Status}}' "$(docker compose --env-file "${ENV_FILE}" -p "${STACK_ID}" -f compose.yaml -f compose.dev.yaml -f compose.test-ocr.yaml ps -q rabbitmq)" 2>/dev/null || echo starting)"
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

echo "== security: protected endpoint without token is 401 AUTHENTICATION_REQUIRED =="
UNAUTH_CODE="$(curl --silent --max-time 15 --output /dev/null --write-out '%{http_code}' \
    --request GET \
    "http://localhost:18080/api/v1/pos-records/11111111-1111-1111-1111-111111111111" 2>/dev/null || true)"
UNAUTH_BODY="$(curl --silent --max-time 15 \
    --request GET \
    "http://localhost:18080/api/v1/pos-records/11111111-1111-1111-1111-111111111111" 2>/dev/null || true)"
if [ "${UNAUTH_CODE}" != "401" ]; then
    echo "ERROR: unauthenticated protected endpoint returned http ${UNAUTH_CODE}, expected 401: ${UNAUTH_BODY}" >&2
    exit 1
fi
printf '%s' "${UNAUTH_BODY}" | grep -q "AUTHENTICATION_REQUIRED" || {
    echo "ERROR: unauthenticated 401 missing AUTHENTICATION_REQUIRED code: ${UNAUTH_BODY}" >&2
    exit 1
}
echo "security: protected endpoint without token -> 401 AUTHENTICATION_REQUIRED"

echo "== stack upload (committed fixture) =="
# Relative path: the script has already cd to the repository root, so curl
# (including Windows curl) can read the file.
FIXTURE="src/test/resources/fixtures/valid-with-lappe.zip"
[ -f "${FIXTURE}" ] || { echo "ERROR: fixture ${FIXTURE} is missing." >&2; exit 1; }
UPLOAD_RESPONSE_FILE="$(mktemp "pos-doc-task2-test-upload.XXXXXX")"
UPLOAD_CODE="$(curl --silent --max-time 15 --output "${UPLOAD_RESPONSE_FILE}" --write-out '%{http_code}' \
    --header "${AUTH_HEADER}" \
    --form "file=@${FIXTURE};filename=EREF-STACK-001.zip;type=application/zip" \
    --form "policyNumber=POLICY-STACK-001" \
    http://localhost:18080/api/v1/pos-records)"
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
    JOB_RESPONSE="$(curl --fail --silent --show-error --header "${AUTH_HEADER}" http://localhost:18080/api/v1/ingestion-jobs/${JOB_ID} 2>/dev/null || true)"
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
docker compose --env-file "${ENV_FILE}" -p "${STACK_ID}" -f compose.yaml -f compose.dev.yaml -f compose.test-ocr.yaml restart rabbitmq
i=0
RABBIT_STATUS="starting"
while [ "${i}" -lt 60 ]; do
    RABBIT_STATUS="$(docker inspect --format '{{.State.Health.Status}}' "$(docker compose --env-file "${ENV_FILE}" -p "${STACK_ID}" -f compose.yaml -f compose.dev.yaml -f compose.test-ocr.yaml ps -q rabbitmq)" 2>/dev/null || echo starting)"
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
docker compose --env-file "${ENV_FILE}" -p "${STACK_ID}" -f compose.yaml -f compose.dev.yaml -f compose.test-ocr.yaml restart backend
i=0
STATUS="starting"
while [ "${i}" -lt 60 ]; do
    STATUS="$(docker inspect --format '{{.State.Health.Status}}' "$(docker compose --env-file "${ENV_FILE}" -p "${STACK_ID}" -f compose.yaml -f compose.dev.yaml -f compose.test-ocr.yaml ps -q backend)" 2>/dev/null || echo starting)"
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
JOB_RESPONSE="$(curl --fail --silent --show-error --header "${AUTH_HEADER}" http://localhost:18080/api/v1/ingestion-jobs/${JOB_ID})"
case "${JOB_RESPONSE}" in
    *"QUEUED"*) echo "job: still QUEUED after backend restart" ;;
    *) echo "ERROR: job no longer QUEUED after backend restart: ${JOB_RESPONSE}" >&2; exit 1 ;;
esac

# --- Task 6 phase 2: re-enable the consumer and verify end-to-end ------------
#
# Phase 1 (Tasks 4-5) left one durable message on pos.ingestion.jobs with the
# consumer disabled. Phase 2 rewrites the env file with INGESTION_CONSUMER_ENABLED=true,
# restarts the backend so the listener container spins up, and asserts the
# consumer drains the queue, persists two documents, and leaves the storage
# in a known shape.

echo "== re-enable ingestion consumer =="
cat > "${ENV_FILE}" <<EOF
MINIO_ROOT_USER=${MINIO_ROOT_USER}
MINIO_ROOT_PASSWORD=${MINIO_ROOT_PASSWORD}
MINIO_BUCKET=${MINIO_BUCKET}
RABBITMQ_USERNAME=${RABBITMQ_USERNAME}
RABBITMQ_PASSWORD=${RABBITMQ_PASSWORD}
INGESTION_CONSUMER_ENABLED=true
${SECURITY_ENV}
EOF
docker compose --env-file "${ENV_FILE}" -p "${STACK_ID}" -f compose.yaml -f compose.dev.yaml -f compose.test-ocr.yaml up --detach --wait backend >/dev/null
i=0
STATUS="starting"
while [ "${i}" -lt 60 ]; do
    STATUS="$(docker inspect --format '{{.State.Health.Status}}' "$(docker compose --env-file "${ENV_FILE}" -p "${STACK_ID}" -f compose.yaml -f compose.dev.yaml -f compose.test-ocr.yaml ps -q backend)" 2>/dev/null || echo starting)"
    if [ "${STATUS}" = "healthy" ]; then
        break
    fi
    i=$((i + 1))
    sleep 1
done
if [ "${STATUS}" != "healthy" ]; then
    echo "ERROR: backend did not become healthy with the consumer enabled." >&2
    exit 1
fi
echo "backend: healthy with consumer enabled"

echo "== job reaches COMPLETED with attempt_count=1 =="
i=0
JOB_RESPONSE=""
while [ "${i}" -lt 60 ]; do
    JOB_RESPONSE="$(curl --fail --silent --show-error --header "${AUTH_HEADER}" http://localhost:18080/api/v1/ingestion-jobs/${JOB_ID} 2>/dev/null || true)"
    case "${JOB_RESPONSE}" in
        *"\"status\":\"COMPLETED\""*'"attemptCount":1'*) break ;;
    esac
    i=$((i + 1))
    sleep 1
done
case "${JOB_RESPONSE}" in
    *"\"status\":\"COMPLETED\""*'"attemptCount":1'*)
        echo "job: COMPLETED with attemptCount=1" ;;
    *)
        echo "ERROR: job did not reach COMPLETED/attemptCount=1: ${JOB_RESPONSE}" >&2
        exit 1 ;;
esac
# Job must not carry non-null error code or error message.
# Tolerant of field ordering: check each field independently.
HAS_NON_NULL_ERROR_CODE=0
HAS_NON_NULL_ERROR_MESSAGE=0
printf '%s' "${JOB_RESPONSE}" | grep -qE '"errorCode"\s*:\s*"[^"]+"' && HAS_NON_NULL_ERROR_CODE=1
printf '%s' "${JOB_RESPONSE}" | grep -qE '"errorMessage"\s*:\s*"[^"]+"' && HAS_NON_NULL_ERROR_MESSAGE=1
if [ "${HAS_NON_NULL_ERROR_CODE}" -eq 1 ] || [ "${HAS_NON_NULL_ERROR_MESSAGE}" -eq 1 ]; then
    echo "ERROR: completed job carries non-null error fields: ${JOB_RESPONSE}" >&2
    exit 1
fi
echo "job: no terminal error"

echo "== pos_document: candidate COMPLETED + non-candidate SKIPPED =="
DOCS_RESPONSE="$(curl --fail --silent --show-error --header "${AUTH_HEADER}" \
    http://localhost:18080/api/v1/pos-records/${POS_RECORD_ID}/documents)"
COUNT="$(printf '%s' "${DOCS_RESPONSE}" | grep -o '"posRecordId":"[0-9a-f-]\{36\}"' | wc -l | tr -d ' ')"
if [ "${COUNT}" != "2" ]; then
    echo "ERROR: expected 2 documents, got ${COUNT}: ${DOCS_RESPONSE}" >&2
    exit 1
fi
COMPLETED_COUNT="$(printf '%s' "${DOCS_RESPONSE}" | grep -o '"processingStatus":"COMPLETED"' | wc -l | tr -d ' ')"
SKIPPED_COUNT="$(printf '%s' "${DOCS_RESPONSE}" | grep -o '"processingStatus":"SKIPPED"' | wc -l | tr -d ' ')"
if [ "${COMPLETED_COUNT}" != "1" ] || [ "${SKIPPED_COUNT}" != "1" ]; then
    echo "ERROR: expected 1 COMPLETED (LAPPe candidate) + 1 SKIPPED, got ${COMPLETED_COUNT} COMPLETED / ${SKIPPED_COUNT} SKIPPED: ${DOCS_RESPONSE}" >&2
    exit 1
fi
echo "documents: candidate COMPLETED + non-candidate SKIPPED"

# Use a test-only SQLite CLI container attached read-only to the SQLite
# volume, since the backend runtime image contains the JRE and curl but
# not the sqlite3 CLI.
sqlite_query() {
    docker run --rm \
        -v "${STACK_ID}_sqlite-data:/db:ro" \
        alpine:3.20 sh -c "apk add --no-cache sqlite >/dev/null 2>&1; sqlite3 /db/pos-doc.db \"$1\""
}

echo "== pos_record is REVIEW_REQUIRED (Task 9) =="
# After Task 9, the POS record must be REVIEW_REQUIRED (durable OCR exists
# but structured policy metadata has not yet been extracted). We verify
# via the test-only SQLite CLI container.
RECORD_STATUS="$(sqlite_query "SELECT status FROM pos_record WHERE id = '${POS_RECORD_ID}';")"
if [ "${RECORD_STATUS}" = "REVIEW_REQUIRED" ]; then
    echo "pos_record: REVIEW_REQUIRED"
else
    echo "ERROR: pos_record status is '${RECORD_STATUS}', expected REVIEW_REQUIRED" >&2
    exit 1
fi

echo "== main queue and DLQ are empty =="
# Poll for up to 30s for the message to be fully ACKed and the queues
# to drain. The job may be COMPLETED in SQLite before the AMQP ACK
# propagates to the management API. A 404 from the management API means
# the queue does not exist yet (e.g. the DLQ before any message is
# dead-lettered), which is equivalent to empty.
for Q in pos.ingestion.jobs pos.ingestion.jobs.dlq; do
    i=0
    while [ $i -lt 30 ]; do
        QUEUE_INFO="$(curl --silent --show-error \
            --user "${RABBITMQ_USERNAME}:${RABBITMQ_PASSWORD}" \
            http://127.0.0.1:15672/api/queues/%2F/${Q} 2>/dev/null)"
        READY="$(printf '%s' "${QUEUE_INFO}" | sed -n 's/.*"messages_ready":\([0-9]\{1,\}\).*/\1/p')"
        UNACKED="$(printf '%s' "${QUEUE_INFO}" | sed -n 's/.*"messages_unacknowledged":\([0-9]\{1,\}\).*/\1/p')"
        # Empty or 404 means the queue is absent or has no messages.
        if [ -z "${READY}" ] || { [ "${READY}" = "0" ] && [ "${UNACKED}" = "0" ]; }; then
            break
        fi
        i=$((i + 1))
        sleep 1
    done
    if [ -n "${READY}" ] && [ "${READY}" != "0" ]; then
        echo "ERROR: ${Q} has ${READY} ready messages, expected 0." >&2
        exit 1
    fi
    if [ -n "${UNACKED}" ] && [ "${UNACKED}" != "0" ]; then
        echo "ERROR: ${Q} has ${UNACKED} unacknowledged messages, expected 0." >&2
        exit 1
    fi
done
echo "queues: empty"

echo "== source archive and two UUID-keyed PDFs are in MinIO =="
KEYS="$(docker compose --env-file "${ENV_FILE}" -p "${STACK_ID}" -f compose.yaml -f compose.dev.yaml -f compose.test-ocr.yaml run --rm --no-deps \
    -e MINIO_ROOT_USER -e MINIO_ROOT_PASSWORD \
    minio-init "${MINIO_ALIAS_SETUP}; mc ls --recursive local/${MINIO_BUCKET}/ 2>/dev/null" | tr -d '\r')"
SOURCE_COUNT="$(printf '%s' "${KEYS}" | grep -c "archives/${POS_RECORD_ID}/" || true)"
PDF_COUNT="$(printf '%s' "${KEYS}" | grep -cE "documents/${POS_RECORD_ID}/[0-9a-f-]{36}\\.pdf$" || true)"
if [ "${SOURCE_COUNT}" != "1" ]; then
    echo "ERROR: expected 1 source archive under archives/${POS_RECORD_ID}/, got ${SOURCE_COUNT}." >&2
    echo "Listing was: ${KEYS}" >&2
    exit 1
fi
if [ "${PDF_COUNT}" != "2" ]; then
    echo "ERROR: expected 2 UUID-keyed PDFs under documents/${POS_RECORD_ID}/, got ${PDF_COUNT}." >&2
    echo "Listing was: ${KEYS}" >&2
    exit 1
fi
echo "minio: source archive and 2 UUID-keyed PDFs present"

echo "== PDFs are byte-for-byte equal to fixture entries =="
EXTRACT_DIR="$(mktemp -d "pos-doc-task6-pdfs.XXXXXX")"
# mc ls --recursive output format: [YYYY-MM-DD HH:MM:SS UTC] SIZE key
# Extract just the object key (last field) from each matching line.
PDF_KEYS="$(printf '%s' "${KEYS}" | tr -d '\r' | grep -E "documents/${POS_RECORD_ID}/[0-9a-f-]{36}\\.pdf" | awk '{print $NF}' || true)"
for KEY in ${PDF_KEYS}; do
    KEY="$(printf '%s' "${KEY}" | tr -d '\r')"
    SAFE_KEY="$(printf '%s' "${KEY}" | tr '/' '_')"
    # --quiet suppresses docker compose's own stdout (container lifecycle
    # messages) so only the mc cat payload reaches the file.
    docker compose --env-file "${ENV_FILE}" -p "${STACK_ID}" -f compose.yaml -f compose.dev.yaml -f compose.test-ocr.yaml run --rm --no-deps --quiet \
        -e MINIO_ROOT_USER -e MINIO_ROOT_PASSWORD \
        minio-init "${MINIO_ALIAS_SETUP}; mc cat local/${MINIO_BUCKET}/${KEY} 2>/dev/null" > "${EXTRACT_DIR}/${SAFE_KEY}"
done
# Compare each extracted PDF against the corresponding fixture entry. The
# script invokes `unzip -p` (POSIX) so the script needs neither `unzip` on
# the host (only zip + sha256sum + sh) nor a temp directory for the fixture.
EXTRACTED_KEYS="$(printf '%s' "${KEYS}" | tr -d '\r' | grep -E "documents/${POS_RECORD_ID}/[0-9a-f-]{36}\\.pdf" | awk '{print $NF}' | sort)"
MATCH=0
for KEY in ${EXTRACTED_KEYS}; do
    KEY="$(printf '%s' "${KEY}" | tr -d '\r')"
    SAFE_KEY="$(printf '%s' "${KEY}" | tr '/' '_')"
    ACTUAL_HASH="$(sha256sum "${EXTRACT_DIR}/${SAFE_KEY}" | sed -n 's/^\([0-9a-f]\{64\}\).*/\1/p')"
    for ENTRY in documents/LAPPe.pdf documents/other.pdf; do
        EXPECTED_HASH="$(python -c "import zipfile,sys; z=zipfile.ZipFile(sys.argv[1]); sys.stdout.buffer.write(z.read(sys.argv[2]))" "${FIXTURE}" "${ENTRY}" 2>/dev/null | sha256sum | sed -n 's/^\([0-9a-f]\{64\}\).*/\1/p')"
        if [ "${ACTUAL_HASH}" = "${EXPECTED_HASH}" ]; then
            MATCH=$((MATCH + 1))
            break
        fi
    done
done
if [ "${MATCH}" != "2" ]; then
    echo "ERROR: extracted PDFs did not match fixture entries (matched ${MATCH}/2)." >&2
    exit 1
fi
echo "pdfs: both extracted PDFs byte-for-byte equal fixture entries"
rm -rf "${EXTRACT_DIR}"

# --- Task 9: OCR verification (before duplicate delivery) ---------------------

echo "== OCR stub health check (WireMock) =="
OCR_STUB_HTTP_CODE="$(docker compose --env-file "${ENV_FILE}" -p "${STACK_ID}" -f compose.yaml -f compose.dev.yaml -f compose.test-ocr.yaml exec -T backend \
    sh -c 'curl --silent --max-time 15 --output /dev/null --write-out "%{http_code}" http://ocr-stub:8080/__admin/mappings 2>/dev/null || echo 000')"
if [ "${OCR_STUB_HTTP_CODE}" != "200" ]; then
    echo "ERROR: OCR stub (WireMock) is not healthy: HTTP ${OCR_STUB_HTTP_CODE} from /__admin/mappings" >&2
    exit 1
fi
echo "ocr-stub: WireMock healthy (HTTP 200 from /__admin/mappings)"

echo "== OCR request count via WireMock request-count endpoint =="
OCR_REQUEST_COUNT="$(ocr_request_count)"
if [ "${OCR_REQUEST_COUNT}" != "3" ]; then
    echo "ERROR: expected 3 OCR requests in WireMock journal (one per field), got ${OCR_REQUEST_COUNT}." >&2
    exit 1
fi
echo "wiremock: exactly 3 OCR requests recorded (one per field)"

echo "== structured field outcomes in SQLite =="
FIELD_RESULT_COUNT="$(sqlite_query "SELECT count(*) FROM pos_field_extraction WHERE prompt_version = 2;")"
if [ "${FIELD_RESULT_COUNT}" != "3" ]; then
    echo "ERROR: expected 3 version-2 field outcomes, got ${FIELD_RESULT_COUNT}." >&2
    exit 1
fi
FIELD_RESOLVED="$(sqlite_query "SELECT count(*) FROM pos_field_extraction WHERE outcome = 'RESOLVED';")"
if [ "${FIELD_RESOLVED}" != "3" ]; then
    echo "ERROR: expected 3 RESOLVED field outcomes, got ${FIELD_RESOLVED}." >&2
    exit 1
fi
echo "sqlite: 3 version-2 field outcomes present (all RESOLVED)"

echo "== field values are the deterministic stub values =="
PH_VALUE="$(sqlite_query "SELECT value_text FROM pos_field_extraction WHERE field_name = 'POLICYHOLDER_NAME';")"
CS_VALUE="$(sqlite_query "SELECT value_text FROM pos_field_extraction WHERE field_name = 'CONSULTANT_NAME';")"
DT_VALUE="$(sqlite_query "SELECT value_text FROM pos_field_extraction WHERE field_name = 'POLICY_CREATE_DATE';")"
[ "${PH_VALUE}" = "Charlie Henry" ] || { echo "ERROR: policyholder value is '${PH_VALUE}', expected 'Charlie Henry'." >&2; exit 1; }
[ "${CS_VALUE}" = "John Davidson" ] || { echo "ERROR: consultant value is '${CS_VALUE}', expected 'John Davidson'." >&2; exit 1; }
[ "${DT_VALUE}" = "2026-07-26" ] || { echo "ERROR: date value is '${DT_VALUE}', expected '2026-07-26'." >&2; exit 1; }
echo "field values: Charlie Henry / John Davidson / 2026-07-26"

echo "== resolved business fields applied to the record =="
RECORD_POLICYHOLDER="$(sqlite_query "SELECT policyholder_name FROM pos_record WHERE id = '${POS_RECORD_ID}';")"
RECORD_CONSULTANT="$(sqlite_query "SELECT consultant_name FROM pos_record WHERE id = '${POS_RECORD_ID}';")"
RECORD_DATE="$(sqlite_query "SELECT policy_create_date FROM pos_record WHERE id = '${POS_RECORD_ID}';")"
[ "${RECORD_POLICYHOLDER}" = "Charlie Henry" ] || { echo "ERROR: record policyholder_name is '${RECORD_POLICYHOLDER}', expected 'Charlie Henry'." >&2; exit 1; }
[ "${RECORD_CONSULTANT}" = "John Davidson" ] || { echo "ERROR: record consultant_name is '${RECORD_CONSULTANT}', expected 'John Davidson'." >&2; exit 1; }
[ "${RECORD_DATE}" = "2026-07-26" ] || { echo "ERROR: record policy_create_date is '${RECORD_DATE}', expected '2026-07-26'." >&2; exit 1; }
echo "record business fields: policyholder/consultant/date applied"

echo "== document and job statuses are final =="
DOC_COMPLETED="$(sqlite_query "SELECT count(*) FROM pos_document WHERE pos_record_id = '${POS_RECORD_ID}' AND processing_status = 'COMPLETED';")"
DOC_SKIPPED="$(sqlite_query "SELECT count(*) FROM pos_document WHERE pos_record_id = '${POS_RECORD_ID}' AND processing_status = 'SKIPPED';")"
if [ "${DOC_COMPLETED}" != "1" ] || [ "${DOC_SKIPPED}" != "1" ]; then
    echo "ERROR: expected 1 COMPLETED + 1 SKIPPED document, got ${DOC_COMPLETED} COMPLETED / ${DOC_SKIPPED} SKIPPED." >&2
    exit 1
fi
echo "documents: candidate COMPLETED + non-candidate SKIPPED"

echo "== POS record is REVIEW_REQUIRED =="
RECORD_STATUS="$(sqlite_query "SELECT status FROM pos_record WHERE id = '${POS_RECORD_ID}';")"
if [ "${RECORD_STATUS}" != "REVIEW_REQUIRED" ]; then
    echo "ERROR: expected REVIEW_REQUIRED, got ${RECORD_STATUS}." >&2
    exit 1
fi
echo "pos_record: REVIEW_REQUIRED"

echo "== MinIO still contains the original ZIP and extracted PDFs =="
KEYS_BEFORE_DUP="$(docker compose --env-file "${ENV_FILE}" -p "${STACK_ID}" -f compose.yaml -f compose.dev.yaml -f compose.test-ocr.yaml run --rm --no-deps \
    -e MINIO_ROOT_USER -e MINIO_ROOT_PASSWORD \
    minio-init "${MINIO_ALIAS_SETUP}; mc ls --recursive local/${MINIO_BUCKET}/ 2>/dev/null" | tr -d '\r')"
SRC_BEFORE_DUP="$(printf '%s' "${KEYS_BEFORE_DUP}" | grep -c "archives/${POS_RECORD_ID}/" || true)"
PDF_BEFORE_DUP="$(printf '%s' "${KEYS_BEFORE_DUP}" | grep -cE "documents/${POS_RECORD_ID}/[0-9a-f-]{36}\\.pdf$" || true)"
if [ "${SRC_BEFORE_DUP}" != "1" ] || [ "${PDF_BEFORE_DUP}" != "2" ]; then
    echo "ERROR: MinIO state before duplicate: src=${SRC_BEFORE_DUP} pdfs=${PDF_BEFORE_DUP} (expected 1/2)." >&2
    exit 1
fi
echo "minio: source archive and 2 PDFs still present after OCR"

echo "== no PNG objects were created in MinIO =="
PNG_BEFORE_DUP="$(printf '%s' "${KEYS_BEFORE_DUP}" | grep -cE '\.png$' || true)"
if [ "${PNG_BEFORE_DUP}" != "0" ]; then
    echo "ERROR: ${PNG_BEFORE_DUP} PNG objects found in MinIO; no PNG objects should be created." >&2
    exit 1
fi
echo "minio: no PNG objects created"

echo "== duplicate message is a no-op (idempotency) =="
# Re-publish a message with the same jobId/posRecordId; the consumer must
# treat it as IDEMPOTENT_NOOP. The eventId is a fresh UUID (the original
# eventId is not stored in the job API response).
DUP_EVENT_ID="$(python -c 'import uuid; print(uuid.uuid4())')"
DUP_PAYLOAD="$(printf '{"eventId":"%s","jobId":"%s","posRecordId":"%s","schemaVersion":1,"occurredAt":"2026-01-02T03:04:05Z"}' \
    "${DUP_EVENT_ID}" "${JOB_ID}" "${POS_RECORD_ID}")"
# Publish via the RabbitMQ HTTP management API. Use Python to build
# the JSON body so the nested payload is correctly escaped.
PUBLISH_PY="$(mktemp publish-body-XXXXXX.py)"
cat > "${PUBLISH_PY}" <<'PYEOF'
import json, sys
body = {
    'properties': {
        'content_type': 'application/json',
        'content_encoding': 'UTF-8',
        'delivery_mode': 2,
        'message_id': sys.argv[1],
        'correlation_id': sys.argv[2],
        'type': 'INGESTION_REQUESTED'
    },
    'routing_key': 'ingestion.requested',
    'payload': sys.argv[3],
    'payload_encoding': 'string'
}
print(json.dumps(body))
PYEOF
PUBLISH_BODY="$(python "${PUBLISH_PY}" "${DUP_EVENT_ID}" "${JOB_ID}" "${DUP_PAYLOAD}")"
rm -f "${PUBLISH_PY}"
curl --fail --silent --show-error --request POST \
    --user "${RABBITMQ_USERNAME}:${RABBITMQ_PASSWORD}" \
    -H 'content-type: application/json' \
    -d "${PUBLISH_BODY}" \
    "http://127.0.0.1:15672/api/exchanges/%2F/pos.ingestion/publish" >/dev/null
# Bounded wait for the consumer to fully ACK the duplicate.
# Poll until both messages_ready AND messages_unacknowledged are zero.
i=0
READY="1"
UNACKED="1"
while [ "${i}" -lt 30 ]; do
    QUEUE_INFO="$(curl --fail --silent --show-error \
        --user "${RABBITMQ_USERNAME}:${RABBITMQ_PASSWORD}" \
        http://127.0.0.1:15672/api/queues/%2F/pos.ingestion.jobs)"
    READY="$(printf '%s' "${QUEUE_INFO}" | sed -n 's/.*"messages_ready":\([0-9]\{1,\}\).*/\1/p')"
    UNACKED="$(printf '%s' "${QUEUE_INFO}" | sed -n 's/.*"messages_unacknowledged":\([0-9]\{1,\}\).*/\1/p')"
    if [ "${READY}" = "0" ] && [ "${UNACKED}" = "0" ]; then
        break
    fi
    i=$((i + 1))
    sleep 1
done
if [ "${READY}" != "0" ] || [ "${UNACKED}" != "0" ]; then
    echo "ERROR: duplicate message was not fully consumed (ready=${READY}, unacked=${UNACKED})." >&2
    exit 1
fi
# Still exactly two documents.
DOCS_AFTER="$(curl --fail --silent --show-error --header "${AUTH_HEADER}" \
    http://localhost:18080/api/v1/pos-records/${POS_RECORD_ID}/documents)"
COUNT_AFTER="$(printf '%s' "${DOCS_AFTER}" | grep -o '"posRecordId":"[0-9a-f-]\{36\}"' | wc -l | tr -d ' ')"
if [ "${COUNT_AFTER}" != "2" ]; then
    echo "ERROR: duplicate delivery created extra documents: ${DOCS_AFTER}" >&2
    exit 1
fi
echo "duplicate: ACK'd as no-op; document count unchanged"

# Verify the OCR request count remains 3 after duplicate delivery.
OCR_REQUEST_COUNT_AFTER="$(ocr_request_count)"
if [ "${OCR_REQUEST_COUNT_AFTER}" != "3" ]; then
    echo "ERROR: expected 3 OCR requests after duplicate delivery, got ${OCR_REQUEST_COUNT_AFTER}." >&2
    exit 1
fi
echo "wiremock: still exactly 3 OCR requests after duplicate delivery"

# --- Task 10: read/search/PATCH/verify/delete smoke flow -----------------------
#
# The ingested record above is a completed ingestion (REVIEW_REQUIRED, two
# COMPLETED documents, one COMPLETED job). This synthetic-metadata smoke flow
# exercises the persistence-backed review API end to end: detail read, PATCH,
# search, explicit verification, and soft delete. It runs last so the trailing
# mutation (delete) cannot disturb the earlier checks.

echo "== Task 10 smoke: detail read of the ingested record =="
http_get_retry "http://localhost:18080/api/v1/pos-records/${POS_RECORD_ID}" 200 "detail read"
printf '%s' "${HTTP_BODY}" | grep -q "\"id\":\"${POS_RECORD_ID}\"" || { echo "ERROR: detail response missing id: ${HTTP_BODY}" >&2; exit 1; }
printf '%s' "${HTTP_BODY}" | grep -q '"status":"REVIEW_REQUIRED"' || { echo "ERROR: ingested record is not REVIEW_REQUIRED: ${HTTP_BODY}" >&2; exit 1; }
echo "detail: read OK, status REVIEW_REQUIRED"
V0="$(json_int version)"
[ -n "${V0}" ] || { echo "ERROR: could not read version from detail: ${HTTP_BODY}" >&2; exit 1; }

echo "== Task 10 smoke: PATCH stores synthetic metadata =="
http_call PATCH "http://localhost:18080/api/v1/pos-records/${POS_RECORD_ID}" \
    '{"expectedVersion":'"${V0}"',"policyholderName":"Stack Smoke Holder","consultantName":"Stack Smoke Consultant"}' \
    "application/merge-patch+json"
if [ "${HTTP_CODE}" != "200" ]; then
    echo "ERROR: PATCH returned http ${HTTP_CODE}: ${HTTP_BODY}" >&2
    exit 1
fi
printf '%s' "${HTTP_BODY}" | grep -q '"policyholderName":"Stack Smoke Holder"' || { echo "ERROR: PATCH did not store holder: ${HTTP_BODY}" >&2; exit 1; }
printf '%s' "${HTTP_BODY}" | grep -q '"consultantName":"Stack Smoke Consultant"' || { echo "ERROR: PATCH did not store consultant: ${HTTP_BODY}" >&2; exit 1; }
printf '%s' "${HTTP_BODY}" | grep -q '"status":"REVIEW_REQUIRED"' || { echo "ERROR: PATCH on REVIEW_REQUIRED changed status: ${HTTP_BODY}" >&2; exit 1; }
V1="$(json_int version)"
[ -n "${V1}" ] || { echo "ERROR: could not read version after PATCH: ${HTTP_BODY}" >&2; exit 1; }
[ "${V1}" = "$((V0 + 1))" ] || { echo "ERROR: PATCH did not bump version (${V0} -> ${V1})" >&2; exit 1; }
echo "patch: metadata stored, version ${V0} -> ${V1}"

echo "== Task 10 smoke: search finds the record by policy number =="
http_call POST "http://localhost:18080/api/v1/pos-records/search" '{"policyNumber":"POLICY-STACK-001"}'
if [ "${HTTP_CODE}" != "200" ]; then
    echo "ERROR: search returned http ${HTTP_CODE}: ${HTTP_BODY}" >&2
    exit 1
fi
printf '%s' "${HTTP_BODY}" | grep -q "\"id\":\"${POS_RECORD_ID}\"" || { echo "ERROR: search did not return the record: ${HTTP_BODY}" >&2; exit 1; }
echo "search: record found by policy number"

echo "== Task 10 smoke: verification moves it to COMPLETED =="
http_call POST "http://localhost:18080/api/v1/pos-records/${POS_RECORD_ID}/verification" '{"expectedVersion":'"${V1}"'}'
if [ "${HTTP_CODE}" != "200" ]; then
    echo "ERROR: verification returned http ${HTTP_CODE}: ${HTTP_BODY}" >&2
    exit 1
fi
printf '%s' "${HTTP_BODY}" | grep -q '"status":"COMPLETED"' || { echo "ERROR: verification did not set COMPLETED: ${HTTP_BODY}" >&2; exit 1; }
V2="$(json_int version)"
[ -n "${V2}" ] || { echo "ERROR: could not read version after verify: ${HTTP_BODY}" >&2; exit 1; }
[ "${V2}" = "$((V1 + 1))" ] || { echo "ERROR: verification did not bump version (${V1} -> ${V2})" >&2; exit 1; }
echo "verify: status COMPLETED, version ${V1} -> ${V2}"

# --- Task 11: protected content access while the record is active ------------
# The record is COMPLETED and active; the authenticated (stack-test bearer) HTTP
# content endpoints must stream the exact fixture bytes with the required
# security/content headers. The content URLs are saved for the post-delete check.

echo "== content: uploadedBy is the synthetic stack-test principal =="
http_get_retry "http://localhost:18080/api/v1/pos-records/${POS_RECORD_ID}" 200 "content detail"
printf '%s' "${HTTP_BODY}" | grep -q '"uploadedBy":"stack-test:principal"' || { echo "ERROR: uploadedBy is not stack-test:principal: ${HTTP_BODY}" >&2; exit 1; }
echo "content: uploadedBy == stack-test:principal"

echo "== content: extract both document ids from the document list =="
DOCS_LIST="$(curl --fail --silent --show-error --header "${AUTH_HEADER}" http://localhost:18080/api/v1/pos-records/${POS_RECORD_ID}/documents)"
# Parse the JSON to extract only the top-level document ids (the nested
# storageObject also has an id, so a naive grep would capture the wrong ids).
DOC_IDS="$(printf '%s' "${DOCS_LIST}" | python3 -c 'import json,sys; [print(d["id"]) for d in json.load(sys.stdin)]')"
DOC1="$(printf '%s' "${DOC_IDS}" | sed -n '1p')"
DOC2="$(printf '%s' "${DOC_IDS}" | sed -n '2p')"
[ -n "${DOC1}" ] && [ -n "${DOC2}" ] && [ "${DOC1}" != "${DOC2}" ] || { echo "ERROR: could not extract two distinct document ids: ${DOCS_LIST}" >&2; exit 1; }
echo "content: two document ids captured"

CONTENT_DIR="$(mktemp -d "pos-doc-task11-content.XXXXXX")"
PDF_CONTENT_URL="http://localhost:18080/api/v1/pos-records/${POS_RECORD_ID}/documents/${DOC1}/content"
ZIP_CONTENT_URL="http://localhost:18080/api/v1/pos-records/${POS_RECORD_ID}/source-archive/content"

echo "== content: download each PDF via the authenticated HTTP content endpoint =="
for DOC_ID in "${DOC1}" "${DOC2}"; do
    http_download "http://localhost:18080/api/v1/pos-records/${POS_RECORD_ID}/documents/${DOC_ID}/content" "${CONTENT_DIR}/${DOC_ID}.pdf"
    [ "${HTTP_CODE}" = "200" ] || { echo "ERROR: PDF content returned http ${HTTP_CODE} for document ${DOC_ID}" >&2; exit 1; }
    _H="${CONTENT_DIR}/${DOC_ID}.pdf.headers"
    grep -qi '^content-type: application/pdf' "${_H}" || { echo "ERROR: PDF content-type wrong: $(grep -i '^content-type' "${_H}")" >&2; exit 1; }
    grep -qi '^content-disposition: inline' "${_H}" || { echo "ERROR: PDF must be served inline: $(grep -i '^content-disposition' "${_H}")" >&2; exit 1; }
    grep -qi '^cache-control: no-store' "${_H}" || { echo "ERROR: PDF cache-control wrong: $(grep -i '^cache-control' "${_H}")" >&2; exit 1; }
    grep -qi '^pragma: no-cache' "${_H}" || { echo "ERROR: PDF pragma wrong: $(grep -i '^pragma' "${_H}")" >&2; exit 1; }
    grep -qi '^x-content-type-options: nosniff' "${_H}" || { echo "ERROR: PDF missing nosniff" >&2; exit 1; }
    _SIZE="$(wc -c < "${CONTENT_DIR}/${DOC_ID}.pdf" | tr -d '[:space:]')"
    _CL="$(grep -i '^content-length:' "${_H}" | head -1 | sed 's/.*: *//' | tr -d '\r')"
    [ "${_CL}" = "${_SIZE}" ] || { echo "ERROR: PDF content-length ${_CL} != body size ${_SIZE}" >&2; exit 1; }
done
echo "content: both PDFs downloaded with correct headers and content-length"

echo "== content: downloaded PDFs are byte-for-byte the fixture PDFs =="
# Compare the SORTED pair of actual hashes against the SORTED pair of expected
# hashes so the match is one-to-one (two identical copies of first.pdf would not
# pass, even though a naive count of "matched an entry" would reach 2/2).
EXPECTED_PDF_HASHES="$(python -c "import zipfile,sys; z=zipfile.ZipFile(sys.argv[1]); sys.stdout.buffer.write(z.read(sys.argv[2]))" "${FIXTURE}" documents/LAPPe.pdf 2>/dev/null | sha256sum | sed -n 's/^\([0-9a-f]\{64\}\).*/\1/p')"
EXPECTED_PDF_HASHES="${EXPECTED_PDF_HASHES}
$(python -c "import zipfile,sys; z=zipfile.ZipFile(sys.argv[1]); sys.stdout.buffer.write(z.read(sys.argv[2]))" "${FIXTURE}" documents/other.pdf 2>/dev/null | sha256sum | sed -n 's/^\([0-9a-f]\{64\}\).*/\1/p')"
ACTUAL_PDF_HASHES="$(sha256sum "${CONTENT_DIR}/${DOC1}.pdf" | sed -n 's/^\([0-9a-f]\{64\}\).*/\1/p')"
ACTUAL_PDF_HASHES="${ACTUAL_PDF_HASHES}
$(sha256sum "${CONTENT_DIR}/${DOC2}.pdf" | sed -n 's/^\([0-9a-f]\{64\}\).*/\1/p')"
EXPECTED_PDF_SORTED="$(printf '%s\n' "${EXPECTED_PDF_HASHES}" | sort)"
ACTUAL_PDF_SORTED="$(printf '%s\n' "${ACTUAL_PDF_HASHES}" | sort)"
[ "${ACTUAL_PDF_SORTED}" = "${EXPECTED_PDF_SORTED}" ] || { echo "ERROR: downloaded PDF hashes (sorted: ${ACTUAL_PDF_SORTED}) do not one-to-one equal the fixture hashes (sorted: ${EXPECTED_PDF_SORTED})." >&2; exit 1; }
echo "content: both downloaded PDFs byte-for-byte equal fixture PDFs (one-to-one)"

echo "== content: download the source ZIP via the authenticated HTTP endpoint =="
http_download "${ZIP_CONTENT_URL}" "${CONTENT_DIR}/source.zip"
[ "${HTTP_CODE}" = "200" ] || { echo "ERROR: ZIP content returned http ${HTTP_CODE}" >&2; exit 1; }
_H="${CONTENT_DIR}/source.zip.headers"
grep -qi '^content-type: application/zip' "${_H}" || { echo "ERROR: ZIP content-type wrong: $(grep -i '^content-type' "${_H}")" >&2; exit 1; }
grep -qi '^content-disposition: attachment' "${_H}" || { echo "ERROR: ZIP must be served as attachment: $(grep -i '^content-disposition' "${_H}")" >&2; exit 1; }
grep -qi '^cache-control: no-store' "${_H}" || { echo "ERROR: ZIP cache-control wrong" >&2; exit 1; }
grep -qi '^pragma: no-cache' "${_H}" || { echo "ERROR: ZIP pragma wrong" >&2; exit 1; }
grep -qi '^x-content-type-options: nosniff' "${_H}" || { echo "ERROR: ZIP missing nosniff" >&2; exit 1; }
_ZIP_SIZE="$(wc -c < "${CONTENT_DIR}/source.zip" | tr -d '[:space:]')"
_ZIP_CL="$(grep -i '^content-length:' "${_H}" | head -1 | sed 's/.*: *//' | tr -d '\r')"
[ "${_ZIP_CL}" = "${_ZIP_SIZE}" ] || { echo "ERROR: ZIP content-length ${_ZIP_CL} != body size ${_ZIP_SIZE}" >&2; exit 1; }
ZIP_HASH="$(sha256sum "${CONTENT_DIR}/source.zip" | sed -n 's/^\([0-9a-f]\{64\}\).*/\1/p')"
FIXTURE_HASH="$(sha256sum "${FIXTURE}" | sed -n 's/^\([0-9a-f]\{64\}\).*/\1/p')"
[ "${ZIP_HASH}" = "${FIXTURE_HASH}" ] || { echo "ERROR: downloaded ZIP is not byte-for-byte identical to the uploaded ZIP." >&2; exit 1; }
echo "content: downloaded ZIP byte-for-byte identical to the uploaded ZIP"

echo "== Task 10 smoke: soft delete returns 204 =="
http_call DELETE "http://localhost:18080/api/v1/pos-records/${POS_RECORD_ID}"
if [ "${HTTP_CODE}" != "204" ]; then
    echo "ERROR: delete returned http ${HTTP_CODE}: ${HTTP_BODY}" >&2
    exit 1
fi
echo "delete: 204"

echo "== Task 10 smoke: deleted record hidden from detail and search =="
http_get_retry "http://localhost:18080/api/v1/pos-records/${POS_RECORD_ID}" 404 "deleted detail"
printf '%s' "${HTTP_BODY}" | grep -q "POS_RECORD_NOT_FOUND" || { echo "ERROR: deleted detail 404 missing code: ${HTTP_BODY}" >&2; exit 1; }
http_call POST "http://localhost:18080/api/v1/pos-records/search" '{"policyNumber":"POLICY-STACK-001"}'
if [ "${HTTP_CODE}" != "200" ]; then
    echo "ERROR: post-delete search returned http ${HTTP_CODE}: ${HTTP_BODY}" >&2
    exit 1
fi
if printf '%s' "${HTTP_BODY}" | grep -q "\"id\":\"${POS_RECORD_ID}\""; then
    echo "ERROR: deleted record still returned by search: ${HTTP_BODY}" >&2
    exit 1
fi
echo "post-delete: hidden from detail and search"

# --- Task 11: protected content is gone after soft delete ---------------------
echo "== content: after soft delete, document content is a sanitized 404 =="
http_call GET "${PDF_CONTENT_URL}"
[ "${HTTP_CODE}" = "404" ] || { echo "ERROR: deleted document content returned http ${HTTP_CODE}, expected 404: ${HTTP_BODY}" >&2; exit 1; }
printf '%s' "${HTTP_BODY}" | grep -q "DOCUMENT_NOT_FOUND" || { echo "ERROR: deleted document 404 missing DOCUMENT_NOT_FOUND: ${HTTP_BODY}" >&2; exit 1; }
echo "content: deleted document -> 404 DOCUMENT_NOT_FOUND"

echo "== content: after soft delete, source archive content is a sanitized 404 =="
http_call GET "${ZIP_CONTENT_URL}"
[ "${HTTP_CODE}" = "404" ] || { echo "ERROR: deleted source archive returned http ${HTTP_CODE}, expected 404: ${HTTP_BODY}" >&2; exit 1; }
printf '%s' "${HTTP_BODY}" | grep -q "POS_RECORD_NOT_FOUND" || { echo "ERROR: deleted source 404 missing POS_RECORD_NOT_FOUND: ${HTTP_BODY}" >&2; exit 1; }
echo "content: deleted source archive -> 404 POS_RECORD_NOT_FOUND"

if [ -n "${CONTENT_DIR}" ]; then
    rm -rf "${CONTENT_DIR}"
    CONTENT_DIR=""
fi

echo ""
echo "verify-container-stack: ALL CHECKS PASSED"
