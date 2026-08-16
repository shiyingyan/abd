# Auto-Deploy 系统架构设计文档

> **文档版本**: v1.0  
> **适用对象**: 技术架构师、后端开发工程师、运维工程师  
> **最后更新**: 2026-08-15

---

## 目录

- [1. 系统概述](#1-系统概述)
- [2. 概要设计](#2-概要设计)
  - [2.1 功能视图](#21-功能视图)
  - [2.2 逻辑视图](#22-逻辑视图)
  - [2.3 时序视图](#23-时序视图)
  - [2.4 ER 图](#24-er-图)
  - [2.5 物理视图](#25-物理视图)
- [3. 详细设计](#3-详细设计)
  - [3.1 认证与安全子系统](#31-认证与安全子系统)
  - [3.2 构建调度子系统](#32-构建调度子系统)
  - [3.3 构建执行子系统](#33-构建执行子系统)
  - [3.4 实时日志子系统](#34-实时日志子系统)
  - [3.5 部署子系统](#35-部署子系统)
  - [3.6 构建缓存子系统](#36-构建缓存子系统)
  - [3.7 密钥管理子系统](#37-密钥管理子系统)
  - [3.8 运行时检测子系统](#38-运行时检测子系统)
- [4. 技术债务与已知约束](#4-技术债务与已知约束)
- [5. 附录：技术栈清单](#5-附录技术栈清单)

---

## 1. 系统概述

Auto-Deploy 是一套自托管的轻量级 CI/CD 系统，面向中小团队的持续集成与持续部署场景。系统以 Web UI 为核心交互入口，提供项目配置管理、Git 仓库拉取、多语言构建（Java / Go / Node.js / Python）、构建产物解析、远程服务器部署等端到端能力。

**核心设计目标：**

| 目标 | 设计决策 |
|------|---------|
| 低运维成本 | 单体 Spring Boot 应用，MySQL 唯一外部依赖 |
| 实时可观测 | SSE（Server-Sent Events）推送构建日志 |
| 安全隔离 | 密钥仅存于环境变量，数据库只保存变量名引用 |
| 构建并发 | 优先级队列 + Git Worktree 并行构建 |
| 灵活部署 | 多环境、多服务器、多模块的矩阵式部署拓扑 |

---

## 2. 概要设计

### 2.1 功能视图

功能视图描述系统对外提供的业务能力边界，按用户角色和业务流程组织。

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         Auto-Deploy 功能视图                            │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  ┌─────────────┐ │
│  │  项目配置管理  │  │  构建任务管理  │  │  部署拓扑管理  │  │  系统管理    │ │
│  ├──────────────┤  ├──────────────┤  ├──────────────┤  ├─────────────┤ │
│  │ · 新建/编辑   │  │ · 触发构建    │  │ · 环境管理    │  │ · 并发控制   │ │
│  │ · 复制配置    │  │ · 优先级队列  │  │ · 服务器管理  │  │ · 日志保留   │ │
│  │ · 删除配置    │  │ · 取消/终止   │  │ · 项目关联    │  │ · 历史保留   │ │
│  │ · 模块扫描    │  │ · 实时日志    │  │ · 部署开关    │  │ · 密钥变量   │ │
│  │ · 分支浏览    │  │ · 构建历史    │  │ · 多环境部署  │  │ · 构建服务器  │ │
│  │ · 配置指南    │  │ · 构建缓存    │  │ · 备份策略    │  │ · 调度器控制  │ │
│  │              │  │ · Worktree并行│  │ · 重启脚本    │  │ · 用户管理   │ │
│  └──────────────┘  └──────────────┘  └──────────────┘  └─────────────┘ │
│                                                                         │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐                  │
│  │  运行时管理   │  │  认证与权限   │  │  辅助功能     │                  │
│  ├──────────────┤  ├──────────────┤  ├──────────────┤                  │
│  │ · Java(SDKMAN)│ │ · 用户注册   │  │ · 部署脚本导出 │                  │
│  │ · Go(gvm)    │  │ · 用户登录   │  │ · SSH连接测试  │                  │
│  │ · Node(nvm)  │  │ · 会话持久化 │  │ · 环境变量检测 │                  │
│  │ · Python(uv) │  │ · 会话过期   │  │ · Git状态检测  │                  │
│  │ · 版本安装   │  │              │  │ · 定时清理任务  │                  │
│  │ · Win本地扫描 │  │              │  │               │                  │
│  └──────────────┘  └──────────────┘  └──────────────┘                  │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

**功能模块说明：**

| 功能模块 | 核心能力 | 主要 Service |
|---------|---------|-------------|
| 项目配置管理 | 配置 Git 仓库、构建命令、部署路径、语言版本等构建参数 | `ConfigService` |
| 构建任务管理 | 触发构建、队列调度、并行执行、实时日志推送、历史记录查询 | `BuildService`, `BuildQueueService`, `BuildHistoryService` |
| 部署拓扑管理 | 管理环境（dev/staging/prod）、服务器、项目与环境/服务器的关联矩阵 | `DeployEnvironmentService`, `ServerInfoService` |
| 运行时管理 | 查看已安装版本、安装新版本的 Java/Go/Node.js/Python 运行时 | `LanguageRuntimeService` |
| 系统管理 | 全局并发上限、日志/历史保留策略、密钥变量注册、调度器控制 | `SystemSettingsService` |
| 认证与权限 | 基于 Apache Shiro 的会话认证，JDBC 持久化会话，无细粒度授权模型 | `UserService`, `CustomRealm`, `JdbcSessionDAO` |

---

### 2.2 逻辑视图

逻辑视图描述系统的分层架构和模块划分，体现组件间的依赖关系与职责边界。

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              表现层 (Presentation)                           │
│  Thymeleaf Templates + Bootstrap 5 + jQuery + SSE (EventSource)             │
│  ┌─────────┐ ┌─────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐           │
│  │  build/  │ │ config/ │ │ history/ │ │ settings/│ │   auth/  │           │
│  └─────────┘ └─────────┘ └──────────┘ └──────────┘ └──────────┘           │
└──────────────────────────────────┬──────────────────────────────────────────┘
                                   │ HTTP / SSE
┌──────────────────────────────────▼──────────────────────────────────────────┐
│                              控制层 (Controller)                             │
│  @Controller (Dual: Thymeleaf page routes + @ResponseBody /api/ endpoints)  │
│  ┌────────────────┐ ┌───────────────┐ ┌──────────────┐ ┌────────────────┐  │
│  │BuildController │ │ConfigController│ │BuildHistCtrl │ │SettingsCtrl   │  │
│  │· /build (page) │ │· /config (page)│ │· /history    │ │· /settings    │  │
│  │· /api/build/*  │ │· /api/configs/*│ │· /history/*  │ │· POST /settings│ │
│  │· /api/queue/*  │ │· /api/config/* │ │· /history/api│ │· /api/settings│  │
│  ├────────────────┤ ├───────────────┤ ├──────────────┤ ├────────────────┤  │
│  │ServerInfoCtrl  │ │DeployEnvCtrl  │ │LanguageRtCtrl│ │AuthController  │  │
│  │· /server       │ │· /environment │ │· /runtime    │ │· /login        │  │
│  │· /api/servers  │ │· /api/envs    │ │· /api/runtime│ │· /register     │  │
│  └────────────────┘ └───────────────┘ └──────────────┘ └────────────────┘  │
└──────────────────────────────────┬──────────────────────────────────────────┘
                                   │ 方法调用（@Autowired 字段注入）
┌──────────────────────────────────▼──────────────────────────────────────────┐
│                              业务层 (Service)                                │
│  ┌──────────────┐ ┌──────────────┐ ┌──────────────┐ ┌──────────────────┐  │
│  │ BuildService │ │BuildQueueSvc │ │ ConfigService│ │ DeployService    │  │
│  │ (构建编排)    │ │ (队列调度)    │ │ (配置管理)   │ │ (产物部署)       │  │
│  ├──────────────┤ ├──────────────┤ ├──────────────┤ ├──────────────────┤  │
│  │ GitService   │ │BuildCacheSvc │ │BuildHistSvc  │ │DeployScriptSvc   │  │
│  │ (Git操作)    │ │ (构建缓存)    │ │ (历史查询)   │ │ (脚本导出)       │  │
│  ├──────────────┤ ├──────────────┤ ├──────────────┤ ├──────────────────┤  │
│  │SshService    │ │ModuleScanSvc │ │ServerInfoSvc │ │DeployEnvSvc      │  │
│  │ (SSH/SCP)   │ │ (模块扫描)    │ │ (服务器管理) │ │ (环境管理)       │  │
│  ├──────────────┤ ├──────────────┤ ├──────────────┤ ├──────────────────┤  │
│  │LanguageRtSvc │ │SystemSettSvc │ │ UserService  │ │WinRtScanner    │  │
│  │ (运行时管理)  │ │ (系统设置)    │ │ (用户认证)   │ │ (Win目录扫描)   │  │
│  └──────────────┘ └──────────────┘ └──────────────┘ └──────────────────┘  │
└──────────────────────────────────┬──────────────────────────────────────────┘
                                   │ MyBatis-Plus BaseMapper
┌──────────────────────────────────▼──────────────────────────────────────────┐
│                            数据访问层 (Repository)                           │
│  ┌──────────────┐ ┌──────────────┐ ┌──────────────┐ ┌──────────────────┐  │
│  │ConfigRepo    │ │BuildRecordRepo│ │BuildQueueRepo│ │ServerInfoRepo    │  │
│  ├──────────────┤ ├──────────────┤ ├──────────────┤ ├──────────────────┤  │
│  │UserRepo      │ │DeployEnvRepo │ │ProjEnvSvrRepo│ │ProjectModuleRepo │  │
│  ├──────────────┤ ├──────────────┤ ├──────────────┤ ├──────────────────┤  │
│  │EnvVarRefRepo │ │SysSettingRepo│ │ShiroSessionRepo│                 │  │
│  └──────────────┘ └──────────────┘ └──────────────┘ └──────────────────┘  │
└──────────────────────────────────┬──────────────────────────────────────────┘
                                   │ JDBC
┌──────────────────────────────────▼──────────────────────────────────────────┐
│                              数据层 (Data)                                   │
│  MySQL 8.0 (auto_deploy)    |    文件系统 (~/.autodeploy/)                   │
│  ┌────────────────────┐     |    ┌────────────────────────────────────┐    │
│  │ 11 张业务表         │     |    │ logs/       构建日志文件             │    │
│  │ Flyway 迁移管理     │     |    │ builds/     构建工作目录             │    │
│  └────────────────────┘     |    │ data/       构建缓存 (JSON)         │    │
│                             |    └────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────────────────────┘
```

**横切关注点：**

| 关注点 | 实现方式 |
|--------|---------|
| 认证拦截 | `ShiroConfig` 过滤器链 + `SessionExpiryFilter` |
| 异常处理 | `GlobalExceptionHandler`（窄范围，仅处理 `HttpMessageNotWritableException`） |
| 定时任务 | `@EnableScheduling` + `ScheduledCleanupTask` + `TempDirCleanupTask` |
| 系统初始化 | `SystemInitConfig`（`ApplicationRunner`）创建运行时目录 |
| 分页支持 | `MyBatisPlusConfig` 注册 MySQL 方言分页拦截器 |
| 线程池管理 | `BuildThreadPoolManager` 动态管理构建执行线程池 |

---

### 2.3 时序视图

时序视图描述核心业务场景下各组件间的交互顺序，聚焦关键路径上的调用链。

#### 2.3.1 构建任务提交与执行时序

```
用户(Browser)     BuildController    BuildQueueService    BuildService     BuildThreadPool    GitService    DeployService
     │                  │                  │                  │                 │                │              │
     │ POST /api/build/ │                  │                  │                 │                │              │
     │ start(configId)  │                  │                  │                 │                │              │
     │─────────────────▶│                  │                  │                 │                │              │
     │                  │ submitTask()     │                  │                 │                │              │
     │                  │─────────────────▶│                  │                 │                │              │
     │                  │                  │                  │                 │                │              │
     │                  │                  │ ① 检查并发冲突     │                 │                │              │
     │                  │                  │ ② 检查服务器重叠   │                 │                │              │
     │                  │                  │                  │                 │                │              │
     │                  │                  │──┐ 无冲突:直接执行  │                │                │              │
     │                  │                  │  │               │                 │                │              │
     │                  │                  │  │ startBuild()  │                 │                │              │
     │                  │                  │  │───────────────▶                 │                │              │
     │                  │                  │  │               │ submit(task)    │                │              │
     │                  │                  │  │               │────────────────▶│                │              │
     │                  │                  │  │               │                 │ executeBuild() │              │
     │                  │                  │  │               │                 │───────────────▶│              │
     │                  │                  │  │               │                 │ cloneOrPull()  │              │
     │                  │                  │  │               │                 │───────────────▶│              │
     │                  │                  │  │               │                 │◀───────────────│              │
     │                  │                  │  │               │                 │ build command  │              │
     │                  │                  │  │               │                 │───────────────▶│              │
     │                  │                  │  │               │                 │ resolve artifact              │
     │                  │                  │  │               │                 │ deploy()       │              │
     │                  │                  │  │               │                 │──────────────────────────────▶│
     │                  │                  │  │               │                 │                │              │
     │                  │                  │──┘               │                 │                │              │
     │                  │                  │                  │                 │                │              │
     │                  │                  │──┐ 有服务器重叠:   │                │                │              │
     │                  │                  │  │ 创建 worktree  │                │                │              │
     │                  │                  │  │ 并行执行       │                │                │              │
     │                  │                  │──┘               │                 │                │              │
     │                  │                  │                  │                 │                │              │
     │                  │                  │──┐ 达到并发上限:  │                 │                │              │
     │                  │                  │  │ 入队等待       │                 │                │              │
     │                  │                  │  │ INSERT QUEUED  │                 │                │              │
     │                  │                  │──┘               │                 │                │              │
     │                  │◀─────────────────│                  │                 │                │              │
     │◀─────────────────│ taskId/queueId   │                  │                 │                │              │
```

#### 2.3.2 SSE 实时日志推送时序

```
Browser(SSE Client)    BuildController    BuildService    BuildTask        SseEmitter
      │                    │                  │               │                │
      │ GET /api/build/    │                  │               │                │
      │ sse/{taskId}       │                  │               │                │
      │───────────────────▶│                  │               │                │
      │                    │ subscribeLog()   │               │                │
      │                    │─────────────────▶│               │                │
      │                    │                  │ new SseEmitter │                │
      │                    │                  │──────────────▶│                │
      │                    │                  │               │ 发送历史缓冲     │
      │                    │                  │               │ (最近200行)     │
      │                    │◀─────────────────│◀──────────────│                │
      │◀───────────────────│ SSE event stream │               │                │
      │                    │                  │               │                │
      │                    │                  │  构建线程       │                │
      │                    │                  │  pushLog(line) │                │
      │                    │                  │──────────────▶│                │
      │                    │                  │               │ send(event)    │
      │◀───────────────────────────────────────────────────────────────────────│
      │                    │                  │               │                │
      │  (构建完成)         │                  │               │                │
      │                    │                  │ completeEmitters()              │
      │                    │                  │──────────────▶│                │
      │◀───────────────────────────────────────────────────────────────────────│
      │ SSE connection closed                                                  │
```

#### 2.3.3 认证与会话时序

```
Browser              AuthController    Shiro Subject    CustomRealm      UserRepository    JdbcSessionDAO
  │                      │                │                │                 │                │
  │ POST /login          │                │                │                 │                │
  │ (username,           │                │                │                 │                │
  │  sha256(password))   │                │                │                 │                │
  │─────────────────────▶│                │                │                 │                │
  │                      │ Subject.login()│                │                 │                │
  │                      │───────────────▶│                │                 │                │
  │                      │                │ doGetAuthInfo()│                 │                │
  │                      │                │───────────────▶│                 │                │
  │                      │                │                │ findByUsername()│                │
  │                      │                │                │────────────────▶│                │
  │                      │                │                │◀────────────────│                │
  │                      │                │                │ User entity     │                │
  │                      │                │◀───────────────│                 │                │
  │                      │                │ SimpleAuthInfo │                 │                │
  │                      │                │ (hash+salt)    │                 │                │
  │                      │                │                │                 │                │
  │                      │                │ HashedCredentialsMatcher         │                │
  │                      │                │ SHA-256 x2 + base64 compare      │                │
  │                      │                │                │                 │                │
  │                      │                │ createSession()│                 │                │
  │                      │                │──────────────────────────────────────────────────▶│
  │                      │                │                │                 │  serialize     │
  │                      │                │                │                 │  + base64      │
  │                      │                │                │                 │  INSERT/UPDATE │
  │                      │◀───────────────│                │                 │                │
  │◀─────────────────────│ 302 /build     │                │                 │                │
  │                      │                │                │                 │                │
  │ 后续请求 (JSESSIONID)│                │                │                 │                │
  │─────────────────────▶│                │                │                 │                │
  │                      │                │ SessionExpiryFilter              │                │
  │                      │                │ → 验证会话有效性  │                │                │
  │                      │                │ → JdbcSessionDAO.doReadSession() │                │
  │                      │                │──────────────────────────────────────────────────▶│
  │                      │                │                │                 │  deserialize   │
```

#### 2.3.4 部署执行时序

```
BuildService          DeployService      ArtifactResolver    SshService         Remote Server
     │                    │                   │                 │                   │
     │ deploy(task,       │                   │                 │                   │
     │  config, modules)  │                   │                 │                   │
     │───────────────────▶│                   │                 │                   │
     │                    │                   │                 │                   │
     │                    │  per module:      │                 │                   │
     │                    │ resolve artifacts  │                 │                   │
     │                    │──────────────────▶│                 │                   │
     │                    │◀──────────────────│                 │                   │
     │                    │ List<Path>        │                 │                   │
     │                    │                   │                 │                   │
     │                    │  per env → per server:              │                   │
     │                    │                   │                 │                   │
     │                    │ executeCommand()  │                 │                   │
     │                    │ "backup existing" │                 │                   │
     │                    │─────────────────────────────────────▶│                   │
     │                    │                   │                 │ SSH exec          │
     │                    │                   │                 │──────────────────▶│
     │                    │                   │                 │◀──────────────────│
     │                    │                   │                 │                   │
     │                    │ uploadFile()      │                 │                   │
     │                    │─────────────────────────────────────▶│                   │
     │                    │                   │                 │ SCP transfer      │
     │                    │                   │                 │──────────────────▶│
     │                    │                   │                 │◀──────────────────│
     │                    │                   │                 │                   │
     │                    │ executeCommand()  │                 │                   │
     │                    │ "restart command" │                 │                   │
     │                    │─────────────────────────────────────▶│                   │
     │                    │                   │                 │ SSH exec          │
     │                    │                   │                 │──────────────────▶│
     │                    │                   │                 │◀──────────────────│
     │                    │                   │                 │                   │
     │◀───────────────────│                   │                 │                   │
```

#### 2.3.5 构建队列调度时序

```
@Scheduled(5s)      BuildQueueService     BuildService      DB(build_queue_task)
     │                   │                    │                    │
     │ processQueue()    │                    │                    │
     │──────────────────▶│                    │                    │
     │                   │ SELECT WHERE       │                    │
     │                   │ status=QUEUED      │                    │
     │                   │ ORDER BY priority  │                    │
     │                   │ DESC, submit_time  │                    │
     │                   │ DESC              │                    │
     │                   │────────────────────────────────────────▶│
     │                   │◀────────────────────────────────────────│
     │                   │ List<QueueTask>    │                    │
     │                   │                    │                    │
     │                   │  per task:         │                    │
     │                   │  ① 检查服务器冲突    │                   │
     │                   │  ② 无冲突 → 执行     │                   │
     │                   │                    │                    │
     │                   │ UPDATE status=     │                    │
     │                   │ EXECUTING          │                    │
     │                   │────────────────────────────────────────▶│
     │                   │                    │                    │
     │                   │ startBuild()       │                    │
     │                   │───────────────────▶│                    │
     │                   │◀───────────────────│                    │
     │                   │                    │                    │
     │                   │  轮询等待完成        │                    │
     │                   │  UPDATE status=    │                    │
     │                   │  SUCCESS/FAILURE   │                    │
     │                   │────────────────────────────────────────▶│
     │                   │                    │                    │
     │                   │  3次空轮询后自动停止  │                    │
     │  (除非 always-on) │                    │                    │
```

---

### 2.4 ER 图

ER 图描述系统持久化数据模型及其关联关系。

```
┌─────────────────────┐       ┌──────────────────────────┐       ┌─────────────────────┐
│      users           │       │     project_config       │       │  deploy_environment  │
├─────────────────────┤       ├──────────────────────────┤       ├─────────────────────┤
│ PK id          INT  │       │ PK id              INT  │       │ PK id           INT  │
│    username    VARCHAR│      │    project_key     VARCHAR│      │    name       VARCHAR│
│    password    VARCHAR│      │    project_name    VARCHAR│      │    created_at DATETIME│
│    password_salt VARCHAR│    │    git_repo_url    VARCHAR│      └──────────┬──────────┘
│    status      INT  │       │    git_branch      VARCHAR│                 │
│    created_at  DATETIME│    │    git_auth_env_key VARCHAR│                │
│    last_login_at DATETIME│  │    build_command   VARCHAR│                │
└─────────────────────┘       │    build_work_dir  VARCHAR│                │
                              │    language_type   VARCHAR│                │
         ┌──────────────┐     │    language_version VARCHAR│               │
         │ shiro_session│     │    deploy_target_path VARCHAR│              │
         ├──────────────┤     │    deploy_restart_cmd VARCHAR│              │
         │ PK session_id│     │    deploy_backup    BOOLEAN│               │
         │    session_data TEXT│    artifact_pattern VARCHAR│               │
         │    last_access DATETIME│ artifact_excl   VARCHAR│               │
         │    expire_at  DATETIME│ install_dir      VARCHAR│               │
         └──────────────┘     │    script_dir      VARCHAR│               │
                              │    last_module_scan DATETIME│              │
                              └──────────┬───────────┘                │
                                         │                            │
              ┌──────────────────────────┼────────────────────────────┘
              │                          │
              ▼                          ▼
┌─────────────────────────┐    ┌─────────────────────────┐    ┌─────────────────────┐
│     build_records        │    │    project_module        │    │  project_env_server  │
├─────────────────────────┤    ├─────────────────────────┤    ├─────────────────────┤
│ PK id             INT   │    │ PK id              INT  │    │ PK id          INT  │
│    config_id      INT ──┤    │    project_id  INT ─────┤    │    project_id  INT ─┤──FK→project_config
│    status         VARCHAR│   │    module_key  VARCHAR  │    │    environment_id INT┤──FK→deploy_environment
│    deploy_status  VARCHAR│   │    module_name VARCHAR  │    │    server_id   INT ─┤──FK→server_info
│    git_hash       VARCHAR│   │    module_path VARCHAR  │    │    deploy_enabled TINYINT
│    log_file       VARCHAR│   │    parent_module_key     │    └─────────────────────┘
│    started_at     DATETIME│  │    scanned_at DATETIME  │
│    completed_at   DATETIME│  └─────────────────────────┘
│    selected_modules TEXT│
│    selected_envs  TEXT  │    ┌─────────────────────────┐
│    auto_deploy    TINYINT│   │     build_queue_task     │
│    artifact_paths TEXT  │    ├─────────────────────────┤
│    build_server_host VARCHAR│ │ PK id              INT  │
│    queue_task_id  INT ──┤──┐│    config_id      INT ───┤──FK→project_config
└─────────────────────────┘  │├─────────────────────────┤
                             ││    status         INT   │
                             ││    priority       INT   │
                             ││    submit_time    DATETIME│
                             ││    start_time     DATETIME│
                             ││    completion_time DATETIME│
                             ││    build_record_id INT ──┤──FK→build_records
                             ││    build_task_id  VARCHAR│
                             ││    worktree_path  VARCHAR│
                             ││    error_message  TEXT   │
                             │└─────────────────────────┘
                             │
┌─────────────────────┐      │
│     server_info      │      │
├─────────────────────┤      │
│ PK id          INT  │      │
│    name        VARCHAR│    │
│    host        VARCHAR│    │
│    port        INT  │      │
│    user        VARCHAR│    │
│    auth_env_key VARCHAR│   │
│    server_type VARCHAR│    │
│    environment_id INT┤──┘
└─────────────────────┘

┌─────────────────────┐    ┌─────────────────────┐
│    system_settings   │    │    env_var_refs      │
├─────────────────────┤    ├─────────────────────┤
│ PK setting_key VARCHAR│   │ PK var_key    VARCHAR│
│    setting_value VARCHAR│  │    description VARCHAR│
│    description  VARCHAR│  └─────────────────────┘
└─────────────────────┘
```

**关联关系说明：**

| 关系 | 类型 | 说明 |
|------|------|------|
| `project_config` → `build_records` | 1:N | 一个项目配置产生多条构建记录 |
| `project_config` → `project_module` | 1:N | 一个项目配置包含多个子模块 |
| `project_config` → `project_env_server` | 1:N | 一个项目配置关联多个环境-服务器映射 |
| `deploy_environment` → `server_info` | 1:N | 一个环境包含多台服务器 |
| `deploy_environment` → `project_env_server` | 1:N | 一个环境被多个项目关联 |
| `server_info` → `project_env_server` | 1:N | 一台服务器参与多个项目的部署 |
| `build_records` → `build_queue_task` | 1:1 | 一条构建记录关联一个队列任务 |
| `project_config` → `build_queue_task` | 1:N | 一个项目配置产生多个队列任务 |

**内存态实体（非持久化）：**

| 实体 | 存储位置 | 说明 |
|------|---------|------|
| `BuildTask` | `ConcurrentHashMap<String, BuildTask>` | 运行中/已完成的构建任务，含 SSE Emitter 列表和日志缓冲。**应用重启后丢失** |
| `BuildCacheEntry` | `build-cache.json` 文件 | 构建缓存，通过 configId 索引，重启时从文件加载 |

---

### 2.5 物理视图

物理视图描述系统的部署拓扑、运行时进程结构和外部依赖。

```
┌──────────────────────────────────────────────────────────────────────────────┐
│                          Auto-Deploy 物理部署视图                             │
├──────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  ┌─────────────────────────────────────────────────────────────────┐        │
│  │                    Auto-Deploy 主机 (单节点)                     │        │
│  │                                                                 │        │
│  │  ┌──────────────────────────────────────────────────────┐      │        │
│  │  │              JVM Process (Spring Boot)                │      │        │
│  │  │              java -jar auto-deploy.jar                │      │        │
│  │  │              Port: 8081                               │      │        │
│  │  │                                                       │      │        │
│  │  │  ┌─────────────────────────────────────────────────┐ │      │        │
│  │  │  │            Tomcat Embedded (HTTP)                │ │      │        │
│  │  │  │  ┌────────────┐  ┌────────────┐  ┌───────────┐ │ │      │        │
│  │  │  │  │ Page Routes│  │ REST /api/ │  │ SSE Stream│ │ │      │        │
│  │  │  │  │ (Thymeleaf)│  │ (@Response)│  │ (Emitter) │ │ │      │        │
│  │  │  │  └────────────┘  └────────────┘  └───────────┘ │ │      │        │
│  │  │  └─────────────────────────────────────────────────┘ │      │        │
│  │  │                                                       │      │        │
│  │  │  ┌──────────────┐  ┌──────────────┐  ┌───────────┐  │      │        │
│  │  │  │ Build Thread  │  │ Build Thread  │  │ @Scheduled│  │      │        │
│  │  │  │ Pool (max 20) │  │ Pool (max 20) │  │  Tasks    │  │      │        │
│  │  │  └──────────────┘  └──────────────┘  └───────────┘  │      │        │
│  │  │                                                       │      │        │
│  │  │  ┌──────────────────────────────────────────────────┐│      │        │
│  │  │  │              JGit + CLI Git                       ││      │        │
│  │  │  │   clone / pull / worktree / branch / fetch       ││      │        │
│  │  │  └──────────────────────────────────────────────────┘│      │        │
│  │  │                                                       │      │        │
│  │  │  ┌──────────────────────────────────────────────────┐│      │        │
│  │  │  │           Apache MINA SSHD Client                ││      │        │
│  │  │  │   SSH exec / SCP upload / SCP download           ││      │        │
│  │  │  └──────────────────────────────────────────────────┘│      │        │
│  │  └──────────────────────────────────────────────────────┘      │        │
│  │                          │                                      │        │
│  │  ┌───────────────────────▼──────────────────────────────┐      │        │
│  │  │              文件系统 (~/.autodeploy/)                  │      │        │
│  │  │  logs/     构建日志文件 (*.log)                         │      │        │
│  │  │  builds/   项目源码工作目录 (git clone target)          │      │        │
│  │  │  data/     build-cache.json, 扫描临时目录               │      │        │
│  │  └──────────────────────────────────────────────────────┘      │        │
│  │                                                                 │        │
│  └─────────────────────────────────────────────────────────────────┘        │
│                          │                                                   │
│              ┌───────────┼───────────┐                                      │
│              ▼           ▼           ▼                                       │
│  ┌──────────────┐ ┌──────────┐ ┌──────────────────────────────────────┐    │
│  │  MySQL 8.0   │ │  Git远程  │ │         目标部署服务器集群             │    │
│  │  localhost    │ │  仓库    │ │                                      │    │
│  │  :3306        │ │ (GitHub/ │ │  ┌────────┐ ┌────────┐ ┌────────┐  │    │
│  │  auto_deploy  │ │  GitLab/ │ │  │ Dev    │ │Staging │ │  Prod  │  │    │
│  │               │ │  Gitee)  │ │  │ Server │ │ Server │ │ Server │  │    │
│  │  11 tables    │ │          │ │  │        │ │        │ │        │  │    │
│  │  Flyway       │ │  HTTPS/  │ │  │ SSH:22 │ │ SSH:22 │ │ SSH:22 │  │    │
│  └──────────────┘ │  SSH     │ │  └────────┘ └────────┘ └────────┘  │    │
│                    └──────────┘ └──────────────────────────────────────┘    │
│                                                                              │
│  ┌──────────────────────────────────────────────────────────────────┐       │
│  │                   语言运行时管理器（预安装要求）                    │       │
│  │                                                                   │       │
│  │  ┌─────────────┐  ┌──────────┐  ┌──────────┐  ┌──────────────┐ │       │
│  │  │  SDKMAN     │  │   gvm    │  │   nvm    │  │     uv       │ │       │
│  │  │  (Java)     │  │  (Go)    │  │ (Node.js)│  │  (Python)    │ │       │
│  │  │  Unix only  │  │ Unix only│  │          │  │              │ │       │
│  │  └─────────────┘  └──────────┘  └──────────┘  └──────────────┘ │       │
│  │                                                                   │       │
│  │  Windows: 扫描本地目录 (Program Files, JAVA_HOME, .jdks 等)       │       │
│  │  检测已安装的 Java/Go 版本，无需额外版本管理工具                    │       │
│  └──────────────────────────────────────────────────────────────────┘       │
│                                                                              │
│  ┌──────────────────────────────────────────────────────────────────┐       │
│  │                        环境变量（密钥）                            │       │
│  │                                                                   │       │
│  │  MYSQL_PASSWORD     数据库连接密码                                 │       │
│  │  GIT_TOKEN          Git 仓库访问令牌（HTTPS模式）                   │       │
│  │  SSH_PASSWORD_*     各服务器 SSH 密码                              │       │
│  │  (自定义变量名)      通过 EnvVarRef 表注册                          │       │
│  └──────────────────────────────────────────────────────────────────┘       │
│                                                                              │
└──────────────────────────────────────────────────────────────────────────────┘
```

**进程间通信协议：**

| 连接 | 协议 | 端口 | 说明 |
|------|------|------|------|
| Browser → Auto-Deploy | HTTP / SSE | 8081 | 页面访问 + API 调用 + 日志流 |
| Auto-Deploy → MySQL | JDBC | 3306 | MyBatis-Plus ORM 访问 |
| Auto-Deploy → Git 远程 | HTTPS / SSH | 443 / 22 | JGit clone/pull/fetch |
| Auto-Deploy → 部署服务器 | SSH / SCP | 22 | MINA SSHD 远程执行和文件传输 |

---

## 3. 详细设计

### 3.1 认证与安全子系统

#### 3.1.1 密码处理流水线

```
用户输入明文密码
    │
    ▼
[浏览器] Web Crypto API → SHA-256 Hash → 64字符十六进制字符串
    │
    │ POST /login { username, password: sha256_hex }
    ▼
[服务端] Shiro HashedCredentialsMatcher
    │  1. 从 DB 加载 User (password, password_salt)
    │  2. 将 sha256_hex + salt 进行 SHA-256 哈希
    │  3. 迭代 2 次
    │  4. Base64 编码
    │  5. 与 DB 中存储的 password 比对
    ▼
认证成功 → 创建 Shiro Session → JdbcSessionDAO 持久化
```

#### 3.1.2 会话持久化机制

`JdbcSessionDAO` 实现了 Shiro `SessionDAO` 接口，将会话序列化到 `shiro_session` 表：

- **写入节流**：距上次 DB 写入不足 10 分钟的会话更新被跳过，减少 I/O 开销
- **序列化格式**：Java 原生序列化 → Base64 编码 → VARCHAR 存储
- **缓存层**：内存 `ConcurrentHashMap` 作为一级缓存，DB 作为持久化存储
- **过期清理**：启动时清理 `expire_at < NOW()` 的过期会话
- **会话有效期**：24 小时（`ShiroConfig` 配置）

#### 3.1.3 请求拦截链

```
HTTP Request
    │
    ▼
SessionExpiryFilter (Pre-Shiro)
    ├── 匿名路径 (/login, /register, /static/**, /api/auth/**) → 放行
    ├── 无 Session 或 Session 未认证 → 302 /login
    └── Session 有效 → 继续
    │
    ▼
Shiro Filter Chain
    ├── anon: /login, /register, /static/**, /api/auth/**
    └── authc: /** (所有其他路径)
    │
    ▼
Controller 处理
```

**ASYNC 调度器支持**：`ShiroConfig` 注册了 `ASYNC` DispatcherType 的过滤器映射，确保 SSE 长连接请求不经过 Shiro 认证拦截（避免异步写入时的 `HttpMessageNotWritableException`）。

---

### 3.2 构建调度子系统

#### 3.2.1 调度策略决策树

`BuildQueueService.submitTask()` 实现了三级调度策略：

```
submitTask(configId, buildMode, modules, envs, autoDeploy)
    │
    ▼
获取当前正在执行的任务列表 (activeTasks)
    │
    ├── 无冲突（无同项目任务在执行）
    │   └── 直接调用 BuildService.startBuild() → 立即执行
    │
    ├── 有同项目任务在执行，但服务器不重叠
    │   └── 创建 Git Worktree → executeBuildWithWorktree()
    │       避免分支冲突，支持同一项目的并行构建
    │
    └── 达到并发上限 或 服务器重叠
        └── 创建 BuildQueueTask (status=QUEUED)
            等待 @Scheduled processQueue() 调度执行
```

#### 3.2.2 队列调度算法

```
processQueue() — @Scheduled(fixedDelay = 5000ms)
    │
    ├── 调度器未启用 且 非 always-on → 跳过
    ├── SELECT * FROM build_queue_task
    │   WHERE status = QUEUED
    │   ORDER BY priority DESC, submit_time DESC
    │
    ├── FOR EACH queuedTask:
    │   ├── 再次检查服务器冲突（防止竞态）
    │   ├── 无冲突:
    │   │   ├── UPDATE status = EXECUTING
    │   │   ├── 创建 worktree（如需）
    │   │   ├── BuildService.startBuild()
    │   │   ├── 轮询等待完成（1秒间隔）
    │   │   └── UPDATE status = SUCCESS/FAILURE
    │   └── 有冲突: 跳过，等待下一轮
    │
    └── 自动停止机制:
        ├── 连续 3 次空轮询（无 QUEUED 任务）→ 暂停调度
        └── always-on 模式 → 永不自动停止
```

#### 3.2.3 Worktree 并行构建

当同一项目的多个构建任务需要并行时，系统使用 Git Worktree 隔离工作目录：

```
主仓库: ~/.autodeploy/builds/{projectKey}/
    │
    ├── Worktree 1: ~/.autodeploy/builds/{projectKey}/.worktrees/wt-{uuid1}/
    │   └── 独立的 HEAD、index、working tree
    │
    └── Worktree 2: ~/.autodeploy/builds/{projectKey}/.worktrees/wt-{uuid2}/
        └── 独立的 HEAD、index、working tree
```

**实现细节**：
- 创建：`git worktree add <path> <branch>`（通过 `ProcessBuilder`，JGit 不支持 worktree）
- 清理：`git worktree remove <path>` + 文件系统删除兜底
- 定时清理：`cleanupCompletedWorktrees()` 每小时清理已完成任务的 worktree

---

### 3.3 构建执行子系统

#### 3.3.1 构建流水线

`BuildService.executeBuild()` 是核心编排方法，按以下阶段顺序执行：

```
Phase 1: 初始化
    ├── 生成 UUID taskId
    ├── 创建 ProjectConfig 深拷贝快照
    ├── 创建日志文件 ~/.autodeploy/logs/{taskId}.log
    └── 注册 BuildTask 到 activeTasks Map

Phase 2: Git 操作
    ├── cloneOrPull(projectDir, repoUrl, branch, authEnvKey)
    │   ├── 目录存在 → git pull (fetch + merge)
    │   ├── 目录不存在 → git clone
    │   ├── 合并冲突 → git reset --hard + git pull
    │   └── 分支切换 → git checkout + git pull
    ├── 配置 Git (autocrlf, fileMode, ignoreCase)
    └── 获取 HEAD commit hash

Phase 3: 构建缓存检查
    ├── BuildCacheService.shouldSkipBuild(config, gitHash, modules, buildMode)
    ├── 全部匹配 → 跳过构建，使用缓存产物
    └── 不匹配 → 继续执行

Phase 4: 依赖安装 (Node.js only)
    ├── 检查 node_modules 是否存在且未过期
    └── 执行 npm install（或 yarn install）

Phase 5: 构建命令执行
    ├── 构建 Shell Preamble:
    │   ├── source ~/.zshrc (or ~/.bashrc)
    │   ├── source nvm.sh (Node.js)
    │   ├── source sdkman-init.sh (Java)
    │   ├── source gvm-init.sh (Go)
    │   └── 语言版本切换命令
    ├── 拼接: preamble + languageVersionSwitch + buildCommand
    └── ProcessBuilder 执行，实时推送 stdout/stderr 到 SSE

Phase 6: 产物解析
    ├── ArtifactResolver.resolveArtifacts(buildDir, config, modules)
    │   ├── 自动检测模式:
    │   │   ├── JAVA → target/*.jar
    │   │   ├── NODE → dist/ | build/ | .next/ | .output/ | out/
    │   │   │        + 解析 vue/vite/next/nuxt/angular 配置
    │   │   └── GO → 可执行文件
    │   └── 正则匹配模式:
    │       └── artifactPattern (include) + artifactExclusionPattern (exclude)
    └── 约束: 最大深度 8，最大扫描 5000 文件，最大匹配 50

Phase 7: 部署（可选，autoDeploy=true 时）
    └── DeployService.deploy(task, config, modules, envIds)

Phase 8: 收尾
    ├── 更新 BuildTask 状态 (SUCCESS/FAILED/DEPLOY_*)
    ├── 持久化 BuildRecord
    ├── 更新 BuildCacheEntry
    ├── 关闭所有 SSE Emitter
    └── 从 activeTasks 移除
```

#### 3.3.2 构建线程池管理

`BuildThreadPoolManager` 管理构建任务的并发执行：

| 参数 | 值 | 说明 |
|------|-----|------|
| 核心线程数 | 1 | 空闲时保留 1 个线程 |
| 最大线程数 | 系统设置值（默认 5，上限 20） | 通过 `SystemSettingsService` 动态调整 |
| 队列容量 | 0 | 不使用等待队列 |
| 拒绝策略 | `CallerRunsPolicy` | 满载时由调用线程执行 |
| 关闭超时 | 30 秒 | `@PreDestroy` 优雅关闭 |

修改最大并发数时，线程池会被销毁并重建（`rebuildPool()`）。

---

### 3.4 实时日志子系统

#### 3.4.1 SSE 日志推送架构

```
Build Thread                    BuildTask                     SSE Clients
     │                            │                              │
     │ pushLog(line)              │                              │
     │───────────────────────────▶│                              │
     │                            │ 1. bufferLogLine(line)       │
     │                            │    环形缓冲，上限 10,000 行    │
     │                            │                              │
     │                            │ 2. 写入日志文件                │
     │                            │    ~/.autodeploy/logs/        │
     │                            │    {taskId}.log               │
     │                            │                              │
     │                            │ 3. FOR EACH SseEmitter:      │
     │                            │    send(SseEmitter.event()    │
     │                            │      .data(line))             │
     │                            │─────────────────────────────▶│
     │                            │                              │ Browser
     │                            │ 4. 移除已断开的 Emitter       │ requestAnimationFrame
     │                            │                              │ 批量渲染
```

#### 3.4.2 订阅与重连机制

| 场景 | 处理方式 |
|------|---------|
| 构建进行中订阅 | 先发送最近 200 行缓冲，然后实时推送新日志 |
| 构建已完成订阅 | 返回 HTTP 204（前端回退到文件读取） |
| SSE 连接超时 | SseEmitter 设置 30 分钟超时 |
| SSE 连接断开 | 前端回退到 `fetch` 读取日志文件 |
| Emitter 发送异常 | 自动从订阅列表移除 |
| 构建完成 | `completeEmitters()` 关闭所有连接 |

#### 3.4.3 前端日志渲染优化

- **活跃任务**：`EventSource` 接收事件 → `requestAnimationFrame` 批量 DOM 更新 → 自动滚动
- **已完成任务**：`fetch` 读取日志文件 → 分页加载（"加载更多"按钮）
- **日志尾部读取**：`BuildService.readLogFileTail()` 使用 `RandomAccessFile` 从文件末尾反向读取，避免加载整个文件

---

### 3.5 部署子系统

#### 3.5.1 部署拓扑模型

```
ProjectConfig (项目配置)
    │
    ├── 关联 Environment 1 (dev)
    │   ├── Server A (deploy_enabled=true)  ← 实际部署
    │   └── Server B (deploy_enabled=false) ← 跳过
    │
    ├── 关联 Environment 2 (staging)
    │   └── Server C (deploy_enabled=true)  ← 实际部署
    │
    └── 关联 Environment 3 (prod)
        └── Server D (deploy_enabled=true)  ← 实际部署
```

用户在构建时选择目标环境，系统根据 `deploy_enabled` 标志过滤实际部署的服务器。

#### 3.5.2 单服务器部署流程

```
DeployService.deploy()
    │
    ├── FOR EACH module:
    │   ├── ArtifactResolver 解析产物路径
    │   └── 收集所有产物文件
    │
    ├── FOR EACH selected environment:
    │   └── FOR EACH server (deploy_enabled=true):
    │       │
    │       ├── Step 1: 备份
    │       │   └── SSH: cp -r {targetPath} {targetPath}.bak.{timestamp}
    │       │
    │       ├── Step 2: 上传
    │       │   ├── 文件产物 → SCP 单文件上传
    │       │   └── 目录产物 → SCP 递归上传（先清空目标目录，Node.js 特殊处理）
    │       │
    │       └── Step 3: 重启
    │           └── SSH: {deployRestartCommand}
    │
    └── 汇总部署结果 (DEPLOY_SUCCESS / DEPLOY_FAILED)
```

#### 3.5.3 路径计算规则

| 路径 | 计算公式 | 示例 |
|------|---------|------|
| 有效部署路径 | `installDir` + `deployTargetPath` | `/opt/app` + `/releases` = `/opt/app/releases` |
| 有效脚本路径 | `installDir` + `scriptDir` | `/opt/app` + `scripts` = `/opt/app/scripts` |
| 备份路径 | `{effectiveTargetPath}.bak.{yyyyMMddHHmmss}` | `/opt/app/releases.bak.20260815143000` |

#### 3.5.4 部署脚本导出

`DeployScriptService` 可生成独立的 `.bat`/`.sh` 部署脚本，包含：
- 目标服务器信息（host、port、user）
- 备份命令
- `mkdir -p` 创建目录
- `scp` 上传命令
- `ssh` 重启命令

供用户在脱离系统的情况下手动执行部署。

---

### 3.6 构建缓存子系统

#### 3.6.1 缓存结构

```json
{
  "{configId}": {
    "configId": 1,
    "gitHash": "abc123def456",
    "modulePaths": ["module-a", "module-b"],
    "deploySourcePath": "/opt/app/builds/project/dist",
    "buildWorkDir": "frontend",
    "buildCommand": "npm run build",
    "languageVersion": "18.17.0",
    "buildMode": "FULL",
    "updatedAt": "2026-08-15T10:30:00"
  }
}
```

#### 3.6.2 缓存失效判定

`BuildCacheService.shouldSkipBuild()` 逐一比对以下字段，任一不匹配即判定缓存失效：

| 比较字段 | 失效场景 |
|---------|---------|
| `gitHash` | 代码有变更（新 commit） |
| `modulePaths` | 模块列表发生变化 |
| `deploySourcePath` | 部署源路径变更 |
| `buildWorkDir` | 构建工作目录变更 |
| `buildCommand` | 构建命令变更 |
| `languageVersion` | 语言版本切换 |
| `buildMode` | 构建模式变更（FULL vs INCREMENTAL） |

#### 3.6.3 缓存存储

- **存储格式**：单个 JSON 文件 `~/.autodeploy/data/build-cache.json`
- **加载时机**：应用启动时加载到 `ConcurrentHashMap`
- **写入时机**：每次构建成功后更新对应 entry 并全量写回文件
- **驱逐时机**：项目配置保存时，清除该项目对应的缓存条目

---

### 3.7 密钥管理子系统

#### 3.7.1 设计原则

系统采用**间接引用**模式管理敏感信息：数据库中仅存储环境变量的**名称**，实际值在运行时通过 `System.getenv()` 读取。

#### 3.7.2 密钥引用链路

```
┌─────────────────────────────────────────────────────────────────────┐
│                          密钥管理流程                                │
│                                                                     │
│  数据库存储                    运行时解析                             │
│  ──────────                   ──────────                            │
│                                                                     │
│  ProjectConfig                 EnvVarUtil.getValue(key)             │
│  ├─ gitAuthEnvKey = "GIT_TOKEN"  ──▶  System.getenv("GIT_TOKEN")   │
│  │                                  ──▶  "ghp_xxxxxxxxxxxx"        │
│  │                                                                  │
│  ServerInfo                    EnvVarUtil.getValue(key)              │
│  ├─ authEnvKey = "SSH_PASS_1"  ──▶  System.getenv("SSH_PASS_1")    │
│  │                                ──▶  "server_password"            │
│  │                                                                  │
│  EnvVarRef (注册表)             用途:                                │
│  ├─ varKey = "GIT_TOKEN"       记录系统中使用了哪些环境变量            │
│  ├─ varKey = "SSH_PASS_1"      在系统设置页面展示配置状态              │
│  └─ description = "..."        (已设置 / 未设置)                     │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

#### 3.7.3 密钥使用场景

| 密钥变量 | 引用模型 | 使用场景 |
|---------|---------|---------|
| `MYSQL_PASSWORD` | 应用配置 | 数据库连接 |
| `GIT_TOKEN` (自定义名) | `ProjectConfig.gitAuthEnvKey` | Git HTTPS 认证 |
| SSH 密码 (自定义名) | `ServerInfo.authEnvKey` | SSH/SCP 远程操作 |
| SSH 私钥路径 (自定义名) | `ProjectConfig.gitAuthEnvKey` | Git SSH 认证 |

---

### 3.8 运行时检测子系统

#### 3.8.1 检测策略

系统根据操作系统采用不同的运行时检测策略：

| 操作系统 | Java | Go | Node.js | Python |
|---------|------|-----|---------|--------|
| **Unix/Linux/macOS** | SDKMAN (`sdk list java`) | gvm (`gvm list`) | nvm (`nvm ls`) | uv (`uv python list`) |
| **Windows** | 本地目录扫描 | 本地目录扫描 | nvm-windows (`nvm list`) | uv (`uv python list`) |

#### 3.8.2 Windows 本地目录扫描

`WindowsRuntimeScanner` 在 Windows 上通过扫描常见安装目录检测已安装的 Java/Go 版本：

**Java 扫描位置（按优先级）：**
1. `JAVA_HOME` 环境变量指向的目录
2. `%USERPROFILE%\.jdks\`（IntelliJ IDEA 下载的 JDK）
3. `C:\Program Files\Java\`（Oracle JDK/JRE）
4. `C:\Program Files\Eclipse Adoptium\`（Temurin）
5. `C:\Program Files\Zulu\`、`Microsoft\`、`BellSoft\`、`Amazon Corretto\` 等

**Go 扫描位置：**
1. `GOROOT` 环境变量指向的目录
2. `C:\Program Files\Go\`、`C:\Go\`

**版本提取：**
- 通过有序正则数组从目录名提取版本号（如 `jdk-17.0.5` → `17.0.5`，`jdk1.8.0_301` → `1.8.0_301`）
- 验证 `bin\java.exe` 或 `bin\go.exe` 是否存在（文件系统检查，不启动进程）
- 仅当目录名无法匹配时，fallback 执行 `<dir>\bin\java.exe -version`

#### 3.8.3 缓存策略

```
应用启动
    │
    ▼
@PostConstruct → 后台线程执行一次 scanJavaInstallations() + scanGoInstallations()
    │
    ▼
结果缓存到 volatile Map<String, String> (版本 → 安装路径)
    │
    ▼
后续查询直接返回缓存，不触发扫描
    │
    ▼
用户点击「重新扫描」→ POST /api/runtime/scan → refreshCache() → 更新缓存
```

**设计原则：**
- 启动时自动扫描一次，避免首次访问时延迟
- 之后不再自动扫描，避免不必要的文件系统 I/O
- 用户可通过 UI 手动触发重新扫描
- 缓存使用 `volatile` 保证多线程可见性

#### 3.8.4 版本切换机制

```
项目配置选择 Java 17.0.5
    │
    ▼
UI 从 /api/runtime/versions-with-paths 获取版本→路径映射
    │
    ▼
选择版本时自动填充 customInstallDir = "C:\Program Files\Java\jdk-17.0.5"
    │
    ▼
构建时 buildFullCommand() 生成:
    set PATH=C:\Program Files\Java\jdk-17.0.5\bin;%PATH% && mvn clean package
    │
    ▼
cmd /c 执行，使用指定版本的 Java
```

**关键行为：**
- Windows 上 `customInstallDir` 由 UI 自动填充，用户可手动修改
- 如果 `customInstallDir` 为空，`buildFullCommand` 会从扫描缓存自动解析版本对应的路径
- Windows 上跳过无意义的 `use` 命令（`java -version` 不能切换版本），仅通过 PATH 前置实现版本切换
- Unix 上行为不变，仍使用 SDKMAN 的 `sdk use java <version>`

---

## 4. 技术债务与已知约束

| 类别 | 问题 | 影响 | 建议 |
|------|------|------|------|
| 内存态数据 | `BuildTask` 仅存于 `ConcurrentHashMap`，重启后丢失 | 重启后无法查看运行中任务状态，SSE 连接中断 | 考虑 Redis 或 DB 持久化 |
| 安全性 | 删除操作使用 GET 方法（`/config/delete/{id}`） | 可能被浏览器预取或爬虫误触发 | 改为 POST/DELETE |
| 安全性 | 表单无 CSRF Token | 跨站请求伪造风险 | 引入 Shiro CSRF 插件 |
| 安全性 | 无细粒度授权模型 | 所有认证用户拥有完全相同权限 | 按需引入 RBAC |
| 性能 | `ServerInfoService` 存在 N+1 查询 | 服务器列表加载时逐条查询环境名称 | 使用 JOIN 或批量查询 |
| 架构 | 无 DTO 层，实体直接序列化 | API 契约与 DB Schema 强耦合 | 引入 DTO 或 Projection |
| 架构 | 字段注入（`@Autowired`）而非构造器注入 | 不利于测试和不可变性 | 迁移至构造器注入 |
| 架构 | 远程构建模式（`REMOTE`）基础设施存在但未接入 | 功能不完整 | 完成集成或移除代码 |
| 资源管理 | `SshService` 使用单例 `SshClient`，无 `@PreDestroy` | 应用关闭时 SSH 连接可能未正常释放 | 添加生命周期管理 |
| 线程管理 | `LanguageRuntimeController` 使用 `new Thread()` | 绕过线程池管理，高并发时可能耗尽系统线程 | 统一使用线程池 |
| 数据一致性 | `ConfigService.getSnapshot()` 手动字段拷贝 | 新增字段时易遗漏 | 使用 `BeanUtils.copyProperties` |

---

## 5. 附录：技术栈清单

| 层级 | 技术 | 版本 | 用途 |
|------|------|------|------|
| **框架** | Spring Boot | 2.7.18 | 应用框架 |
| **语言** | Java | 8 | 开发语言 |
| **ORM** | MyBatis-Plus | 3.5.5 | 数据访问 |
| **数据库** | MySQL | 8.0+ | 持久化存储 |
| **迁移** | Flyway | (bundled) | 数据库版本管理 |
| **认证** | Apache Shiro | 1.13.0 | 会话认证 |
| **Git** | JGit | 5.13.3 | Git 操作 |
| **SSH** | Apache MINA SSHD | 2.9.2 | SSH/SCP 客户端 |
| **模板** | Thymeleaf | (bundled) | 服务端渲染 |
| **前端** | Bootstrap 5 + jQuery | — | UI 框架 |
| **构建** | Maven | — | 项目构建 |
| **代码质量** | Spotless + Checkstyle | — | 代码格式化与检查 |
| **测试** | Spring Boot Test + H2 | — | 集成测试 |

---

*文档结束*
