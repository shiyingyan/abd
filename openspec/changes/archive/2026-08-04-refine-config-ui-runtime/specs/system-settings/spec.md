## MODIFIED Requirements

### Requirement: 环境变量管理 - Select from System Environment Variables

When adding an environment variable reference, the system SHALL provide a dropdown listing existing system environment variables instead of requiring manual text input. The user selects from the list.

#### Scenario: Add Environment Variable Reference

- **WHEN** user clicks "Add Environment Variable" in System Settings
- **THEN** a dialog appears with a searchable dropdown listing all current system environment variables (by key name)

#### Scenario: Select and Save

- **WHEN** user selects an environment variable from the dropdown and clicks Save
- **THEN** the selected variable key is stored as a reference in the system settings
