# 构建队列决策重构方案

> 日期：2026-08-15
> 状态：实施中

## 规则定义

| 序号 | 条件 | 行为 |
|------|------|------|
| 0 | 本地有未提交代码 | **保持当前行为**：分支不同→拒绝；分支相同→正常构建 |
| 1 | 无执行中任务 | 直接构建 |
| 2.1 | 不同项目 | 直接构建 |
| 2.2① | 同项目 + 有projectDir + 部署服务器无交集 | worktree构建，完成后删除 |
| 2.2② | 同项目 + 有projectDir + 部署服务器有交集 | 入队列排队 |
| 2.3① | 同项目 + 无projectDir + 服务器无交集 + 不同用户 | 直接构建（各自目录已隔离） |
| 2.3② | 同项目 + 无projectDir + 服务器无交集 + 相同用户 | worktree构建，完成后删除 |
| 2.3③ | 同项目 + 无projectDir + 服务器有交集 | 入队列排队 |

## 修改清单

### 1. GitService.createWorktree() — 命名加入用户名

```
当前: {projectName}_{8位UUID}_{branch}
改为: {projectName}_{username}_{8位UUID}_{branch}
```

`createWorktree` 方法签名新增 `String username` 参数，调用处同步修改。

### 2. GitService — 新增孤立worktree清理方法

```java
@Scheduled(cron = "0 0 3 * * ?")  // 每天凌晨3点
public void cleanupOrphanedWorktrees()
```

- 扫描 `{tmpdir}/autodeploy-worktrees/` 下所有目录
- 检查目录的 `lastModified` 是否超过24小时
- 超过24小时的执行 `removeWorktree()` 清理
- 日志记录清理数量

### 3. BuildQueueService.submitTask() — 重写决策树

**移除**：
- `forceStart` 参数
- `checkDuplicate` 拒绝逻辑（整个 `isDuplicate` → 返回error 的分支）
- `hasSameUserProjectTasks` 调用及 forceStart 分支

**新增**：
```java
private String decideBuildStrategy(ProjectConfig config, String username,
                                  String deployServersKey) {
  List<BuildQueueTask> executing = getExecutingTasks();
  if (executing.isEmpty()) return "direct";

  boolean hasProjectDir = config.getProjectDir() != null
                          && !config.getProjectDir().trim().isEmpty();

  for (BuildQueueTask task : executing) {
    if (!task.getConfigId().equals(config.getId())) continue; // 不同项目，跳过

    // 同项目
    if (hasServerOverlap(task.getDeployServers(), deployServersKey)) {
      return "queue";  // 服务器有交集 → 排队
    }

    // 服务器无交集
    if (hasProjectDir) {
      return "worktree";  // 有projectDir → worktree
    } else {
      if (task.getUsername().equals(username)) {
        return "worktree";  // 无projectDir + 同用户 → worktree
      }
      // 无projectDir + 不同用户 → 继续检查其他任务
    }
  }
  return "direct";  // 无冲突 → 直接构建
}
```

`submitTask` 根据返回值：
- `"direct"` → `startDirectBuild()`
- `"worktree"` → `startImmediateBuild()`（内部调用 `createWorktree`）
- `"queue"` → `enqueueTask()`

### 4. BuildQueueService.processQueue() — 调度感知服务器冲突

```
当前：取第一个候选任务直接执行
改为：
  for each candidate (最多5个):
    if (与当前 EXECUTING 任务有同项目+服务器重叠):
      skip，尝试下一个
    else:
      执行该任务
```

### 5. BuildQueueService.executeQueuedTask() — 动态决定构建方式

出队时重新调用 `decideBuildStrategy()`：
- `"direct"` → `buildService.startBuild()`
- `"worktree"` → `createWorktree` + `buildService.startBuildFromWorktree()`
- 若仍返回 `"queue"` → 重新入队（不执行，等下次调度）

### 6. BuildController — 接口调整

- `startBuild()`: 移除 `forceStart` 参数
- `checkDuplicate` API 返回值：
  - 移除 `duplicate`、`hasSameUserProject`
  - 新增 `mustQueue`（boolean）
  - 新增 `reason`（string，排队原因描述，如"部署目标服务器有正在执行的任务"）

### 7. 前端 build/index.html

**删除**：
- `#forceStartSection` HTML 及 `#forceStartToggle` 相关 JS

**修改 `checkDuplicate` 回调**：
```
mustQueue = true  → 显示提示信息 + 自动提交表单（入队列）
mustQueue = false → 直接提交
```

提示信息："当前项目有部署目标服务器重叠的任务正在执行，任务将排队等候"

### 8. 不需要修改的部分

- `cancelTask()` — 已有保护，取消排队任务不报错
- `stopBuild()` — 保持当前行为
- `BuildService` — 构建执行层无需改动
- 队列排序 — 已是 `priority DESC, submit_time DESC`
- 规则0未提交代码逻辑 — 保持当前行为

## 清理策略总结

| 清理对象 | 触发时机 | 方式 |
|----------|---------|------|
| worktree目录 + git元数据 | 构建完成（成功/失败） | `removeWorktree()` |
| 孤立worktree残留（异常中断） | 每天凌晨3点 | 扫描删除24h前的目录 |
| 构建日志 | 不清理 | 用户查看历史用 |
| 构建记录(DB) | 不清理 | 构建历史 |

## 影响范围

| 文件 | 改动量 | 说明 |
|------|--------|------|
| `BuildQueueService.java` | ~80行 | 决策树重写 + 调度感知 + 动态出队 |
| `GitService.java` | ~30行 | 命名加用户名 + 孤立清理方法 |
| `BuildController.java` | ~10行 | 移除参数 + 调整返回值 |
| `build/index.html` | ~30行 | 移除forceStart + 排队提示 |
