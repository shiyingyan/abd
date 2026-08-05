## MODIFIED Requirements

### Requirement: 配置编辑 - Build Work Directory

The build work directory field SHALL accept a relative path from the project root directory (not an absolute path). The input field SHALL display a help icon (?) next to it that, when clicked, shows a tooltip explaining the expected format (e.g., "Relative path from project root, e.g., 'src/main'").

#### Scenario: Relative Path Input

- **WHEN** user edits a project configuration
- **THEN** the build work directory field accepts and stores a relative path (e.g., "src/main" instead of "/absolute/path/src/main")

#### Scenario: Help Tooltip Display

- **WHEN** user clicks the help icon next to the build work directory field
- **THEN** a tooltip appears with text: "Relative path from project root directory"

### Requirement: 配置编辑 - Git Download Directory

The system SHALL use a per-user temporary directory for Git repository downloads during builds. Each user's builds use isolated directories to support parallel execution. A single user's multiple builds reuse the same directory.

#### Scenario: Multi-User Isolation

- **WHEN** two different users trigger builds for the same project simultaneously
- **THEN** each user's build uses a separate temp directory (e.g., `/tmp/autodeploy/userA/project` and `/tmp/autodeploy/userB/project`)

#### Scenario: Single User Reuse

- **WHEN** the same user triggers multiple builds of the same project
- **THEN** the builds reuse the same temp directory (e.g., `/tmp/autodeploy/userA/project`)

### Requirement: 配置编辑 - Language Version Selection

The language version field SHALL display a dropdown showing only versions maintained in the Language Runtime module. If no versions are maintained, the dropdown shows a link "Manage Language Runtime" that navigates to the Runtime configuration page.

#### Scenario: Versions Available

- **WHEN** user edits a project config and the Language Runtime has maintained versions for the selected language
- **THEN** the language version dropdown lists those maintained versions

#### Scenario: No Versions Maintained

- **WHEN** user edits a project config and the Language Runtime has no maintained versions for the selected language
- **THEN** the language version field shows a message "No versions maintained" with a hyperlink "Manage Language Runtime" that navigates to `/runtime`

### Requirement: 配置编辑 - Start/Restart Commands

The start command and restart command fields SHALL be optional. When left empty, the system displays a message "No restart required" in the configuration detail view and build logs.

#### Scenario: Commands Provided

- **WHEN** user fills in start and/or restart command fields
- **THEN** the system uses those commands during deployment after build

#### Scenario: Commands Not Provided

- **WHEN** user leaves both start and restart command fields empty
- **THEN** the config detail view shows "No restart required" and the build log includes the message "No restart required" after successful deployment
