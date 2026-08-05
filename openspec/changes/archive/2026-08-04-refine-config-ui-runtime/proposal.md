## Why

The auto-deploy tool requires UX improvements for better usability, multi-user isolation, and modern visual design. Current limitations include absolute path requirements, shared git directories, manual environment variable input, and outdated UI styling.

## What Changes

- **Project Configuration**:
  - Build work directory uses relative path from project root (not absolute path)
  - Add help icon with tooltip explaining the path format
  - Git download directory uses per-user temp directory for multi-user isolation; same user reuses same directory across builds
  - Language version selection shows only versions maintained in Language Runtime; link to Runtime page if none maintained
  - Start/Restart commands are optional; if not provided, display "No restart required" message
- **Language Runtime Management**:
  - Version selection dropdown shows only system-installed versions
  - If no versions installed, show prompt directing user to install in system first
- **System Settings**:
  - Environment variable management: select from existing system environment variables (dropdown), no manual input
- **UI Modernization**:
  - Redesign all pages with modern, clean aesthetic
  - Improve visual hierarchy, spacing, and interactive feedback
  - Ensure responsive and fluid layout

## Capabilities

### New Capabilities
- `ui-modernization`: Modernize all frontend pages with contemporary design system, improved UX patterns, and responsive layout

### Modified Capabilities
- `project-config`: Change build work directory to relative path, add help icons, implement per-user temp git directories, add language version selection with runtime links, make start/restart commands optional
- `language-runtime`: Change version selection to show only installed system versions, add installation prompt when none available
- `system-settings`: Change environment variable management to select from system env vars instead of manual input

## Impact

- **Frontend**: All Thymeleaf templates (config edit/list, settings, runtime, build, history pages), CSS/styling overhaul
- **Backend Services**: ConfigService (relative path handling, temp directory logic), LanguageRuntimeService (installed version filtering), SystemSettingsService (env var listing from system)
- **APIs**: Config API responses may include additional metadata (help text, temp dir info), Runtime API returns only installed versions
- **Database**: No schema changes (existing columns accommodate new behavior)
