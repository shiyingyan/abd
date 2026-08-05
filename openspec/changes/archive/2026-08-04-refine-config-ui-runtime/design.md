## Context

The auto-deploy tool is a Spring Boot + Thymeleaf application with Bootstrap 5 styling. Current implementation uses absolute paths for build work directories, shared git download directories, and manual text input for environment variables. The UI uses basic Bootstrap components without custom theming. See proposal.md for full motivation.

## Goals / Non-Goals

**Goals:**
- Improve multi-user build isolation through per-user temp directories
- Enhance config form UX with relative paths, help tooltips, and smart dropdowns
- Modernize UI with consistent design system and responsive layout
- Simplify environment variable management via system env var selection

**Non-Goals:**
- Full frontend framework migration (stay with Thymeleaf + Bootstrap)
- Internationalization (i18n) support
- Dark mode theming
- Changing database schema
- download all the source code when reference third party,for example bootstrap, not using CDN link

## Decisions

### 1. UI Modernization: Enhanced Bootstrap + Custom CSS

**Decision**: Upgrade Bootstrap to latest 5.3.x, add Bootstrap Icons, and implement custom CSS with CSS custom properties (variables) for theming.

**Rationale**: The existing app already uses Bootstrap 5.3.2. Rather than migrating to a different framework (React/Vue), we enhance the existing stack with:
- Bootstrap Icons for help icons, status indicators, and navigation
- CSS custom properties for color palette, spacing, and shadows
- Custom utility classes for modern card designs, form styling, and animations
- Improved typography with system font stack

**Alternatives considered**:
- Tailwind CSS — requires build tooling, adds complexity to Thymeleaf workflow
- PrimeFaces/Bootstrap Studio — commercial, overkill for this scope
- Full React migration — contradicts "stay with Thymeleaf" constraint

### 2. Build Work Directory: Relative Path with Backend Resolution

**Decision**: Store build work directory as relative path in DB. Backend resolves it relative to the git repo root at build time.

**Rationale**: 
- Relative paths are more portable across different build environments
- Users think in terms of project structure, not absolute server paths
- Backend has access to the git clone location and can resolve correctly
- No DB schema change needed — existing `build_work_dir` column stores the relative path string

**Implementation**:
- Frontend: Input field with placeholder "e.g., src/main" and help icon tooltip
- Backend: `BuildService` resolves `{gitRepoRoot}/{buildWorkDir}` before executing build command
- Validation: Reject paths containing `..` or starting with `/` to prevent path traversal

### 3. Per-User Git Temp Directory Strategy

**Decision**: Use `{systemTempDir}/autodeploy/{username}/{projectName}` as git download directory.

**Rationale**:
- System temp dir (`java.io.tmpdir`) is the standard location for ephemeral files
- Username-based subdirectory ensures isolation between users
- Project name subdirectory allows same user to have separate dirs for different projects
- Same user reusing the same dir enables git incremental fetch (faster builds)

**Implementation**:
```java
String tempBase = System.getProperty("java.io.tmpdir");
String gitDir = Paths.get(tempBase, "autodeploy", currentUser, projectName).toString();
```

**Cleanup**: Directories are NOT auto-deleted after build (to support incremental fetch). A scheduled task cleans dirs older than 7 days.

### 4. Language Version Selection: Installed-Only Dropdown

**Decision**: Config edit page calls Language Runtime API to get installed versions. Dropdown shows only those versions. If empty, show link to Runtime page.

**Rationale**:
- Prevents users from selecting versions that don't exist on the system
- Reduces configuration errors and build failures
- Leverages existing Runtime API (`/api/runtime/installed`)

**Implementation**:
- Frontend: On config edit page load, fetch `GET /api/runtime/installed?language={type}` and populate dropdown
- If response is empty array, display: "No versions installed. [Manage Language Runtime](/runtime)"
- Backend: No change needed — Runtime API already filters to installed versions

### 5. Environment Variable Selection: System Env Var Listing API

**Decision**: Add new API endpoint `GET /api/settings/env-vars` that returns `System.getenv().keySet()` as a sorted JSON array. Frontend shows searchable dropdown.

**Rationale**:
- `System.getenv()` is the standard Java API to list all environment variables
- Returns a `Map<String, String>` — we extract keys and sort alphabetically
- Searchable dropdown improves UX over manual text input
- No security risk — env var names are not sensitive (values are)

**Implementation**:
- Backend: New controller method in `SettingsController`:
  ```java
  @GetMapping("/api/settings/env-vars")
  public List<String> listEnvVars() {
      return System.getenv().keySet().stream().sorted().collect(Collectors.toList());
  }
  ```
- Frontend: Use Bootstrap 5 `<datalist>` or a JS library like Select2 for searchable dropdown

### 6. Help Tooltips: Bootstrap 5 Native Tooltips

**Decision**: Use Bootstrap 5's built-in tooltip component with `data-bs-toggle="tooltip"` attribute.

**Rationale**:
- Already included in Bootstrap — no additional dependency
- Consistent with existing UI component library
- Easy to implement: add attribute + initialize via JS
- Accessible (keyboard navigable, screen reader friendly)

**Implementation**:
```html
<input type="text" name="buildWorkDir" />
<button type="button" class="btn btn-sm btn-outline-secondary"
        data-bs-toggle="tooltip" data-bs-placement="right"
        title="Relative path from project root directory">
  <i class="bi bi-question-circle"></i>
</button>
```
Initialize: `document.querySelectorAll('[data-bs-toggle="tooltip"]').forEach(el => new bootstrap.Tooltip(el))`

### 7. Optional Start/Restart Commands: "No Restart Required" Display

**Decision**: Keep start_command and restart_command as optional in DB (nullable). When both are null, display "No restart required" message in config detail and build logs.

**Rationale**:
- Existing DB schema already allows null — no migration needed
- Clear user feedback prevents confusion about whether deployment succeeded
- Build log message provides audit trail

**Implementation**:
- Frontend (config detail): Check if both commands are empty, show "No restart required" badge
- Backend (BuildService): After successful deployment, if no restart command, log "No restart required" and set deploy status to SUCCESS
- Frontend (build log page): Display the log message as-is

## Risks / Trade-offs

- **[Env Var Listing Performance]** → `System.getenv()` is fast (< 1ms), but if called frequently (e.g., every dropdown open), cache the result for 60 seconds to reduce overhead.

- **[Temp Dir Disk Usage]** → Per-user temp dirs could accumulate over time. → Mitigation: Scheduled cleanup task deletes dirs older than 7 days.

- **[Relative Path Security]** → Users could attempt path traversal (e.g., `../../etc/passwd`). → Mitigation: Validate that resolved path is within git repo root; reject `..` segments.

- **[Bootstrap Upgrade Breaking Changes]** → Bootstrap 5.3.x may have minor breaking changes from current version. → Mitigation: Test all pages after upgrade; use CSS custom properties to isolate theme changes.

- **[Installed Version Detection Accuracy]** → Language Runtime API relies on tool-specific commands (nvm ls, sdk list, etc.) which may fail or return unexpected output. → Mitigation: Graceful fallback — if API fails, show all maintained versions with a warning.
