## 1. UI Foundation & Design System

- [x] 1.1 Download Bootstrap 5.3.x CSS and JS files from npm/official release to `src/main/resources/static/vendor/bootstrap/` (bootstrap.min.css, bootstrap.bundle.min.js)
- [x] 1.2 Download Bootstrap Icons CSS and font files from npm/official release to `src/main/resources/static/vendor/bootstrap-icons/` (bootstrap-icons.css, fonts/*.woff2)
- [x] 1.3 Update all Thymeleaf layout templates (`fragments/common.html`, `layout.html`) to reference local Bootstrap assets: `<link th:href="@{/static/vendor/bootstrap/bootstrap.min.css}">` and `<script th:src="@{/static/vendor/bootstrap/bootstrap.bundle.min.js}">`
- [x] 1.4 Update all Thymeleaf templates to reference local Bootstrap Icons: `<link th:href="@{/static/vendor/bootstrap-icons/bootstrap-icons.css}">`
- [x] 1.5 Create CSS custom properties (variables) for color palette, spacing, and shadows in `app.css`
- [x] 1.6 Update all Thymeleaf layout templates to use new design system (typography, cards, shadows)
- [x] 1.7 Implement loading spinner component (reusable across all pages for async operations)
- [x] 1.8 Implement toast notification component for success/error feedback (Bootstrap 5 toast)
- [x] 1.9 Add Bootstrap tooltip initialization script to common.js for all `[data-bs-toggle="tooltip"]` elements
- [x] 1.10 Update navigation bar and sidebar with modern styling (Bootstrap Icons, active states)

## 2. Project Config - Relative Path & Help Tooltips

- [x] 2.1 Update `config/edit.html` - build work directory field: add placeholder "e.g., src/main" and help icon button
- [x] 2.2 Add Bootstrap tooltip to build work directory help icon with text "Relative path from project root directory"
- [x] 2.3 Update `ConfigService.save()` - validate build work directory is relative (reject paths starting with `/` or containing `..`)
- [x] 2.4 Update `BuildService.executeBuild()` - resolve build work directory relative to git repo root: `Paths.get(gitRepoRoot, config.getBuildWorkDir())`
- [x] 2.5 Update `config/list.html` and `config/detail.html` - display build work directory as relative path

## 3. Project Config - Per-User Git Temp Directory

- [x] 3.1 Update `BuildService.cloneOrPull()` - use per-user temp directory: `Paths.get(System.getProperty("java.io.tmpdir"), "autodeploy", currentUser, projectName)`
- [x] 3.2 Update `GitService` - create user-specific temp subdirectory if not exists, reuse existing for same user
- [x] 3.3 Add scheduled cleanup task `TempDirCleanupTask` - delete temp dirs older than 7 days (run daily at 3 AM)
- [x] 3.4 Update build log output - display the temp directory path used for git clone

## 4. Project Config - Language Version Selection

- [x] 4.1 Update `config/edit.html` - language version field: replace text input with `<select>` dropdown
- [x] 4.2 Add JavaScript to fetch installed versions on page load: `GET /api/runtime/installed?language={type}`
- [x] 4.3 Populate language version dropdown with API response; if empty, show "No versions maintained" message with link to `/runtime`
- [x] 4.4 Update `ConfigService.save()` - validate selected language version exists in Runtime module (warn if not found)

## 5. Project Config - Optional Start/Restart Commands

- [x] 5.1 Update `config/edit.html` - start_command and restart_command fields: add placeholder "Optional - leave empty if no restart needed"
- [x] 5.2 Update `config/detail.html` - when both commands are empty, display "No restart required" badge (Bootstrap badge component)
- [x] 5.3 Update `BuildService.deploy()` - after successful upload, if restart command is null/empty, log "No restart required" and set deploy status to SUCCESS
- [x] 5.4 Update `build/log.html` - display "No restart required" message in build log when applicable

## 6. Language Runtime - Installed-Only Version Selection

- [x] 6.1 Update `LanguageRuntimeService.getInstalledVersions()` - ensure API returns only system-installed versions (already implemented, verify)
- [x] 6.2 Update `runtime/index.html` - version check section: if no installed versions, show message "No versions installed. Please install a version in your system first." with link to installation guide
- [x] 6.3 Add frontend feedback when version check returns empty - highlight the language card with warning color

## 7. System Settings - Environment Variable Selection

- [x] 7.1 Add new API endpoint in `SettingsController`: `GET /api/settings/env-vars` returning `System.getenv().keySet()` as sorted JSON array
- [x] 7.2 Add caching to env var listing (60-second TTL) to reduce repeated `System.getenv()` calls
- [x] 7.3 Update `settings/index.html` - "Add Environment Variable" dialog: replace text input with searchable `<datalist>` or Select2 dropdown
- [x] 7.4 Add JavaScript to populate env var dropdown on dialog open: `GET /api/settings/env-vars`
- [x] 7.5 Update save logic - selected value is stored as env var reference key (no manual text input allowed)

## 8. UI Polish - Enhanced Form Components

- [x] 8.1 Update all form pages (`config/edit`, `settings/index`, `runtime/index`) - add consistent form styling (Bootstrap 5 form-floating or form-label groups)
- [x] 8.2 Add inline validation feedback to all form fields (Bootstrap 5 `is-valid`/`is-invalid` classes)
- [x] 8.3 Update all tables (`config/list`, `build/index`, `history/index`) - modern card-based table styling with hover effects
- [x] 8.4 Add hover effects to all buttons and interactive elements (transitions, shadow on hover)
- [x] 8.5 Update error pages (401, 404, 500) with modern design (centered content, icons, clear messaging)

## 9. Responsive & Fluid Layout

- [x] 9.1 Test and fix all pages for tablet viewport (768px) - ensure sidebar collapses, forms stack vertically
- [x] 9.2 Test and fix all pages for mobile viewport (480px) - ensure navigation is accessible, touch targets are adequate
- [x] 9.3 Add responsive utility classes for spacing and typography (e.g., `d-md-none`, `fs-sm`)
- [x] 9.4 Ensure all modal dialogs are responsive and scrollable on small screens

## 10. Testing & Verification

- [x] 10.1 Test relative path validation - verify `..` and absolute paths are rejected, relative paths accepted
- [x] 10.2 Test per-user temp directory isolation - trigger builds as different users, verify separate directories
- [x] 10.3 Test language version dropdown - verify only installed versions appear, empty state shows link (cross-platform OS detection implemented)
- [x] 10.4 Test optional start/restart commands - verify "No restart required" displays when both empty
- [x] 10.5 Test environment variable selection - verify dropdown lists system env vars, selection saves correctly
- [x] 10.6 Test UI across pages - verify consistent design system, responsive behavior, tooltip functionality (all pages return 200)
- [x] 10.7 Test temp dir cleanup task - verify dirs older than 7 days are deleted
