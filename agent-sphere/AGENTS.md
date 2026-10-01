# AgentSphere Backend (agent-sphere)

Stack: Spring Boot 3.4.3, Java 21, Maven multi-module, Lombok. **No Maven wrapper** — use system `mvn`.

## Module layout (DDD layered)

```
agent-sphere-common       — auth/tenant context, error codes, BizException/GlobalExceptionHandler,
  events, chrome-bridge DTOs (ChromeCommandDTO/ChromeCallbackDTO/ChromePendingStore),
  AgentRuntimeProperties
agent-sphere-util         — JsonUtils, JsonSchemaGenerator (victools JSON-schema gen from POJOs)
agent-sphere-infrastructure — MyBatis-Plus config, Flyway, Redis, web config, type handlers,
  interceptors (AuthInterceptor, DataPermissionInterceptor), AuditMetaObjectHandler,
  TraceIdFilter, ControllerLogAspect, CacheService
agent-sphere-runtime      — kernel + orchestration (langgraph4j agent workflow graphs)
agent-sphere-bootstrap    — the ONLY runnable module (spring-boot-maven-plugin),
  Application.java (com.buukle.agent.Application, @ComponentScan "com.buukle.agent", port 8080),
  Flyway migrations, application.yml
```

**Business modules** (`instance`, `model`, `capability`): each split by layer:
`-domain` → `-exception` → `-dtvo` → `-spi` → `-repository` → `-service` → `-controller`
Dependency direction: controller → service → repository → spi/dtvo → domain/exception. `common`+`util` are shared foundations. Controllers depend on capability `*-spi` contracts, NOT implementations.

`capability` has 4 areas: `mcp`, `skill`, `cli`, `builtin` (+ `builtin-tool-spi`, `builtin-tool-webfetch`).

## Commands

```bash
# Build everything (required before most operations)
mvn install -DskipTests

# Run the app (port 8080)
mvn -pl agent-sphere-bootstrap spring-boot:run -am

# After a full build, -am not needed:
mvn -pl agent-sphere-bootstrap spring-boot:run

# Run all tests (fast — no DB/Redis needed, see below)
mvn test

# Run one test class
mvn -pl agent-sphere-bootstrap test -Dtest=SessionControllerTest

# Run one module's tests
mvn -pl agent-sphere-instance/agent-sphere-instance-service test -am
```

## Tests

JUnit 5 + Mockito + MockMvc `standaloneSetup` — **NOT** `@SpringBootTest`. Controller tests mock services via `@ExtendWith(MockitoExtension.class)`. `mvn test` does NOT need Postgres/Redis.

## Runtime prerequisites

PostgreSQL (DB name `buukle_agent_2026061101`) + Redis. `docker-compose.yml` lives at `agent-sphere/agent-docker-middleware/docker-compose.yml` (under this project, **not** the repo root) and starts both, but volume paths are hardcoded to macOS (`/Users/elvin/Desktop/...`) — override or remove volumes on other machines.

Env overrides (defaults): `DB_HOST` (127.0.0.1), `DB_PORT` (5432), `DB_USERNAME` (buukle), `DB_PASSWORD` (buukle123), `REDIS_HOST` (127.0.0.1), `REDIS_PORT` (6379).

**Redis access must go through Redisson** (`RedisClient` bean in `infrastructure/config/RedisConfig`, inject `RedissonClient` or reuse `CacheService`). Do **not** use Spring Data Redis (`StringRedisTemplate` / `RedisTemplate`):

- the connection config lives under the **legacy prefix** `spring.redis.host/port` and is read only by that hand-written `@Value` config;
- Spring Data Redis auto-configuration only binds `spring.data.redis.*` (Boot 3 renamed it), so with only the legacy prefix it **silently falls back to `localhost:6379`** — there is no Redis in the pod, so every read/write fails;
- this once made task-level MCP credentials never get written, surfacing far away as the downstream error `缺少 X-Task-Mcp-Credential`, while the real failure was hidden in a `warn` log.

## Flyway

Migrations: `agent-sphere-bootstrap/src/main/resources/db/migration/V<n>__desc.sql` (currently V1–V75; check the directory for the current highest number before adding a new one). `baseline-on-migrate: true`, baseline 0. Add new `V<n>` files; never edit applied migrations.

## Session data cleanup (disk reclamation)

`SessionCleanupTask` (`instance-service/service/impl`) hard-deletes expired session data on a cron so the DB stops growing. Companion SQL lives in `SessionCleanupMapper` (`instance-repository`) — a **raw-SQL mapper that extends nothing**: it must reach `agent_task` / `agent_completions_call` / `agent_file_store`, which belong to other modules, and `infrastructure` already depends on `instance-service`, so instance must not depend back.

- **Hard delete, deliberately.** This is the one sanctioned exception to "never hard-delete": `agent_llm_interaction_record` stores full request/response/reasoning TEXT, so `@TableLogic` (`UPDATE delete_flag=1`) leaves every byte on disk. Cleanup SQL therefore bypasses MyBatis-Plus on purpose, and deliberately does **not** filter on `delete_flag` — already-logically-deleted rows occupy space too.
- **Delete order is a foreign-key contract.** Only two real FKs exist and neither has `ON DELETE CASCADE`: `agent_run.session_id → agent_session` and `agent_task_artifact.task_id → agent_task`. Method order in the mapper == execution order; `SessionCleanupTaskTest` asserts it with `InOrder`. Reordering breaks production with `violates foreign key constraint`.
- **Expiry = `agent_session.created_at < cutoff AND updated_at < cutoff`**, plus two `NOT EXISTS` guards for `PENDING`/`RUNNING` runs and `QUEUED`/`RUNNING` tasks (a long Bole task only updates `task`/`run` rows, never the session row, so time alone is not enough). `AWAITING_USER` is intentionally **not** guarded — a clarification nobody answers would block cleanup forever.
- **Multi-replica**: `k8s/05-backend.yaml` has `replicas: 2`, so the job takes the Redisson lock `scheduler:session-cleanup` (same pattern as `AuditLogCleanupTask`).
- **Config split**: retention days + kill switch live in `agent_system_config` (`config_group='session'`, keys in `SystemConfigKeys`, seeded by `V75`); cron/batch size/sleep live in `application.yml` under the existing `buukle.agent.session` block. Change retention without a redeploy.
- **Indexes**: `V74` adds the ones cleanup needs (`agent_session(delete_flag,created_at)`, `agent_task(session_id)`, …). Without them the driving query seq-scans.
- **Manual trigger**: `POST /api/v1/instance/session-cleanup` (permission `admin:settings:update`), `dryRun` defaults to **true**. It is synchronous and can take minutes — that is intended, so the caller gets the report back.
- **Disk is not returned to the OS.** Hard delete only frees space for reuse inside PG; the data files stay the same size. The job logs a `VACUUM (FULL, ANALYZE)` hint instead of running it (ACCESS EXCLUSIVE lock).
- Sibling job `AuditLogCleanupTask` stays in `infrastructure/config` — audit logs are that module's domain; only session cleanup moved.

## MyBatis-Plus

- Logic-delete column `delete_flag` (1=deleted, 0=active) — never hard-delete, always set `delete_flag`.
- `map-underscore-to-camel-case` enabled.
- Type handlers package: `com.buukle.agent.infrastructure.handler` (includes `JsonbTypeHandler` for PG jsonb).
- Audit meta auto-filled by `AuditMetaObjectHandler` (creates/updates timestamps and user info).

## Conventions

- JVM default + Jackson timezone: `Asia/Shanghai` (set in `Application.java` and `application.yml`).
- Virtual threads enabled by default (`ENABLE_VIRTUAL_THREADS=true`).
- API prefix: `/api/v1/...`; SSE streaming routes contain `/stream`.
- Logging: `com.buukle` → DEBUG; `*.repository` packages and `com.buukle.agent.capability` → WARN.
- Key libs beyond Spring: langgraph4j, redisson, resilience4j, caffeine, hutool, victools, swagger.

## Code style

- Avoid `Map` as a method input parameter. Use typed DTOs/POJOs so signatures, validation, and refactoring stay explicit. A generic `Map<String, Object>` is only acceptable at framework boundaries (e.g. MyBatis result maps, HTTP param bags) and must be converted to a typed object at the earliest layer.
- No magic values. Reuse existing constants/enums by scope before introducing new ones: method-local → `private static final` on the class → module-wide constants class / `enum`. Only declare a new constant when no suitable one exists in the relevant scope. Never inline raw numbers or strings inside business logic.

## Git

GitHub flow: feature branch off `main` → PR. No CI configured — run `mvn test` before pushing.
