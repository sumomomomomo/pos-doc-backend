# Task 2: Docker, MinIO, and SQLite infrastructure

## Objective

Extend the existing Spring API skeleton with local infrastructure that can run reproducibly in Docker:

- Build the Spring Boot backend as a non-root Docker image.
- Run the backend and MinIO as separate containers with Docker Compose.
- Provision one MinIO bucket before the backend starts.
- Replace PostgreSQL with a persistent SQLite database used by the backend.
- Add a small MinIO object-storage adapter that is not yet connected to the POS HTTP workflow.
- Add automated SQLite and MinIO integration tests.
- Add an end-to-end script that builds the image, starts the Compose stack, verifies it, and removes only its own test resources.

This task is complete only when all verification commands in this document succeed.

## Important architecture rule

Do **not** run Spring Boot and MinIO in the same container.

Create:

1. One `Dockerfile` for the Spring Boot backend.
2. One `compose.yaml` that runs separate `backend`, `minio`, and one-shot `minio-init` services.

Each long-running container must have one main process. SQLite is not a separate service; it is a file opened by the backend and stored on a backend volume.

## Non-goals

Do not implement any of the following in this task:

- Real POS record persistence or SQL tables
- JPA entities for POS records or documents
- Database migrations for business tables
- Uploading the ZIP from `uploadPosRecord` to MinIO
- Extracting PDFs from ZIP files
- RabbitMQ
- OCR or vLLM
- Google login, JWT validation, users, or roles
- Public document download endpoints
- Production TLS, reverse proxying, or Internet exposure

The Task 1 endpoints must continue returning the same deterministic dummy responses.

## Files that must not be edited

- Do not manually edit files below `target/` or another generated-source directory.
- Do not change generated API interfaces or generated DTOs.
- Do not change the OpenAPI contract in this task.
- Do not weaken or remove the Task 1 tests.

## Required result

The repository must contain this structure, using the project's existing Java base package:

```text
Dockerfile
.dockerignore
compose.yaml
.env.example
scripts/
└── verify-container-stack.sh
src/main/java/<base-package>/
└── infrastructure/
    ├── minio/
    │   ├── MinioConfiguration.java
    │   ├── MinioProperties.java
    │   └── MinioObjectStorage.java
    └── sqlite/
        └── SQLiteConfiguration.java
src/test/java/<base-package>/
└── infrastructure/
    ├── minio/
    │   └── MinioObjectStorageIntegrationTest.java
    └── sqlite/
        └── SQLiteIntegrationTest.java
```

If equivalent files already exist, update them instead of creating duplicates.

## Step 1: Inspect before modifying

From the repository root, run:

```bash
./mvnw clean verify
```

Then inspect:

```text
pom.xml
src/main/resources/application.yaml
src/main/resources/application.yml
src/test/resources/
.gitignore
```

Only one of `application.yaml` and `application.yml` should be authoritative. Update the one already used by the project. Do not create a second equivalent configuration file.

Record the Java version from `pom.xml`. Use that same major version in both Dockerfile stages. Do not change the project's Java or Spring Boot version in this task.

## Step 2: Update Maven dependencies

### Remove PostgreSQL

Remove the PostgreSQL JDBC driver if it exists:

```xml
<groupId>org.postgresql</groupId>
<artifactId>postgresql</artifactId>
```

Remove PostgreSQL-only test dependencies and configuration. Do not leave two JDBC drivers active without a reason.

### Add SQLite

Add Xerial SQLite JDBC version `3.53.4.0`:

```xml
<dependency>
    <groupId>org.xerial</groupId>
    <artifactId>sqlite-jdbc</artifactId>
    <version>3.53.4.0</version>
</dependency>
```

NOTE: ignore the specific version requirement of JDBSQLite JDBC.

If `spring-boot-starter-data-jpa` is already present, keep it and add the Hibernate community dialects artifact without an explicit version when Spring Boot dependency management supplies one:

```xml
<dependency>
    <groupId>org.hibernate.orm</groupId>
    <artifactId>hibernate-community-dialects</artifactId>
</dependency>
```

If Maven reports that the dialect dependency has no managed version, set its version to exactly the Hibernate ORM version resolved for `hibernate-core`. Determine it with:

```bash
./mvnw -q dependency:tree -Dincludes=org.hibernate.orm:hibernate-core
```

Do not introduce a different Hibernate version.

### Add MinIO

Add the MinIO Java SDK:

```xml
<dependency>
    <groupId>io.minio</groupId>
    <artifactId>minio</artifactId>
    <version>9.0.3</version>
</dependency>
```

### Add Actuator

Add Spring Boot Actuator for container health checks:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
```

### Add Testcontainers MinIO

Add this test dependency:

```xml
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>testcontainers-minio</artifactId>
    <version>2.0.5</version>
    <scope>test</scope>
</dependency>
```

If the project already imports Testcontainers `2.0.5` through dependency management, omit only the dependency's `<version>` element. Do not add two different Testcontainers versions.

Use the existing Spring Boot test dependency and JUnit 5. Do not add JUnit 4.

## Step 3: Configure SQLite

Remove any test-only exclusion of `DataSourceAutoConfiguration` that was added in Task 1. The normal application context test must now start with SQLite enabled.

Add this configuration to the existing application YAML, preserving the existing application name, API base-path configuration, and servlet context path:

```yaml
spring:
  datasource:
    url: ${SQLITE_URL:jdbc:sqlite:./pos-doc.db}
    driver-class-name: org.sqlite.JDBC
    hikari:
      maximum-pool-size: 1
      connection-timeout: 5000
      data-source-properties:
        foreign_keys: "true"
        journal_mode: WAL
        busy_timeout: "5000"
  jpa:
    database-platform: org.hibernate.community.dialect.SQLiteDialect
    hibernate:
      ddl-auto: none
    open-in-view: false

management:
  endpoints:
    web:
      exposure:
        include: health
  endpoint:
    health:
      probes:
        enabled: true
```

Merge the `spring` mapping. Do not create a second top-level `spring:` key.

Use `maximum-pool-size: 1` deliberately. The application currently has very low write concurrency, and serializing database access avoids unnecessary SQLite locking behavior during the initial implementation.

Keep the three `data-source-properties`. Unlike a one-time startup statement, JDBC connection properties are applied whenever Hikari creates a replacement connection.

Add this exact entry to `.gitignore`:

```gitignore
/pos-doc.db*
```

The wildcard is required because SQLite WAL mode can create `pos-doc.db-wal` and `pos-doc.db-shm` beside the database.

### `SQLiteConfiguration`

Create `SQLiteConfiguration.java` as a Spring `@Configuration` class.

It must expose one startup component that executes these statements against the configured data source:

```sql
PRAGMA foreign_keys = ON;
PRAGMA journal_mode = WAL;
PRAGMA busy_timeout = 5000;
```

Requirements:

- Use `JdbcTemplate` or a JDBC `Connection` obtained from the configured `DataSource`.
- Execute each PRAGMA as a separate statement.
- Apply them during application startup before business repositories are used.
- Do not create business tables.
- Do not silently catch `SQLException` or startup failures.
- Do not print the absolute database path in logs.

If `spring-boot-starter-data-jpa` is absent, add `spring-boot-starter-jdbc`; otherwise use the JDBC support already brought in by JPA.

## Step 4: Configure the MinIO client

Add this top-level configuration to the existing application YAML:

```yaml
storage:
  minio:
    endpoint: ${MINIO_ENDPOINT:http://localhost:9000}
    access-key: ${MINIO_ACCESS_KEY:local-dev-access}
    secret-key: ${MINIO_SECRET_KEY:local-dev-secret-change-me}
    bucket: ${MINIO_BUCKET:pos-documents}
```

### `MinioProperties`

Implement `MinioProperties` using `@ConfigurationProperties(prefix = "storage.minio")`.

It must contain exactly these four properties:

```text
endpoint
accessKey
secretKey
bucket
```

Validation requirements:

- All four values must be non-blank.
- `endpoint` must parse as an HTTP or HTTPS URI.
- Do not expose the secret key through `toString()`.
- Prefer a validated record if it works with the project's Spring Boot version; otherwise use a final immutable class.

### `MinioConfiguration`

Implement `MinioConfiguration` as a Spring `@Configuration` class that:

- Enables `MinioProperties`.
- Exposes exactly one `MinioClient` bean.
- Builds the client from the configured endpoint, access key, and secret key.
- Does not create a bucket during normal Spring startup.
- Does not log credentials.

Bucket provisioning belongs to `minio-init` in Compose, not to every backend process.

### `MinioObjectStorage`

Implement one Spring `@Component` named `MinioObjectStorage` using constructor injection of `MinioClient` and `MinioProperties`.

It must expose these methods:

```java
void put(String objectKey, InputStream input, long size, String contentType)
InputStream get(String objectKey)
boolean exists(String objectKey)
void delete(String objectKey)
```

Behavior requirements:

- Every operation uses the configured bucket.
- Reject blank object keys with `IllegalArgumentException` before calling MinIO.
- `put` rejects negative sizes and blank content types.
- `put` must provide the known object size to the MinIO SDK.
- `exists` returns `false` only for MinIO's `NoSuchKey`/`NoSuchObject` response. A missing bucket, connection failure, timeout, or authentication failure must throw `ObjectStorageException`.
- Wrap checked MinIO/IO exceptions in one handwritten unchecked exception named `ObjectStorageException`.
- The exception message may contain the object key but must not contain credentials or document contents.
- Do not buffer an entire object into memory.
- Do not connect this component to `uploadPosRecord` yet.

Place `ObjectStorageException.java` in the same MinIO infrastructure package.

## Step 5: Create the backend Dockerfile

Create a root-level file named exactly `Dockerfile`.

Use a multi-stage build. Replace `<JAVA_VERSION>` below with the Java major version already declared in `pom.xml`:

```dockerfile
ARG JAVA_VERSION=<JAVA_VERSION>

FROM eclipse-temurin:${JAVA_VERSION}-jdk AS build
WORKDIR /workspace

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY openapi/ openapi/
RUN chmod +x mvnw
RUN ./mvnw -B -ntp dependency:go-offline

COPY src/ src/
RUN ./mvnw -B -ntp -DskipTests clean package

FROM eclipse-temurin:${JAVA_VERSION}-jre AS runtime
RUN apt-get update \
    && apt-get install --yes --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system spring \
    && useradd --system --gid spring --home-dir /app spring

WORKDIR /app
RUN mkdir -p /data/sqlite && chown -R spring:spring /app /data/sqlite

COPY --from=build --chown=spring:spring /workspace/target/*.jar /app/app.jar

USER spring:spring
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
```

Before accepting this Dockerfile, confirm that `target/*.jar` selects exactly one runnable JAR. If it selects more than one `.jar`, configure the Spring Boot Maven plugin to produce one deterministic final name such as `app.jar`, and copy that exact path. Do not use `find`, shell command substitution, or an arbitrary first match in the Dockerfile.

Do not place MinIO binaries, Docker CLI, source code, Maven, or the JDK in the runtime image.

## Step 6: Create `.dockerignore`

Create a root-level `.dockerignore` containing at least:

```dockerignore
.git
.github
.idea
.vscode
target
plans
*.iml
*.log
.env
pos-doc.db
pos-doc.db-shm
pos-doc.db-wal
```

Do not ignore `openapi`, `.mvn`, `mvnw`, `pom.xml`, or `src` because the build stage requires them.

## Step 7: Create `.env.example`

Create a committed `.env.example` containing only non-production example values:

```dotenv
MINIO_ROOT_USER=local-dev-access
MINIO_ROOT_PASSWORD=local-dev-secret-change-me
MINIO_BUCKET=pos-documents
```

Add `.env` to `.gitignore`. Do not commit a real `.env` file. Do not put production credentials in Compose, source code, tests, logs, or documentation.

## Step 8: Create `compose.yaml`

Create a root-level `compose.yaml` with exactly three services:

```text
backend
minio
minio-init
```

Use these pinned images:

```text
minio/minio:RELEASE.2025-10-15T17-29-55Z
minio/mc:RELEASE.2025-07-16T15-35-03Z
```

### `minio`

Requirements:

- Command: `server /data --console-address :9001`
- Environment:
  - `MINIO_ROOT_USER=${MINIO_ROOT_USER:?MINIO_ROOT_USER is required}`
  - `MINIO_ROOT_PASSWORD=${MINIO_ROOT_PASSWORD:?MINIO_ROOT_PASSWORD is required}`
- Publish API port `9000:9000`.
- Publish local console port `9001:9001`.
- Mount named volume `minio-data` at `/data`.
- Restart policy: `unless-stopped`.
- Do not use `privileged: true`, host networking, or a host bind mount.

### `minio-init`

Requirements:

- Use the pinned `minio/mc` image above.
- Depend on `minio` being started.
- Receive `MINIO_ROOT_USER`, `MINIO_ROOT_PASSWORD`, and `MINIO_BUCKET` as container environment variables.
- Use `/bin/sh -c` with a bounded retry loop of at most 60 attempts and one second between attempts.
- Configure alias `local` for `http://minio:9000` using the root credentials.
- Create `${MINIO_BUCKET}` using `mc mb --ignore-existing`.
- Escape container-side variables as `$${VARIABLE_NAME}` in `compose.yaml` so Compose does not replace them while parsing the command.
- Exit non-zero if MinIO is still unavailable after 60 attempts.
- Have no restart policy and no published ports.
- Do not print credentials.

### `backend`

Requirements:

- Build from the root `Dockerfile`.
- Publish `8080:8080`.
- Depend on `minio-init` with `condition: service_completed_successfully`.
- Set:

```text
SQLITE_URL=jdbc:sqlite:/data/sqlite/pos-doc.db
MINIO_ENDPOINT=http://minio:9000
MINIO_ACCESS_KEY=${MINIO_ROOT_USER:?MINIO_ROOT_USER is required}
MINIO_SECRET_KEY=${MINIO_ROOT_PASSWORD:?MINIO_ROOT_PASSWORD is required}
MINIO_BUCKET=${MINIO_BUCKET:-pos-documents}
```

- Mount named volume `sqlite-data` at `/data/sqlite`.
- Restart policy: `unless-stopped`.
- Add a health check that calls:

```text
http://localhost:8080/api/v1/actuator/health
```

- Health-check interval: 5 seconds.
- Health-check timeout: 3 seconds.
- Health-check retries: 20.
- Health-check start period: 20 seconds.
- Do not publish SQLite or any internal management port.

Declare named volumes exactly:

```yaml
volumes:
  minio-data:
  sqlite-data:
```

Do not add RabbitMQ or PostgreSQL services.

Run this syntax validation before proceeding:

```bash
docker compose --env-file .env.example config --quiet
```

## Step 9: Add `SQLiteIntegrationTest`

Write a JUnit 5 Spring integration test that uses a unique temporary SQLite database file. Do not use the developer's normal `pos-doc.db`.

Requirements:

- Supply a temporary `SQLITE_URL` through `@DynamicPropertySource` or an equivalent Spring test mechanism.
- Start the Spring application context with the real `DataSource` and real `SQLiteConfiguration`.
- Do not mock `DataSource` or `JdbcTemplate`.
- Execute `SELECT sqlite_version()` and assert a non-blank result.
- Assert `PRAGMA foreign_keys` equals `1`.
- Assert `PRAGMA journal_mode` equals `wal`, ignoring case.
- Assert `PRAGMA busy_timeout` equals `5000`.
- Create a test-only table, insert one row, read it back, and assert the value.
- Close the Spring context before deleting the temporary database and its `-wal`/`-shm` files.
- Do not create production business tables in `src/main/resources`.

The test must prove that the Task 1 `DataSourceAutoConfiguration` exclusion has been removed.

## Step 10: Add `MinioObjectStorageIntegrationTest`

Use JUnit 5 and the official Testcontainers MinIO module.

Use this pinned test image:

```text
minio/minio:RELEASE.2025-10-15T17-29-55Z
```

Requirements:

- Start one real `MinIOContainer` for the test class.
- Override the Spring MinIO endpoint and credentials with values from the running container.
- Use a test bucket named `pos-documents-test`.
- Create the bucket explicitly during test setup.
- Autowire and test the real `MinioObjectStorage`; do not call a mocked MinIO client.
- Use unique object keys under `test/`; never reuse a production-like policy number or person's name.

Add these tests:

1. `putThenGetReturnsIdenticalBytes`
   - Upload UTF-8 bytes for `dummy-object-content`.
   - Content type: `application/octet-stream`.
   - Assert `exists` is true.
   - Read the returned stream in a try-with-resources block.
   - Assert the bytes are identical.

2. `deleteRemovesObject`
   - Upload an object.
   - Delete it.
   - Assert `exists` is false.

3. `blankObjectKeyIsRejectedWithoutRemoteCall`
   - Call every public storage operation with a blank key where applicable.
   - Assert `IllegalArgumentException`.

4. `connectionFailureIsNotReportedAsMissingObject`
   - Construct an adapter with a `MinioClient` targeting an unused local port and valid-looking dummy credentials.
   - Call `exists`.
   - Assert `ObjectStorageException`, not `false`.
   - Use a short SDK HTTP timeout so the test completes promptly; do not sleep.

Delete successfully created test objects in cleanup. Testcontainers will remove the container.

If Docker is unavailable, this integration test must fail with a clear prerequisite message rather than silently report success or skip itself.

## Step 11: Preserve and extend application-context testing

Update the existing application context test so it:

- Uses a temporary SQLite file.
- Does not exclude `DataSourceAutoConfiguration`.
- Does not require an externally running MinIO server merely to start the context.
- Does not create or access the normal local `pos-doc.db`.

The MinIO client bean may be created without making a network request. Bucket creation stays outside normal application startup.

All Task 1 controller tests must remain isolated from external MinIO and must continue passing.

## Step 12: Create the whole-stack verification script

Create executable `scripts/verify-container-stack.sh` using POSIX-compatible shell syntax. Start it with:

```sh
#!/bin/sh
set -eu
```

The script must:

1. Verify `docker` and `docker compose` are available.
2. Use the fixed Compose project name `pos-doc-task2-test`.
3. Refuse to start if any container already exists for that project, including a stopped container; do not reuse or delete an existing developer stack.
4. Create a temporary environment file with test-only MinIO credentials and bucket `pos-documents-test`.
5. Pass the temporary file with `--env-file` to every Compose command.
6. Register a trap that runs `docker compose --env-file <temporary-file> -p pos-doc-task2-test down --volumes --remove-orphans` and deletes only that temporary environment file.
7. Run `docker compose config --quiet`.
8. Run `docker compose build backend`.
9. Run `docker compose up --detach --wait`.
10. Assert MinIO liveness with `curl --fail http://localhost:9000/minio/health/live`.
11. Assert backend health with `curl --fail http://localhost:8080/api/v1/actuator/health` and verify the response contains `UP`.
12. Call the Task 1 dummy endpoint using the fixed UUID below and verify the response contains the same UUID:

```text
GET http://localhost:8080/api/v1/pos-records/11111111-1111-1111-1111-111111111111
```

13. Run this check inside the backend container:

```text
test -s /data/sqlite/pos-doc.db
```

14. Use a one-shot `docker compose run --rm --no-deps` invocation of the `minio-init` image, overriding its entrypoint, to upload the exact bytes `minio-persistence-check` to `pos-documents-test/smoke/persistence.txt` with `mc pipe`. Keep credential expansion inside the temporary container and suppress alias output.
15. Restart only the MinIO container.
16. Wait for `http://localhost:9000/minio/health/live` with a bounded retry loop.
17. Use another one-shot `minio-init` invocation with `mc cat` and assert the object still contains exactly `minio-persistence-check`.
18. Restart only the backend container.
19. Wait for it to become healthy again.
20. Re-run the SQLite file check and dummy endpoint check.

The cleanup trap must run on success and failure. It may remove volumes only because they belong to the dedicated `pos-doc-task2-test` project. It must never run an unscoped `docker compose down`, `docker system prune`, `docker volume prune`, or delete another project's resources.

Do not use arbitrary sleeps to declare readiness. Use Compose health state with a bounded timeout.

## Step 13: Verification order

Run these commands in this exact order:

```bash
./mvnw clean verify
docker compose --env-file .env.example config --quiet
./scripts/verify-container-stack.sh
./mvnw clean verify
```

The first and final Maven runs must include:

- Existing Task 1 tests
- SQLite integration tests
- MinIO Testcontainers integration tests
- Application context test

If any command fails:

1. Read the first relevant error.
2. Fix handwritten source, configuration, Docker, or test code.
3. Do not edit generated sources.
4. Do not remove assertions or skip Docker integration tests.
5. Run the full verification sequence again.

Inspect the final diff:

```bash
git status --short
git diff -- Dockerfile .dockerignore compose.yaml .env.example pom.xml src scripts .gitignore
```

No database file, MinIO data, `.env`, generated code, Docker volume data, credential, or test artifact may be committed.

## Acceptance criteria

All conditions must be true:

- The backend image builds from a clean checkout.
- The runtime container runs as a non-root user.
- Backend and MinIO run as different containers.
- `minio-init` creates the configured bucket and exits successfully.
- MinIO data survives a MinIO container restart because it uses `minio-data`.
- SQLite data survives a backend container restart because it uses `sqlite-data`.
- The backend reports healthy at `/api/v1/actuator/health`.
- The SQLite database enables foreign keys, WAL mode, and a 5000 ms busy timeout.
- The MinIO adapter passes real put/get/exists/delete tests against Testcontainers.
- Network failures are not misreported as missing objects.
- Existing dummy API behavior and tests remain unchanged.
- `./mvnw clean verify` succeeds before and after the Compose smoke test.
- The whole-stack script succeeds and removes its own containers and volumes.
- PostgreSQL is absent from Maven dependencies and Compose.
- No real credential or persistent runtime data is committed.

## Required final report

After implementation, report only:

1. Handwritten files created or changed.
2. The exact SQLite JDBC, MinIO Java SDK, Testcontainers, MinIO server, and MinIO client image versions used.
3. The Docker image's Java major version and runtime user.
4. Every verification command executed.
5. Maven test count, failures, errors, and skips.
6. Whether the Compose stack smoke test passed before and after backend restart.
7. Any deviation from this document, with a concrete technical reason.

Do not claim success unless every command in the verification order completed successfully.
