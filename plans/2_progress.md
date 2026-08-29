# Task 2 Progress: Docker, MinIO, SQLite infrastructure

**Last updated:** 2026-08-29 ~09:30 UTC+8 (session handoff)
**Status:** All files from the plan are implemented. Two runtime failures remain before the verification sequence can pass. **Not committed yet.**

---

## 1. Task reference

Full requirements: [`plans/2_minio_docker_sqlite.md`](plans/2_minio_docker_sqlite.md).
Implement every step, then run the Step 13 verification order and make a detailed git commit (the user explicitly requested "a detailed commit of your changes").

## 2. Environment (IMPORTANT — differs from the system info)

- **The terminal shell is `cmd.exe`, NOT PowerShell.** PowerShell syntax (`$var`, `;` chaining, `Test-Path`) fails. Use cmd syntax (`&`, `&&`, `if exist`, `dir`, `findstr`, `type`).
- Workspace: `c:/Users/Matt/Documents/github/pos-doc-backend`
- Java: 25.0.4 (Temurin). `javap`/`jshell` live in `C:\Program Files\Java\jdk-25.0.4\bin\` (not on PATH).
- Maven: via wrapper only. Use **`mvnw.cmd`** (not `./mvnw`). Maven 3.9.16.
- Spring Boot **4.1.1** (note the Boot 4 package renames, e.g. `org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest`, `org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration`).
- Docker Desktop v29.7.2, Compose v5.4.0 — installed by the user **during** this task; the daemon was started manually by the user. Docker **is** on PATH now.
- Git: available. `.gitattributes` already sets `*.sh text eol=lf`.
- Local `.m2` is at `C:\Users\Matt\.m2`.

## 3. Files created or changed so far

### Root
- **`pom.xml`** (modified)
  - Added: `spring-boot-starter-actuator` (managed), `org.hibernate.orm:hibernate-community-dialects` (managed), `io.minio:minio:9.0.3`, `org.testcontainers:testcontainers-minio` (test, **no version** — Boot BOM manages `2.0.5`), and — see deviation D2 — `com.squareup.okhttp3:okhttp-jvm:5.3.2`.
  - Kept: `org.xerial:sqlite-jdbc` (runtime, **managed version — user explicitly said to ignore the plan's pinned 3.53.4.0 and keep the managed one**).
  - No PostgreSQL dependency existed; nothing to remove.
  - `spring-boot-maven-plugin` now sets `<finalName>app</finalName>` so `target/app.jar` is the single deterministic runnable JAR (plan Step 5 requires this instead of a `*.jar` glob).
- **`Dockerfile`** (new) — multi-stage, `ARG JAVA_VERSION=25`, `eclipse-temurin:25-jdk` build / `25-jre` runtime, curl + non-root `spring` user, `/data/sqlite` dir, copies `target/app.jar`, `ENTRYPOINT ["java","-jar","/app/app.jar"]`. (Note: `# syntax=docker/dockerfile:1` header line added.)
- **`.dockerignore`** (new) — exactly the plan's list.
- **`.env.example`** (new) — the plan's three example values.
- **`compose.yaml`** (new) — services `minio` (pinned image, `server /data --console-address :9001`, ports 9000/9001, volume `minio-data`, `unless-stopped`), `minio-init` (pinned `mc` image, `/bin/sh -c` retry loop ≤60 attempts ×1s, `mc alias set --quiet local http://minio:9000 $MINIO_ROOT_USER $MINIO_ROOT_PASSWORD`, `mc mb --ignore-existing`, `$${VAR}` escaping, no restart policy, no ports), `backend` (build `.` , 8080, depends on `minio-init` `service_completed_successfully`, env per plan incl. `SQLITE_URL=jdbc:sqlite:/data/sqlite/pos-doc.db`, volume `sqlite-data`, healthcheck curl `/api/v1/actuator/health` 5s/3s/20/20s). Named volumes `minio-data`, `sqlite-data`.
  - **⚠️ Pinned image tags are suspected to be wrong — see failure F1 below.**
- **`scripts/verify-container-stack.sh`** (new, LF endings verified) — POSIX sh, `set -eu`, fixed project name `pos-doc-task2-test` (the script internally uses `-p ${PROJECT_NAME}-$$` to reduce collisions — **this is a small deviation from the plan's fixed project name; decide whether to keep or revert to exactly `pos-doc-task2-test`**), temp env file, EXIT/INT/TERM trap running scoped `docker compose down --volumes --remove-orphans` + env-file deletion, refusal if a container with the project label exists, `config --quiet`, `build backend`, `up --detach --wait`, MinIO live check, backend health `UP` check, dummy-endpoint UUID echo check, `exec backend test -s /data/sqlite/pos-doc.db`, one-shot `docker compose run --rm --no-deps --entrypoint /bin/sh minio-init` with `mc pipe` upload of `minio-persistence-check`, restart minio + bounded health poll + `mc cat` verify, restart backend + health-state poll + re-checks.
- **`.gitignore`** (modified) — added `.env` and `/pos-doc.db*`.
- **`.gitattributes`** (modified) — added `*.sh text eol=lf`.

### src/main
- **`src/main/resources/application.yaml`** (modified) — removed the `autoconfigure.exclude: DataSourceAutoConfiguration` block; added the plan's `spring.datasource` (SQLITE_URL, hik  driver `org.sqlite.JDBC`, hikari pool size 1, the three `data-source-properties`), `spring.jpa` (community `SQLiteDialect`, `ddl-auto: none`, `open-in-view: false`), `storage.minio.*` (four env-var-backed props), and `management` (health endpoint + liveness/readiness probes). Kept app name, context-path `/api/v1`, and the `openapi.pOSDocumentIngestion.base-path: ""` override.
- **`src/main/java/horse/sumomo/pos_doc_backend/infrastructure/sqlite/SQLiteConfiguration.java`** (new) — `@Configuration`; `@EventListener(ApplicationReadyEvent)` runs the three PRAGMAs as separate statements via `JdbcTemplate` on the `DataSource`; failures propagate; no logging of the DB path.
- **`src/main/java/horse/sumomo/pos_doc_backend/infrastructure/minio/MinioProperties.java`** (new) — `@ConfigurationProperties(prefix="storage.minio")`, final class with constructor validation (non-blank ×4, endpoint must parse as HTTP/HTTPS URI), `toString()` masks the secret.
- **`src/main/java/horse/sumomo/pos_doc_backend/infrastructure/minio/MinioConfiguration.java`** (new) — `@Configuration` + `@ConfigurationPropertiesScan`; one `MinioClient` bean built from the properties; no bucket creation, no network at startup.
- **`src/main/java/horse/sumomo/pos_doc_backend/infrastructure/minio/ObjectStorageException.java`** (new) — unchecked wrapper.
- **`src/main/java/horse/sumomo/pos_doc_backend/infrastructure/minio/MinioObjectStorage.java`** (new) — `@Component`, ctor-injects `MinioClient` + `MinioProperties`; `put/get/exists/delete`; blank-key `IllegalArgumentException` before any remote call; `put` rejects negative size / blank content type and passes the exact size; `exists` returns `false` only for `NoSuchKey`/`NoSuchObject` (case-insensitive `ErrorResponse.code()`), everything else → `ObjectStorageException`.
  - **MinIO 9.0.3 API notes (verified with javap, differs from 8.x!):** delete is `RemoveObjectArgs` (not `DeleteObjectArgs`); all operations throw the single checked `io.minio.errors.MinioException`; `putObject(...PutObjectArgs)` with `builder().stream(InputStream, Long, Long)` (note: boxed `Long`, so `-1L`); `getObject` returns `GetObjectResponse` (a `FilterInputStream`, so the `InputStream` return works); `statObject` returns a response (ignore it); `MinioClient.builder()` has `endpoint(String)`, `credentials(String,String)`, `build()`; `client.setTimeout(long,long,long)` exists and is used by the test for short timeouts.

### src/test
- **`PosDocBackendApplicationTests.java`** (rewritten) — `@SpringBootTest` + `@DynamicPropertySource` supplying a temp `SQLITE_URL` (`Files.createTempFile`), `@AfterAll` closes the context (captured via an instance `context` field set in `@BeforeEach`) and deletes the temp db + `-wal`/`-shm`. No MinIO required at startup (client bean is offline).
- **`infrastructure/sqlite/SQLiteIntegrationTest.java`** (new) — same temp-DB/context-close pattern; 3 tests: `sqlite_version()` non-blank, PRAGMAs (`foreign_keys`=1, `journal_mode`=`wal` case-insensitive, `busy_timeout`=5000), create/insert/select/drop a `task2_probe` table.
- **`infrastructure/minio/MinioObjectStorageIntegrationTest.java`** (new) — `@SpringBootTest`; static `MinIOContainer` from `testcontainers-minio` with **explicit image** `minio/minio:RELEASE.2025-10-15T17-29-55Z`, creds via `withUserName`/`withPassword`; `@DynamicPropertySource` starts the container first, bootstraps bucket `pos-documents-test` with a throwaway `MinioClient`, and overrides `storage.minio.*` + `SQLITE_URL`; 4 tests per plan (`putThenGetReturnsIdenticalBytes` — **must declare `throws Exception`** for try-with-resources; `deleteRemovesObject`; `blankObjectKeyIsRejectedWithoutRemoteCall`; `connectionFailureIsNotReportedAsMissingObject` — binds a `ServerSocket(0)` to find a free port, builds a `MinioClient` against it with `setTimeout(1000,1000,1000)`, asserts `ObjectStorageException`). `@AfterAll` stops container + cleans temp db.
  - Testcontainers 2.0.5 API confirmed: `org.testcontainers.containers.MinIOContainer(String dockerImageName)`, `getS3URL()`, `getUserName()`, `getPassword()`.

## 4. Deviations from the plan (with reasons)

- **D1 — SQLite JDBC version:** plan pins `3.53.4.0`; user overrode: keep the Boot-BOM-managed version already in `pom.xml` (no explicit version tag).
- **D2 — added `okhttp-jvm:5.3.2`:** MinIO 9.0.3 depends on OkHttp 5.3.2, whose Maven Central `okhttp` jar is an **empty Kotlin-Multiplatform stub** (3 entries, 767 bytes) because Maven 3 does not consume Gradle module metadata. Without `okhttp-jvm`, compilation fails with `cannot access okhttp3.HttpUrl`. `okhttp-jvm` transitively pulls real `okio-jvm` + `kotlin-stdlib`. This is the standard workaround; must be mentioned in the final report as a deviation.
- **D3 — `finalName=app`:** required by the plan's "exactly one runnable JAR" rule.
- **D4 — script project name:** currently `pos-doc-task2-test-$$` internally (collision safety). Plan says fixed `pos-doc-task2-test`. **Decide before commit.**

## 5. Outstanding failures (block Step 13)

### F1 — Pinned MinIO images 404 on Docker Hub (BLOCKER)
Testcontainers pull failed:
```
Status 404: {"message":"failed to resolve reference \"docker.io/minio/minio:RELEASE.2025-10-15T17-29-55Z\": ... not found"}
```
The plan's pinned tags appear not to exist (at least `minio/minio:RELEASE.2025-10-15T17-29-55Z`). **The `mc` tag `RELEASE.2025-07-16T15-35-03Z` is unverified and must be checked too.**
Next step: query the registry for real tags, e.g.
```
curl -s https://registry.hub.docker.com/v2/minio/minio/tags?pageSize=100   (look for RELEASE.2025-* tags)
curl -s https://registry.hub.docker.com/v2/minio/mc/tags?pageSize=100
```
or `docker pull minio/minio:<candidate>`. Pick the closest real tag ≥ the plan's date, update **both** `compose.yaml` (minio + minio-init) and `MinioObjectStorageIntegrationTest.java` (`MINIO_IMAGE`), and record the substitution as a deviation in the final report.

### F2 — `NoClassDefFoundError: PosRecord` in forked test JVMs (LIKELY TRANSIENT — re-verify)
During a full `mvnw.cmd clean verify`, **all 18 tests** failed to load their Spring context with:
```
Caused by: java.lang.NoClassDefFoundError: PosRecord
Caused by: java.lang.ClassNotFoundException: PosRecord
    at java.base/jdk.internal.loader.BuiltinClassLoader.loadClass(...)
```
(short class name = something asked for a *default-package* `PosRecord` while resolving methods of `DummyPosRecordService`).

Investigation already done (all negative):
- `target/classes/com/yourcompany/pos/api/model/PosRecord.class` exists and is correct (`this_class` = `com/yourcompany/pos/api/model/PosRecord`, major 69).
- `javap` on `DummyPosRecordService.class` shows correct FQ references.
- A `URLClassLoader` probe loading `DummyPosRecordService.getDeclaredMethods()` on the **full test classpath** (`target/test-cp.txt` via `dependency:build-classpath`) → `METHODS OK: 6`.
- A scan of every jar on the test classpath found **no** default-package `PosRecord.class`.
- **Re-running just `mvnw.cmd -q surefire:test -Dtest=ApiSkeletonTest` afterward PASSED (exit 0).** So the failure has not reproduced in isolation.

Hypotheses to try, in order:
1. Just re-run the full `mvnw.cmd clean verify` — it may have been a transient (e.g. file still being written by a concurrent process/AV at classload time). If it passes, F2 is closed.
2. If it recurs: run with `-X` and diff the surefire fork classpath vs `target/test-cp.txt`; check whether `target/classes/.../PosRecord.class` is rewritten *during* the test phase (openapi generator re-runs only in `generate-sources`, so it shouldn't be); watch for a second `target/classes` writer (VS Code's JDT language server compiles into `target/classes` — the user has the Java extension open on exactly these files; **ask the user to close/disable the Java build server, or point it elsewhere, before re-running**).
3. Last resort: add `<testFailureIgnore>` — **NO, forbidden by the plan.** Do not weaken tests.

## 6. Remaining checklist (in order)

1. Fix F1 (real MinIO/mc image tags in `compose.yaml` + `MinioObjectStorageIntegrationTest.java`).
2. Delete probe artifacts before commit: **`probe.jsh`, `probe.bat`, `probe-src/` (whole dir)**, Also `target/test-cp.txt` is under `target/` (gitignored, fine).
3. Decide D4 (script project name) and normalize if needed.
4. `mvnw.cmd clean verify` (F2 re-check; includes all Task 1 tests + 3 SQLite + 4 MinIO + context test).
5. `docker compose --env-file .env.example config --quiet`.
6. `scripts/verify-container-stack.sh` — on Windows run it with **Git Bash** (`bash scripts/verify-container-stack.sh`); it needs `curl` (installed via the Dockerfile only — the script needs a **host** `curl`, which Windows 11 ships at `C:\Windows\System32\curl.exe`, so it's on PATH) and `mktemp` (present in Git Bash).
7. `mvnw.cmd clean verify` again (final).
8. `git status --short` + `git diff -- Dockerfile .dockerignore compose.yaml .env.example pom.xml src scripts .gitignore` — confirm no db files, `.env`, or generated code staged.
9. `git add` the handwritten files only, then a **detailed** commit (user request): suggest title `Add Docker, MinIO, and SQLite infrastructure (Task 2)` with body covering: infra overview, dependency additions incl. the okhttp-jvm workaround, SQLite config + pragmas, MinIO adapter behavior contract, compose services + volumes, verification script, test additions, and the deviations (D1, D2, image-tag substitution for F1).
10. Produce the plan's "Required final report" (7 items) as the completion message.

## 7. Verification commands already run

| Command | Result |
|---|---|
| `mvnw.cmd clean verify` (baseline, before changes) | ✅ 10 tests pass (Task 1) |
| `mvnw.cmd -q -DskipTests compile` | ✅ after okhttp-jvm fix |
| `mvnw.cmd clean verify` (1st full run w/ new tests) | ❌ discovery error — `@BeforeAll` non-static (fixed: `@BeforeEach`) |
| `mvnw.cmd clean verify` (2nd full run) | ❌ F2 (PosRecord) + F1 (MinIO image 404) |
| `mvnw.cmd -q surefire:test -Dtest=ApiSkeletonTest` | ✅ 9 tests pass |
| `docker version` / `docker compose version` | ✅ 29.7.2 / v5.4.0 |

## 8. Notes for the next agent

- `@ConfigurationPropertiesScan` in `MinioConfiguration` is what binds `MinioProperties` (no `@EnableConfigurationProperties` needed).
- Spring Boot 4: `@WebMvcTest` lives at `org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest` (see `ApiSkeletonTest` imports).
- The actuator health endpoint is served at `/api/v1/actuator/health` because it inherits the servlet context path — the compose healthcheck and the verify script already use that URL.
- `MinioObjectStorageIntegrationTest` deliberately does NOT use `@Testcontainers`; the container lifecycle is in the `@DynamicPropertySource` hook (guarantees the container is up before property resolution) with an explicit `@AfterAll` stop — so if Docker is absent it fails fast with Testcontainers' own message (plan requirement).
- Windows cmd: use `&` between commands, `2>nul` to discard errors, `findstr` instead of grep.
- Do NOT edit anything under `target/`, the generated API packages, or `openapi/pos-document-api.openapi.yaml`.
