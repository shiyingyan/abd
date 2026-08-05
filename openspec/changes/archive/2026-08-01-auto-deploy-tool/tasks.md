## 1. 项目初始化与基础架构

- [x] 1.1 创建 Maven 项目结构，配置 pom.xml（Spring Boot 2.7、MyBatis-Plus、Thymeleaf、Apache Shiro、MySQL 驱动、HikariCP、JGit、Apache MINA SSHD 依赖）
- [x] 1.2 创建 Spring Boot 主类，配置 application.yml（数据库连接、Thymeleaf、MyBatis-Plus 配置）
- [x] 1.3 创建基础包结构（controller、service、repository、config、model、dto、util）
- [x] 1.4 配置 logback-spring.xml 日志输出（控制台 + 文件），配置 MySQL 数据库连接（HikariCP 连接池，Spring Boot 自动配置）
- [x] 1.5 定义系统工作目录结构（logs/、builds/、data/），实现 SystemInitConfig 启动时自动创建目录
- [x] 1.6 引入 Bootstrap、jQuery 等前端资源，创建基础布局模板 layout.html（导航栏、侧边栏、内容区）

## 2. 用户认证模块

- [x] 2.1 创建 users 表 MySQL DDL（id、username、password（哈希值）、password_salt（随机盐）、created_at、last_login_at），实现数据库初始化脚本 schema.sql
- [x] 2.2 实现 UserRepository（MyBatis-Plus BaseMapper，含 findByUsername 自定义查询）
- [x] 2.3 实现 UserService（注册逻辑：接收前端密文密码，通过 Shiro HashedCredentialsMatcher 使用不可逆哈希算法加盐生成哈希值和盐；登录验证、Shiro 会话创建）
- [x] 2.4 实现 AuthController（注册页面、注册提交、登录页面、登录提交、退出登录）
- [x] 2.5 配置 Apache Shiro 过滤器链（anon/authc 规则），未登录请求重定向登录页或返回 401
- [x] 2.6 配置 Shiro 会话超时时间，实现会话过期自动跳转登录页
- [x] 2.7 创建注册和登录 Thymeleaf 页面（Bootstrap 表单、jQuery 前端校验、前端对密码进行不可逆哈希后以密文传输）

## 3. 项目配置管理模块

- [x] 3.1 创建 project_config 表 MySQL DDL（项目名、版本号、Git 仓库地址、分支、Git 认证环境变量引用、构建指令、构建工作目录、部署服务器信息、启动/重启命令、语言类型、语言版本、自定义安装目录、服务器连接环境变量引用），实现数据库初始化脚本
- [x] 3.2 定义 ProjectConfig 实体类（与数据库表映射）
- [x] 3.3 实现 ConfigRepository（MyBatis-Plus BaseMapper，含自定义查询）
- [x] 3.4 实现 ConfigService（加载配置、保存配置、字段格式校验、默认配置生成）
- [x] 3.5 实现配置即时生效逻辑（配置保存到数据库后立即对后续操作生效，构建任务启动时从数据库读取配置创建快照对象）
- [x] 3.6 实现字段格式校验逻辑（必填字段检查、格式合法性验证，校验失败时拒绝保存并返回错误信息）
- [x] 3.7 实现 ConfigController（配置列表页面、配置编辑保存、HTTP 接口修改配置）
- [x] 3.8 实现环境变量读取工具类（根据配置中的变量名从 System.getenv() 读取实际值，未设置时返回错误提示）
- [x] 3.9 创建配置管理 Thymeleaf 页面（配置列表、编辑表单、字段校验错误提示）
- [x] 3.10 实现配置修改指南页面（配置字段说明、格式示例、注意事项）

## 4. 系统设置模块

- [x] 4.1 创建 system_settings 表 MySQL DDL（最大并发数、日志保留天数、历史保留天数、环境变量名列表、构建服务器配置），实现数据库初始化脚本
- [x] 4.2 实现 SystemSettingsService（读取、保存系统设置，设置变更后即时生效）
- [x] 4.3 实现并发数配置逻辑（校验 1-20 范围，修改后重建线程池）
- [x] 4.4 实现环境变量管理功能（查看已配置变量名列表及状态、添加/删除变量名）
- [x] 4.5 实现日志保留天数和历史保留天数配置功能
- [x] 4.6 实现构建服务器配置功能（地址、端口、用户名、认证环境变量引用、测试连接按钮）
- [x] 4.7 创建系统设置 Thymeleaf 页面（并发数配置表单、环境变量管理界面、保留策略配置、构建服务器配置及测试连接）

## 5. 语言运行时管理模块

- [x] 5.1 定义语言类型枚举（JAVA、GO、NODE、PYTHON）及对应多版本管理工具映射（SDKMAN、gvm、nvm、uv）
- [x] 5.2 实现 LanguageRuntimeService（检查指定语言版本是否已安装、生成版本切换命令前缀）
- [x] 5.3 实现版本安装检查逻辑（调用对应工具的 list/installed 命令解析已安装版本列表）
- [x] 5.4 实现一键安装功能（调用对应工具的 install 命令，通过 SSE 推送安装日志）
- [x] 5.5 实现构建前版本切换命令拼接（根据项目配置的语言和版本，在构建指令前添加版本切换命令）
- [x] 5.6 创建语言运行时管理 Thymeleaf 页面（语言版本列表、安装状态检查、一键安装按钮、安装日志 SSE 展示）
- [x] 5.7 实现自定义安装目录和运行环境支持（读取项目配置中的自定义安装目录，构建时使用指定目录的语言环境；未指定时使用多版本管理工具默认路径）

## 6. 构建管理模块

- [x] 6.1 实现构建线程池管理（ThreadPoolExecutor，核心/最大线程数=配置并发数，LinkedBlockingQueue 队列，支持动态重建）
- [x] 6.2 定义构建任务模型（BuildTask：任务ID、项目配置快照、构建模式、状态、开始/结束时间、日志路径、当前用户）
- [x] 6.3 实现 JGit 仓库克隆/拉取服务（GitService：clone、pull，支持用户名密码/Token/SSH Key 认证，认证信息从环境变量读取）
- [x] 6.4 实现本机构建流程（拉取代码 → 切换语言版本 → 执行构建指令 → 收集构建产物）
- [x] 6.5 实现远程构建服务器构建流程（SSH 连接构建服务器 → 远程执行拉取和构建 → 通过 SCP 下载构建产物到本地临时目录）
- [x] 6.6 实现 ProcessBuilder 子进程管理（设置工作目录、环境变量、合并 stdout/stderr 流、处理 Windows 编码问题）
- [x] 6.7 实现构建日志收集器（读取子进程输出流 → 写入独立日志文件 + 推送到 SSE 阻塞队列）
- [x] 6.8 实现 SSE 日志推送端点（SseEmitter，订阅指定构建任务的日志流，构建完成后关闭连接）
- [x] 6.9 实现构建任务隔离（每个构建任务持有配置快照对象，数据库配置变更不影响进行中的任务）
- [x] 6.10 实现构建状态管理（排队中、构建中、成功、失败，状态流转逻辑）
- [x] 6.11 实现 BuildController（发起构建、查询构建状态、SSE 日志订阅端点、构建任务列表）
- [x] 6.12 创建构建管理 Thymeleaf 页面（构建发起表单、构建任务列表、实时日志 SSE 展示页面）

## 7. 部署管理模块

- [x] 7.1 实现 SSH 连接服务（基于 Apache MINA SSHD：建立连接、执行远程命令、SCP/SFTP 文件上传下载，认证信息从环境变量读取）
- [x] 7.2 实现 DeployService（构建产物上传 → 远程执行启动/重启命令 → 验证程序状态）
- [x] 7.3 实现远程构建产物下载（从构建服务器通过 SCP 下载到本地临时目录，再上传到部署服务器）
- [x] 7.4 实现部署状态管理（部署中、成功、失败，错误信息记录）
- [x] 7.5 实现部署失败处理（上传失败、启动/重启命令失败时的错误记录和用户通知）
- [x] 7.6 在 BuildService 中集成部署流程（构建成功后自动触发部署）

## 8. 构建历史模块

- [x] 8.1 创建 build_records 表 MySQL DDL（id、project_name、package_name、version、repo_url、build_time、user、status、build_mode、log_file_path、deploy_status），实现数据库初始化脚本
- [x] 8.2 实现 BuildRecordRepository（插入记录、分页查询、按条件查询、删除过期记录）
- [x] 8.3 实现 BuildHistoryService（构建完成后保存记录、查询记录、查看详情、关联日志文件）
- [x] 8.4 实现定时清理任务（按配置保留天数清理数据库记录和对应日志文件，使用 @Scheduled 定时任务）
- [x] 8.5 实现 BuildHistoryController（历史列表页面、条件查询、详情查看、日志查看）
- [x] 8.6 创建构建历史 Thymeleaf 页面（查询条件表单、分页记录列表、详情弹窗、日志展示）

## 9. 集成与测试

- [x] 9.1 集成构建→部署→记录全流程（发起构建 → 拉取代码 → 编译 → 上传部署 → 重启 → 保存历史记录）
- [x] 9.2 测试配置即时生效（数据库配置修改后即时生效、不影响进行中构建、字段校验失败拒绝保存）
- [ ] 9.3 测试并发构建（多项目同时构建、超过并发数排队、配置变更隔离）— 需要实际 Git 仓库环境
- [ ] 9.4 测试 SSE 日志推送（实时日志推送、构建完成后连接关闭、断线重连）— 需要实际构建任务
- [x] 9.5 测试用户认证（注册、登录、会话超时、未登录访问控制）
- [ ] 9.6 测试多语言版本隔离（不同项目不同版本同时构建互不影响）— 需要 nvm/uv/SDKMAN 环境
- [ ] 9.7 测试部署流程（本机构建部署、远程构建服务器构建部署、部署失败处理）— 需要 SSH 部署服务器
- [ ] 9.8 测试定时清理（日志 7 天清理、历史记录 90 天清理）— 需要长时间运行验证
