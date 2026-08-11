# AGENTS.md

This file provides guidance to the AI agent when working with code in this repository.

## Build & Run

```bash
mvn clean package          # Build JAR (output: target/auto-deploy.jar)
mvn spring-boot:run        # Run dev server on port 8080
mvn test                   # Run tests
```

No Maven wrapper (`mvnw`) is present; use system `mvn`.

## Code Quality

```bash
mvn spotless:apply         # Format Java files with Google Java Format
mvn spotless:check         # Check formatting without modifying files
mvn checkstyle:check       # Run Checkstyle with Google Checks
```

Java files are auto-formatted via a PostToolUse hook after every Write/Edit.

## Language

UI labels, validation messages, and user-facing strings are in Chinese. Write new UI text in Chinese.

## Environment

- MySQL 8.0+ at `localhost:3306` with database `auto_deploy`
- `MYSQL_PASSWORD` env var required (defaults to `root`)
- App creates `~/.autodeploy/` working directories on first startup
- Language runtime managers (SDKMAN, nvm, gvm, uv) must be pre-installed for runtime version management features

## Branch & Commit Conventions

- Branch naming: `feature/xxx`, `fix/xxx`
- Commit messages in English

## Testing

- Add tests for new service and controller code
- Use H2 embedded database for test profile (`spring-boot-starter-test` and `h2` already in pom.xml)

## Architecture

### Package Structure

```
com.autodeploy
  config/       -- Infrastructure config (Shiro, thread pool, scheduled tasks, MyBatis-Plus pagination)
  controller/   -- Dual-purpose controllers: Thymeleaf pages + REST API in same class
  model/        -- Domain entities (@TableName) + enums (BuildTaskStatus, LanguageType)
  repository/   -- MyBatis-Plus mapper interfaces extending BaseMapper<T>
  service/      -- Business logic, field-injected with @Autowired
  util/         -- EnvVarUtil (reads secrets via System.getenv)
```

### Key Patterns

- **Dual controllers**: Every controller uses `@Controller` (not `@RestController`). Page routes return view name strings; API routes under `/api/` use `@ResponseBody`. No separate page/API controller split.
- **No DTOs**: Domain entities are serialized directly to JSON. No projection or DTO layer.
- **No global exception handler**: No `@ControllerAdvice`. Services return `null` on success or error message strings. Controllers check and add to `Model`.
- **Field injection**: All services use `@Autowired` field injection, not constructor injection.
- **Query construction**: Queries are built in services via `QueryWrapper`, not in repositories.
- **List sorting**: All list queries must use `orderByDesc` by `id` or time field (e.g. `created_at`, `updated_at`, `build_time`) so the newest records appear first. Never use `orderByAsc` for user-facing lists. Exception: build queue processing (`processQueue`) sorts by `priority` DESC then `submit_time` DESC to determine execution order.
- **Form deletes use GET**: `/config/delete/{id}`, `/environment/delete/{id}`, etc. are GET endpoints, not POST/DELETE.
- **No CSRF tokens**: Forms lack CSRF protection despite Shiro auth.

### Build & Deploy Pipeline

`BuildService.executeBuild()` orchestrates the full pipeline:

1. Task created with UUID, `ProjectConfig` snapshot copied, log file created at `~/.autodeploy/logs/`
2. Git clone/pull via JGit (HTTPS token or SSH auth)
3. npm install (Node.js projects only, if `node_modules` missing/stale)
4. Shell preamble (sources `.zshrc`/`.bashrc`, nvm, SDKMAN, gvm) + language version switch + build command
5. Artifact resolution (auto-detect: JAR in `target/`, Node in `dist/`/`build/`/`.next/`, Go executables)
6. Per-server deploy: backup → SCP upload → restart command via SSH
7. `BuildRecord` persisted with status, deploy status, log file path

Build task state lives only in `ConcurrentHashMap<String, BuildTask>` — **lost on restart**.

### Real-Time Log Streaming (SSE)

- `/api/build/sse/{taskId}`: `SseEmitter` with 30-min timeout, backed by `CopyOnWriteArrayList` log buffer (max 10K lines). Late subscribers get buffered lines first.
- `/api/runtime/install`: SSE streaming for language runtime installation (uses raw `new Thread()`, not the managed pool).
- Frontend: `EventSource` for active tasks, `fetch` for completed task log files.

### Security Model

- **Auth**: Apache Shiro, session-based. No authorization model — all authenticated users have identical permissions.
- **Password flow**: Frontend SHA-256 hash → backend Shiro salted SHA-256 (2 iterations, base64). Double-hash pattern.
- **Secrets**: Never stored in DB. Models store env var *names* (`gitAuthEnvKey`, `authEnvKey`); values read at runtime via `System.getenv()`. `EnvVarRef` table is a registry of known env var names.

### Gotchas

- `BuildThreadPoolManager` is in `config/` but acts as a runtime service managing the build thread pool
- `SshService` uses a single shared `SshClient` instance with no `@PreDestroy` cleanup
- N+1 queries in `ServerInfoService` — individual lookups for environment names instead of JOINs
- `LanguageRuntimeController` spawns raw `new Thread()` bypassing the managed pool
- `ConfigService.getSnapshot()` does manual field-by-field copy instead of BeanUtils
- Remote build mode (`REMOTE`) infrastructure exists in settings but is not fully wired into the pipeline
