## Purpose

Provide modern, intuitive, and responsive user interface for the auto-deploy tool with consistent design language and improved UX patterns.

## ADDED Requirements

### Requirement: Modern Design System

The system SHALL implement a contemporary design system with consistent typography, color palette, spacing, and component styling across all pages.

#### Scenario: Consistent Visual Language

- **WHEN** user navigates between any pages
- **THEN** all pages share consistent visual elements (fonts, colors, spacing, shadows, rounded corners)

#### Scenario: Responsive Layout

- **WHEN** user accesses the system from different screen sizes (desktop, tablet, mobile)
- **THEN** layout adapts fluidly while maintaining usability and readability

### Requirement: Improved Interactive Feedback

The system SHALL provide clear visual feedback for user interactions including loading states, success/error messages, and hover effects.

#### Scenario: Loading State Indication

- **WHEN** an async operation is in progress (e.g., saving config, starting build)
- **THEN** user sees a loading spinner or progress indicator

#### Scenario: Success/Error Feedback

- **WHEN** an operation completes successfully or fails
- **THEN** user sees a toast notification or inline message with appropriate color coding (green for success, red for error)

### Requirement: Enhanced Form UX

The system SHALL provide modern form components with inline validation, clear labels, and helpful placeholders.

#### Scenario: Inline Validation

- **WHEN** user fills a form field
- **THEN** validation feedback appears immediately below the field (not only on submit)

#### Scenario: Help Text and Tooltips

- **WHEN** user hovers over or clicks a help icon next to a field
- **THEN** a tooltip or popover appears explaining the field's expected format or usage

### Requirement: Local Asset Serving

The system SHALL serve all third-party frontend assets (CSS, JavaScript, fonts) from local static resources instead of external CDN links. This ensures the application works in offline/isolated network environments.

#### Scenario: Bootstrap Assets Served Locally

- **WHEN** any page loads Bootstrap CSS or JavaScript
- **THEN** assets are loaded from `/static/vendor/bootstrap/` directory, not from external CDN

#### Scenario: Bootstrap Icons Served Locally

- **WHEN** any page uses Bootstrap Icons
- **THEN** icon fonts/CSS are loaded from `/static/vendor/bootstrap-icons/` directory, not from external CDN

#### Scenario: No External CDN Dependencies

- **WHEN** the application is deployed in an environment without internet access
- **THEN** all pages render correctly with full styling and functionality, as all assets are bundled locally
