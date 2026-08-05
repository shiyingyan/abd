## MODIFIED Requirements

### Requirement: 版本选择 - Show Only Installed Versions

The language version selection dropdown SHALL display only versions that are installed on the system. If no versions are installed for a language, the system SHALL show a prompt directing the user to install a version in the system first.

#### Scenario: Installed Versions Available

- **WHEN** user opens the language version dropdown for a language (e.g., Java)
- **THEN** the dropdown lists only the versions currently installed on the system (e.g., "17.0.1", "21.0.2")

#### Scenario: No Versions Installed

- **WHEN** user opens the language version dropdown for a language and no versions are installed
- **THEN** the dropdown shows a message: "No versions installed. Please install a version in your system first." with a link to the Language Runtime page
