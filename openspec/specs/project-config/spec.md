## Purpose

管理项目部署配置信息，支持 MySQL 数据库存储、Web 页面和 HTTP 接口编辑、配置即时生效、字段校验与错误提示，确保配置变更不影响正在进行的构建任务。

## Requirements

### Requirement: 配置存储

系统 SHALL 将所有项目配置存储在 MySQL 数据库中。配置内容包括：项目名称、版本号、Git 仓库地址、代码分支、Git 认证信息引用、构建指令、构建工作目录、部署服务器信息、启动命令、重启命令、服务器连接信息引用。

#### Scenario: 读取配置

- **WHEN** 系统启动或需要读取项目配置
- **THEN** 系统从 MySQL 数据库中加载配置信息

#### Scenario: 数据库未初始化

- **WHEN** 系统首次启动且配置表不存在
- **THEN** 系统自动创建配置表，并提示用户进行配置

### Requirement: 配置即时生效

系统 SHALL 确保通过接口修改的配置立即生效，无需重启系统。配置变更 MUST 不影响正在进行的构建任务。

#### Scenario: 配置修改即时生效

- **WHEN** 用户通过 Web 页面或 HTTP 接口修改项目配置并保存
- **THEN** 系统将配置写入数据库，新配置立即对后续操作生效，无需重启

#### Scenario: 不影响正在进行的构建

- **WHEN** 配置被修改且此时有构建任务正在执行
- **THEN** 正在进行的构建任务使用构建开始时的配置快照继续执行，修改后的配置仅影响新的构建任务

### Requirement: 多渠道配置编辑

系统 SHALL 支持两种配置修改方式：Web 页面编辑和 HTTP 接口修改。

#### Scenario: Web 页面编辑配置

- **WHEN** 用户在 Web 页面修改项目配置并保存
- **THEN** 系统将配置写入数据库，配置即时生效

#### Scenario: HTTP 接口修改配置

- **WHEN** 用户通过 HTTP 接口提交配置修改请求
- **THEN** 系统将配置写入数据库，返回修改结果，配置即时生效

### Requirement: 配置格式校验

系统 SHALL 在保存配置到数据库前进行字段格式校验。当格式错误时，系统 MUST 在前端向用户提示错误信息，且不保存无效配置。

#### Scenario: 配置字段正确

- **WHEN** 用户提交的配置字段完整且格式正确
- **THEN** 系统将配置保存到数据库，前端显示配置生效状态

#### Scenario: 配置字段错误

- **WHEN** 用户提交的配置字段有误（必填字段缺失、格式不合法等）
- **THEN** 系统拒绝保存，前端显示具体的字段错误信息提示用户修正

### Requirement: 配置修改指南

系统 SHALL 提供配置修改的详细指南，帮助用户正确编辑配置。

#### Scenario: 查看配置指南

- **WHEN** 用户在配置页面请求查看修改指南
- **THEN** 系统展示配置字段说明、格式示例和注意事项

### Requirement: 敏感信息保护

配置中的所有认证信息（密码、Token、密钥等）MUST 禁止明文存储。系统 SHALL 通过引用系统环境变量的方式读取敏感信息，配置中仅存储环境变量名。

#### Scenario: 认证信息通过环境变量读取

- **WHEN** 系统需要使用 Git 认证信息或服务器连接密码
- **THEN** 系统根据配置中记录的环境变量名，从系统环境变量中读取实际凭证值

#### Scenario: 环境变量未设置

- **WHEN** 配置引用的环境变量在系统中未设置
- **THEN** 系统提示用户该环境变量未配置，无法执行相关操作

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
