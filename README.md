# ThreshCore（award-log）开发者文档

| 项 | 说明 |
|----|------|
| **文档类型** | 工程与研发说明（产品 / 运维 / 测试 / 研发 / 部署均可读） |
| **系统名称** | ThreshCore 控制台 + award-log 后端服务 |
| **Context Path** | `/award-log`（所有后端接口前的统一前缀） |
| **默认端口** | `8088`（后端）；前端开发常见 `3000`（Vite） |
| **技术栈** | Java 17 · Spring Boot 3.5 · Spring AI 1.1.0 · Vue 3 · MySQL 8 / MariaDB 10.6+ |
| **版本** | 与当前仓库实现同步（2026-05-29） |
| **HTTP MCP 工具** | 26+（白名单由 `McpToolCatalog` 启动扫描，HTTP 列表见 `GET /api/mcp/tools`） |

**版权声明**：Apache License 2.0，见 `LICENSE` 与 `NOTICE`。

---

## 目录

- [谁该读哪一节](#谁该读哪一节)
- [文档体系](#文档体系)
- [系统是做什么的](#系统是做什么的)
- [架构总览](#架构总览)
- [Agent 运行时（核心）](#agent-运行时核心)
- [MCP 工具链](#mcp-工具链)
- [扩展 MCP 工具（开发指南）](#扩展-mcp-工具开发指南)
- [安全与审计](#安全与审计)
- [后端 API 索引](#后端-api-索引)
- [前端开发](#前端开发)
- [日志分析流水线](#日志分析流水线)
- [知识库 RAG](#知识库-rag)
- [配置参考](#配置参考)
- [快速开始](#快速开始)
- [本地开发与测试](#本地开发与测试)
- [构建与部署](#构建与部署)
- [麒麟 V11 + LoongArch](#麒麟-v11--loongarch)
- [近期更新](#近期更新)
- [监控与排障](#监控与排障)
- [报表导出（前端）](#报表导出前端)
- [仓库结构](#仓库结构)
- [许可协议](#许可协议)

---

## 谁该读哪一节

| 你是… | 建议阅读 |
|--------|----------|
| **产品 / 运维** | [系统是做什么的](#系统是做什么的)、顶栏安全自检、[安全与审计](#安全与审计) |
| **运维 / 值班** | [安全与审计](#安全与审计)、[配置参考](#配置参考)、`docs/deployment/麒麟V11-LoongArch生产上线检查清单.md` |
| **测试** | [后端 API 索引](#后端-api-索引)、[MCP 工具链](#mcp-工具链)、[本地开发与测试](#本地开发与测试) |
| **后端 / 全栈** | 全文；接口细节以 **Knife4j**（`/award-log/doc.html`）为准 |
| **前端** | [前端开发](#前端开发)、[知识库 RAG](#知识库-rag)、[后端 API 索引](#后端-api-索引) |
| **部署** | [快速开始](#快速开始)、[构建与部署](#构建与部署)、[麒麟 V11 + LoongArch](#麒麟-v11--loongarch) |

---

## 文档体系

| 文档 | 路径 | 用途 |
|------|------|------|
| **本文 README** | 根目录 | 架构、API、配置、开发扩展 |
| **麒麟上线检查清单** | `docs/deployment/麒麟V11-LoongArch生产上线检查清单.md` | LoongArch 生产验收 |
| **生产配置示例** | `docs/deployment/prod-config-example.yml` | 复制为未入库的 `application-prod.yml` |
| **在线接口** | `http://<host>:8088/award-log/doc.html` | Knife4j / OpenAPI |

> 答辩/PPT 等历史材料已归档至 `docs/archive/competition/`（不参与产品构建）。

---

## 系统是做什么的

**一句话**：ThreshCore 是 B/S 运维与日志平台——支持**上传分析日志**，也支持**中文对话**排查磁盘、进程、服务等问题；所有会修改系统的操作都经过**路径白名单、风险评分、二次确认和审计记录**。

| 能力 | 说明 | 界面入口 |
|------|------|----------|
| **智能对话** | 自然语言运维、Playbook 编排、RAG 引用与执行计划 | 工作台 · 对话 |
| **工具箱** | 登记过的运维能力，统一安全校验与结构化结果 | 工作台 · 工具箱 |
| **知识库 RAG** | Runbook 入库（txt/md/pdf）、向量检索，对话自动引用 | 工作台 · 知识库 |
| **溯源审计** | HTTP 访问记录 + MCP 执行链路（CoT 步骤） | 工作台 · 溯源 |
| **环境状态** | AI / Qdrant / MCP 连接自检与执行策略一览 | 工作台 · 环境 |
| **异常诊断** | 模板挖掘 + 多维异常检测 + AI 建议 | 洞察 · 诊断 |
| **态势大屏** | 本机 CPU/内存/磁盘/告警实时可视化（浅色工作区风格，与控制台一致） | 洞察 · 态势 |
| **巡检** | 定时巡检与待确认修复 | 顶栏巡检 |

**不是什么**：不是「给 AI 一个 root 终端」；不是必须装齐 Kafka/Redis（默认关闭）。

### 开发者 30 秒速览

| 动作 | 怎么做 |
|------|--------|
| 起服务 | MySQL/MariaDB + **手动** `schema.sql` → `mvn spring-boot:run` → `cd frontend && npm run dev` |
| 调工具 | **工具箱** 或 `POST /api/mcp/execute` |
| 调对话 | **对话**；`POST /api/assistant/chat/stream`（含 RAG 引用） |
| 管知识库 | **知识库** 页上传；`GET/POST /api/v1/knowledge/*` |
| 看环境 | **环境** 页；`GET /api/platform/info` + `/api/v1/knowledge/status` |
| 看溯源 | **溯源**（访问记录 / 执行链路）；工具箱执行记录可跳转 |
| 安全自检 | 顶栏 **安全自检**；`GET /api/security/self-check` |
| 加工具 | `mcp/tools/*` + `@McpTool` + `McpToolDispatcher` + `mcpToolsMeta.js` |
| 文档接口 | http://localhost:8088/award-log/doc.html |

---

## 架构总览

### 分层（2026 重构后）

```mermaid
flowchart TB
  subgraph ui[浏览器 ThreshCore]
    T1[助手 SSE]
    T2[工具]
    T3[审计]
  end

  subgraph entry[统一入口]
    UAS[UnifiedAssistantService]
    ORS[OpsRuntimeService]
    OIR[OpsIntentRouter]
  end

  subgraph playbooks[编排 Playbook]
    AOO[AutonomousOpsOrchestrator<br/>感知→诊断→修复→验证]
    ASO[AssistantOrchestrator<br/>磁盘/CPU/巡检续办]
  end

  subgraph mcp[MCP 层]
    CAT[McpToolCatalog<br/>白名单/风险分/只读集]
    REG[McpToolRegistry]
    DISP[McpToolDispatcher]
    TOOLS[mcp/tools/* @Tool]
    MPE[MinPrivilegeExecutor]
  end

  subgraph mcpHttp[HTTP MCP 薄层]
    SECSVC[McpSecurityService]
    EXSVC[McpExecutionService]
    AUDSVC[McpAuditService]
  end

  subgraph sec[安全层]
    GATE[McpInvocationSecurityGate]
    KERNEL[OpsSecurityKernel]
    PATH[OpsPathPolicy]
    TRUST[OpsTrustPolicy]
  end

  T1 --> UAS
  UAS --> ORS
  ORS --> OIR
  OIR -->|FULL_AUTONOMOUS| AOO
  OIR -->|DISK/CPU/PATROL| ASO
  UAS -->|未命中 Playbook| LLM[Spring AI ChatClient + @Tool]
  T2 --> SECSVC
  SECSVC --> EXSVC
  EXSVC --> DISP
  DISP --> TOOLS
  CAT --> GATE
  TOOLS --> MPE
  AOO --> TOOLS
  ASO --> TOOLS
  LLM --> TOOLS
  DISP --> GATE
  GATE --> KERNEL
  TOOLS --> PATH
  AOO --> TRUST
```

### 三条执行路径（开发必记）

| 路径 | 入口 | 编排 | 审计 |
|------|------|------|------|
| **HTTP MCP** | `McpExecuteController` → … → `McpToolDispatcher` | 单工具调用 | `ops_audit_trace` 记录执行步骤 |
| **对话 Playbook** | `UnifiedAssistantService` → `OpsRuntimeService` | 规则编排，不经过 LLM 多步 | `AssistantAuditRecorder` **六步** |
| **对话 LLM** | 同上，意图 `NONE` | `ChatClient` 自动 `@Tool` | 切面 `McpToolSecurityAspect` + 工具层 dry-run |

### 技术栈

| 层级 | 技术 |
|------|------|
| 后端 | Spring Boot 3.5、MyBatis、Spring AI 1.1.0、WebSocket |
| 前端 | Vue 3、Vite 4、Element Plus、ECharts、markdown-it |
| 关系库 | **本地/Windows 默认 MySQL 8**；麒麟生产 **MariaDB 10.6+**（双 JDBC 驱动，环境变量切换） |
| 向量库 | **Qdrant**（本机 Docker 或 Cloud + `api-key`）；运维知识库 RAG |
| 可选 | Redis、Kafka、Elasticsearch（默认不加载） |
| 观测 | Actuator、Prometheus |
| 文档 | Knife4j 4.5、Springdoc |

---

## Agent 运行时（核心）

### OpsIntentRouter — 意图 → Playbook

实现：`com.award.log.agent.OpsIntentRouter`

| Playbook | 触发示例 | 执行器 |
|----------|----------|--------|
| `FULL_AUTONOMOUS` | 自动运维、一键巡检、autonomous ops | `AutonomousOpsOrchestrator` |
| `DISK_CLEANUP` | 清理垃圾、磁盘满、执行修复 | `AssistantOrchestrator` |
| `CPU_PRESSURE` | CPU 高、负载异常 | `AssistantOrchestrator` |
| `PATROL_CONTINUATION` | 继续处理巡检、待确认方案 | `AssistantOrchestrator` |
| `NONE` | 其它 | 走 LLM + `@Tool` |

`forceRemediate(message)`：匹配「确认执行」「执行修复」等时，Playbook 内跳过 dry-run 预览。

### OpsRuntimeService — 统一 `run` API

实现：`com.award.log.agent.OpsRuntimeService`

```java
// 对话入口（UnifiedAssistantService 在编排前调用）
if (opsRuntimeService.shouldOrchestrate(userMessage)) {
    RunResult r = opsRuntimeService.run(userMessage, surface, intentRisk);
    // 返回 markdown + traceId + report（自主运维时有 report）
}
```

| 方法 | 用途 |
|------|------|
| `shouldOrchestrate(msg)` | `agent.runtime.enabled` 且意图非 `NONE` |
| `run(msg, surface, risk)` | 按 Playbook 分发 |
| `runScheduledAutonomous(surface, force)` | HTTP / 定时 / 巡检后触发，无自然语言 |

开关：`agent.runtime.enabled`（默认 `true`）。

### AutonomousOpsOrchestrator — 全工具融合闭环

实现：`com.award.log.agent.AutonomousOpsOrchestrator`

**阶段**：

1. **感知**（并行，最多 12 线程）：磁盘、负载、进程、systemd、网络、端口、Docker、防火墙、Cron、日志、journalctl/ss/lsof 等  
2. **诊断**：规则引擎生成 `findings`（磁盘压力、失败 unit、证书临期、僵尸进程提示等）  
3. **处置**：`OpsTrustPolicy` 内自动/待确认（临时目录清理、日志裁剪、白名单服务重启）  
4. **验证**：再次采集关键指标写入 `report`

**感知快照 API**（只读，带 TTL 缓存）：

```http
GET /award-log/api/ops/perception/snapshot?useCache=true
```

缓存：`OpsPerceptionCache` + `agent.perception.cache-ttl-ms`（默认 60000 ms）。写操作成功或 `useCache=false` 会失效缓存。

自主编排执行修复后写入 **`remediationEffect`**（磁盘/CPU 前后对比与 0–100 自愈评分）。

**自主运维 API**：

```http
POST /award-log/api/ops/autonomous/run?forceRemediate=false&readOnly=false
GET  /award-log/api/ops/autonomous/supports?message=全自动运维
```

### 操作审计（六步）

常量：`com.award.log.agent.audit.OpsAuditPhases`

| 步 | 英文 key | 中文 |
|----|----------|------|
| 1 | `receive` | 接收 |
| 2 | `perceive` | 感知 |
| 3 | `reason` | 推理 |
| 4 | `security` | 安全校验 |
| 5 | `execute` | 执行 |
| 6 | `verify` | 验证 |

Playbook 路径写入 `ops_audit_trace`；HTTP 工具调用在界面「操作追踪」中展示执行步骤。

### AssistantOrchestrator — 单场景 Playbook

保留磁盘清理、CPU 压力、巡检续办等**短路径**闭环（预览 → 确认 → 执行），与全工具自主编排互补。

---

## MCP 工具链

### 实现形态

- **进程内 Spring Bean**，非独立 stdio MCP Server。  
- **元数据**：`McpToolCatalog`（`@PostConstruct` 扫描 `mcp.tools.*Tool`，可选 `@McpTool` 注解）。  
- 对话注册：`McpToolRegistry`（`@Tool` 注入 LLM）。  
- HTTP 分发：`McpToolDispatcher`（按 toolName `switch` 调用具体 Bean）。  
- HTTP 门控：`McpInvocationSecurityGate` → `mcpToolCatalog.isHttpAllowed()` / `isReadOnlyObservation()`。  
- HTTP 控制器：`McpExecuteController` 委托 `McpSecurityService`、`McpExecutionService`、`McpAuditService`。

### 工具清单

HTTP / 对话调用时 `toolName` 须与 **Bean 短名**一致。

**读写合一（推荐，一个 toolName + `operation`）**

| toolName | operation 示例 | 读 | 写 |
|----------|----------------|----|----|
| `DiskOpsTool` | `df` / `hotspots` / `analyze` / `clean-temp` | df、热点排行、综合诊断（`overview` + `hotspotsRank`） | 清临时目录（dry-run） |
| `LogOpsTool` | `analyze` / `cleanup` | 日志分析 | 日志清理（dry-run） |
| `ServiceOpsTool` | `failed` / `status` / `restart` | systemd 状态 | 白名单服务重启 |
| `ContainerOpsTool` | `list` / `inspect` / `restart` / `stop` | docker ps | 容器重启/停止 |
| `ProcessOpsTool` | `list` / `kill` | 进程列表（Linux `ps -eo`；Windows CPU 归一化） | 结束进程（PID>10） |

**细粒度工具（兼容保留）**

| toolName | 源文件 | 能力 | 写操作 |
|----------|--------|------|--------|
| `DiskTool` | `mcp/tools/DiskTool.java` | 磁盘占用（Linux `df`；Windows `Get-PSDrive`） | 否 |
| `DiskInsightTool` | `DiskTool` | du 热点排行（白名单路径） | 否 |
| `DiskAnalyzeTool` | `mcp/tools/DiskAnalyzeTool.java` | 磁盘压力综合诊断（结构化 `overview` + 可选 `hotspotsRank`） | 否 |
| `ProcessTool` | `mcp/tools/ProcessTool.java` | 进程列表；Linux 稳定列格式，Windows `PercentProcessorTime / ProcessorCount` 归一化；可选 `kill`（PID>10） | **可选写** |
| `SystemLoadTool` | `mcp/tools/SystemLoadTool.java` | 负载 | 否 |
| `LogAnalysisTool` | `mcp/tools/LogAnalysisTool.java` | 读主机日志并分析 | 否 |
| `NetworkTool` | `mcp/tools/NetworkTool.java` | ping / traceroute | 否 |
| `OsInsightTool` | `mcp/tools/OsInsightTool.java` | journalctl / ss / lsof | 否 |
| `ConfigCheckTool` | `mcp/tools/ConfigCheckTool.java` | 配置语法只读检查 | 否 |
| `ConfigDriftTool` | `mcp/tools/ConfigDriftTool.java` | 配置 checksum 漂移（两次快照对比） | 否 |
| `PrivilegeTool` | `mcp/tools/PrivilegeTool.java` | 权限探测 | 否 |
| `PortHealthTool` | `mcp/tools/PortHealthTool.java` | TCP 端口探测 | 否 |
| `DockerTool` | `mcp/tools/DockerTool.java` | docker ps / inspect；`restart` / `stop` | **可选写** |
| `CronJobTool` | `mcp/tools/CronJobTool.java` | crontab / schtasks | 否 |
| `FirewallTool` | `mcp/tools/FirewallTool.java` | ufw / firewalld / netsh | 否 |
| `SslCertTool` | `mcp/tools/SslCertTool.java` | TLS 证书过期 | 否 |
| `SystemdTool` | `mcp/tools/SystemdTool.java` | failed units / 服务状态；`operation=restart` | **可选写** |
| `CleanTempTool` | `mcp/tools/CleanTempTool.java` | 清临时目录 | **是** |
| `LogCleanupTool` | `mcp/tools/LogCleanupTool.java` | 清陈旧日志 | **是** |
| `ServiceRestartTool` | `mcp/tools/ServiceRestartTool.java` | 重启白名单服务 | **是** |
| `AutonomousOpsTool` | `mcp/tools/AutonomousOpsTool.java` | 一键全工具自主运维 | 策略内写 |
| `FirstMcpTools` | `mcp/FirstMcpTools.java` | 示例（对话实验） | — |

### 写操作参数

| 工具 / 操作 | 真实执行条件 |
|-------------|----------------|
| `CleanTempTool` / `LogCleanupTool` / `*OpsTool` 清理类 | `dryRun=false` **且** `confirmDelete=true` |
| `ServiceRestartTool` / `ServiceOpsTool` restart | `dryRun=false` **且** `confirmRestart=true` |
| `ProcessTool` / `ProcessOpsTool` kill | `dryRun=false` **且** `confirmKill=true` |
| `DockerTool` / `ContainerOpsTool` restart / stop | `dryRun=false` **且** `confirmRestart` / `confirmStop=true` |

**默认**：写类工具首次调用 `dryRun=true`（只预览命令）。HTTP 与工作台在 `needConfirm` 后走 `POST /api/mcp/confirmExecute`。

**二次确认不会自动把预览变真删**：`McpToolParamReader.applyConfirmedWriteToolParams(tool, params, userMessage)` 与前端 `applyMcpWriteConfirmParams` 仅在下列情况关闭 `dryRun`：

- 参数已带 `confirmDelete` / `confirmRestart` / `confirmKill` / `confirmStop`，或 `dryRun=false`；或  
- `userMessage` 含「真实删除」「立即重启」等明确执行话术（见 `userRequestedRealWrite`）。

请求体建议始终传 **`userMessage`**（用户原话），供 `IntentRiskFilter` 与 **`INTENT_TOOL_MISMATCH`**（破坏性话术 + 只读工具）判定。

**示例（日志真实删除）**：

```json
{
  "toolName": "LogCleanupTool",
  "parameters": {
    "path": "/var/log",
    "days": 30,
    "dryRun": false,
    "confirmDelete": true
  }
}
```

### HTTP MCP 接口

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/mcp/tools` | 工具元数据（`McpToolCatalog.listToolsForApi()`，供前端动态同步） |
| POST | `/api/mcp/execute` | 执行（body 含 `userMessage`；可能返回 `needConfirm`） |
| POST | `/api/mcp/confirmExecute` | 二次确认后执行（`confirmCode=确认执行`，`userMessage` 同上） |

### 风险评分

`AgenticRiskScoreEngine`：0–10 分；工具基线分来自 `McpToolCatalog.defaultRiskScore()`。

| 配置项 | 默认 | 含义 |
|--------|------|------|
| `agent.security.risk-score-auto-max` | 3 | 低于此分倾向自动 |
| `agent.security.risk-score-confirm-max` | 7 | 高于此分拒绝；中间需确认 |

---

## 扩展 MCP 工具（开发指南）

新增工具（以只读 `ExampleProbeTool` 为例）的**最小清单**：

### 1. 实现 Tool Bean

```java
@McpTool(httpAllowed = true, readOnlyObservation = true, defaultRiskScore = 1.0)
@Component
public class ExampleProbeTool {
    @Tool(name = "probeExample", description = "示例探测")
    public String probeExample(@ToolParam(description = "目标") String target) {
        // 使用 MinPrivilegeExecutor，勿 Runtime.exec 裸调
        return "...";
    }
}
```

未加 `@McpTool` 时，`McpToolCatalog` 仍按 `*Tool` 类名与约定推断白名单/只读/风险分。

### 2. 必改（分发与对话）

| 位置 | 操作 |
|------|------|
| `McpToolDispatcher` | `switch` 增加 `case "ExampleProbeTool"` |
| `McpToolRegistry` | 构造器注入 Bean（供 LLM `@Tool`） |

白名单与默认风险分：**无需**再改 `ALLOWED_TOOLS` 静态集合（已废弃），由 `McpToolCatalog` 统一维护。

### 3. 前端

| 位置 | 操作 |
|------|------|
| `mcpToolsMeta.js` | `MCP_TOOL_DEFINITIONS` 增加表单项（控制台表单） |
| 启动后 | `GET /api/mcp/tools` 自动带回 `description` / `commandHint`（`mergeToolRegistry` 合并） |

### 4. 可选

- 自主运维：在 `AutonomousOpsOrchestrator.perceiveAll` 中 `safeCall`  
- 写操作成功：确认 `McpToolDispatcher` 会 `OpsPerceptionCache.invalidate()`（非只读且非 dryRun）

### 5. 测试

单元测试 + 安全回归（只读面、破坏性话术 + 只读工具 → `INTENT_TOOL_MISMATCH`）。

---

## 安全与审计

### 多层护栏

| 层 | 组件 | 说明 |
|----|------|------|
| 认证 | `AuthInterceptor` | 除登录、Swagger、部分只读 API 外需会话 |
| 工具白名单 | `McpInvocationSecurityGate` | 仅允许登记的 `toolName` |
| 路径白名单 | `OpsPathPolicy` | 读/清/配置检查路径 |
| 日志删除保护 | `LogSafetyClassifier` | 识别 DB/中间件日志路径 |
| 注入 / 意图 | `PromptInjectionGuard`、`IntentRiskFilter` | 自然语言过滤 |
| 高危命令 | `HighRiskCommandDetector` | rm -rf 等模式 |
| Agentic 风险分 | `AgenticRiskScoreEngine` | 与确认流、巡检修复联动 |
| 信任策略 | `OpsTrustPolicy` | 自主运维写操作分级 |
| 最小权限 | `MinPrivilegeExecutor` | `sudo -u award-agent`；Windows 跳过 sudo |
| 全局演练 | `OpsDryRunProperties` | `ops.dry-run.global=true` 时写类命令仅返回 `[DRY-RUN]` |
| 门面 | `OpsSecurityKernel` | 安全能力聚合（供编排层调用） |
| 审计 | `OpsAuditTraceService`、`AssistantAuditRecorder` | `ops_audit_trace`；`audit.retention-days` 定时清理 |

### 工作台本地风控（助手页）

实现：`frontend/src/utils/opsIntentSafety.js`，与后端 `IntentRiskFilter` 口径对齐。

- 破坏性话术（如「删除整个系统」）**不**走本地工具快捷路由。  
- 延时任务创建前同样拦截；延时白名单与 `OpsDeferredTaskService` 一致（只读/清理类，**不含**服务重启等写操作）。

### 安全自检

| 项 | 说明 |
|----|------|
| **入口** | 顶栏 **安全自检** |
| **接口** | `GET /api/security/self-check` |
| **实现** | `SecuritySelfCheckService`：固定用例调用真实安全校验逻辑，**不执行系统命令** |
| **展示** | 配置快照 + 检查项表（预期 vs 实际：放行 / 须确认 / 拒绝） |

### 两条工具入口

| 入口 | 门控 | 二次确认 |
|------|------|----------|
| HTTP `/api/mcp` | `evaluate` + `userMessage` | NEED_CONFIRM + `confirmExecute`（预览保持 dryRun） |
| 对话 `@Tool` | `McpToolSecurityAspect` → `evaluateChatToolInvocation` | 写工具默认 dry-run；BLOCK 直接拒绝 |

### 生产检查项

- `app.security.ai-audit-relaxed-read=false`  
- `agent.min-privilege.enabled=true`  
- `agent.run-as-user=award-agent` + sudoers  
- Profile `prod-kylin` 时 `ProductionProfileValidator` 拒绝放宽审计  
- 限制 Knife4j / Actuator 公网暴露  

### 平台只读 API（无需登录）

```http
GET /award-log/api/platform/info
GET /award-log/api/ops/perception/snapshot
```

---

## 后端 API 索引

**约定**：完整 URL = `http(s)://主机:端口/award-log` + 路径。除注明外需登录 Cookie。

### 智能运维（Agent）

| 方法 | 路径 | 控制器 |
|------|------|--------|
| POST | `/api/assistant/chat/stream` | `UnifiedAssistantController` — 工作台 SSE |
| GET | `/api/assistant/context` | 会话上下文 |
| POST | `/api/ops/autonomous/run` | `AutonomousOpsController` |
| GET | `/api/ops/autonomous/supports` | 意图是否走自主运维 |
| GET | `/api/ops/perception/snapshot` | `PerceptionController` — 只读感知 |
| GET | `/api/platform/info` | `PlatformInfoController` — 平台/麒麟/最小权限 |
| GET/POST | `/api/mcp/*` | `McpExecuteController` |
| GET | `/api/ops-trace/**` | `OpsTraceController` — 链路审计 |
| GET/POST | `/api/ops/patrol/**` | `OpsPatrolController` — 巡检与修复提案 |
| GET | `/api/security/self-check` | `SecuritySelfCheckController` — 安全自检（只读） |
| GET/POST/DELETE | `/api/v1/knowledge/**` | `KnowledgeController` — 知识库状态、文档、上传、检索 |

**巡检补充接口**：

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/ops/patrol/alerts/recent` | 内存环形最近告警 |
| GET | `/api/ops/patrol/correlation/latest` | 最近一轮关联快照 |
| GET | `/api/ops/patrol/history?days=7&limit=50` | 持久化历史（`ops_patrol_history`） |
| GET | `/api/ops/patrol/history/trend?days=7` | 按日告警/巡检次数（顶栏趋势图） |

### 日志与业务

| 前缀 | 用途 |
|------|------|
| `/log/**` | 上传、任务、报告、下载；`GET /log/history` 支持 `fileName`、`status`、`startTime`、`endTime`（`YYYY-MM-DD`，服务端转 `LocalDateTime` 闭区间） |
| `/log/diagnose/**` | AI 全盘/单行诊断 |
| `/admin/user`、`/admin/role`、`/admin/permission` | RBAC |
| `/admin/audit/ai` | 执行审计列表 |
| `/api/alarm*` | 告警配置与历史 |
| `/admin/statistics/*` | 仪表盘汇总、性能、任务状态（`log-summary`、`task-status` 等） |
| `/api/performance` | 性能 REST（若单独暴露） |
| `/api/agent/path-policy` | 路径白名单读写 |

### WebSocket

| 路径 | 说明 |
|------|------|
| `/ws/performance` | ~5s 推送性能；`patrol_alert` / `patrol_remediation`；客户端重连发 `sync` 补发缓冲消息 |
| `/ws/logs` | 日志流（可选） |

### 对话入口区别（易混淆）

| 接口 | 用途 |
|------|------|
| `POST /api/assistant/chat/stream` | 主机运维主入口（编排 + LLM） |
| `POST /api/chat/analyze*` | 针对**已选日志内容**的问答，不执行主机写操作 |

---

## 知识库 RAG

实现：`KnowledgeBaseServiceImpl` + `KnowledgeController`；对话侧由 `MultiAgentContextBuilder` / `UnifiedAssistantService` 在编排前检索并随 SSE 返回引用元数据（`ASSISTANT_META:`）。

| 能力 | 说明 |
|------|------|
| 入库 | 文本 JSON 上传；文件支持 **txt / md / pdf / log** |
| 分块 | 可配置 `chunk-size` / `chunk-overlap`，写入 Qdrant 集合 `ops_knowledge` |
| 向量 | 优先 DashScope Embedding（Spring AI）；不可用时可选本地降级向量 |
| 检索 | `GET /api/v1/knowledge/search?query=&topK=` |
| 对话 | 助手自动检索 Top-K，前端展示依据条与引用摘要 |
| 集合创建 | **惰性创建**：`getStatus` / `search` 不自动 POST；**首次上传**时进程内仅发起一次创建请求 |
| 状态缓存 | 集合探测与文档数缓存 120s，降低 Qdrant scroll / GET 频率 |

**Qdrant 部署方式**（二选一）：

```bash
# 本机 Docker（无需 API Key）
docker compose up -d qdrant
# QDRANT_URL=http://localhost:6333

# Qdrant Cloud：控制台复制 Cluster URL + API Key，写入 application-local.yml 或环境变量
# QDRANT_URL=https://<cluster>.region.gcp.cloud.qdrant.io
# QDRANT_API_KEY=...
```

首次空库：**环境页**显示「集群已连接，集合将在首次上传时创建」；文档列表返回空表；检索返回空结果。上传文档后自动建集合并向量化入库。

---

## 前端开发

### 目录

```
frontend/
├── src/App.vue                    # 9 个功能页 + 快捷键 1–9
├── src/api/index.js               # Axios（baseURL 含 /award-log；dev 走 Vite 代理）
├── .env.development               # 推荐：VITE_AWARD_LOG_BASE_URL=http://localhost:3000/award-log
├── src/components/
│   ├── OpsChatInterface.vue       # 助手（SSE、计划卡片、RAG 依据条、GFM Markdown）
│   ├── ToolConsole.vue            # 工具箱 + 执行记录（可跳转溯源）
│   ├── AuditCenter.vue            # 溯源（接口访问 + 执行链路）
│   ├── OpsTraceCenter.vue         # 执行链路 CoT 步骤（中文 phase）
│   ├── KnowledgeBase.vue          # 知识库管理
│   ├── EnvironmentStatus.vue      # 环境 / 依赖自检
│   ├── OpsLiveWall.vue            # 态势大屏（浅色主题；WebSocket + 巡检聚合）
│   ├── LogAnalysis.vue            # 日志诊断
│   ├── HistoryList.vue
│   ├── LogAnalysisDashboard.vue   # 趋势看板（导出 PNG/PDF）
│   ├── TopPerformanceBar.vue      # 顶栏性能 / 巡检 / 环境标签
│   ├── RemediationPlanCard.vue    # 对话内修复计划卡片
│   ├── AssistantEvidenceBar.vue   # RAG / 溯源依据条
│   ├── StructuredResultView.vue   # MCP 结构化结果（非 JSON 堆砌）
│   └── SecuritySelfCheckDialog.vue
└── src/utils/
    ├── mcpToolsMeta.js            # 工具元数据与展示名
    ├── structuredDataView.js      # 结构化视图 + formatBytes 等
    ├── assistantMarkdown.js       # 助手 Markdown（含 GFM 表格）
    ├── traceStepLabels.js         # 溯源步骤中文
    ├── opsIntentSafety.js
    ├── formatDate.js / apiError.js / tableExport.js / dashboardExport.js
```

### 导航分组

| 分组 | 页面 | 快捷键 |
|------|------|--------|
| **工作台** | 对话 · 工具箱 · 溯源 · 知识库 · 环境 | 1–5 |
| **洞察** | 态势 · 诊断 · 报告 · 看板 | 6–9 |

### 环境变量

| 变量 | 说明 |
|------|------|
| `VITE_AWARD_LOG_BASE_URL` | **必须**含 `/award-log`，无尾斜杠；开发推荐 `http://localhost:3000/award-log`（Vite 代理到 8088） |
| `VITE_DEV_PROXY_TARGET` | Vite dev 代理目标，默认 `http://127.0.0.1:8088` |
| `VITE_AWARD_LOG_WS_URL` | 跨域时指定 wss |
| `VITE_API_TIMEOUT_MS` | 大文件上传可调大（默认 600000） |

> **注意**：勿将 baseURL 写成 `http://localhost/award-log`（缺端口会打到 80）。开发请使用仓库内 `frontend/.env.development` 或复制 `frontend/.env.example`。

### 控制台页面

| 键 | 页面 | 说明 |
|----|------|------|
| 1 | 对话 | SSE；Playbook / LLM；RAG 引用与确认执行 |
| 2 | 工具箱 | `/api/mcp`；结构化结果；执行记录跳转溯源 |
| 3 | 溯源 | 访问记录 + 执行链路（可导出 CSV/PDF） |
| 4 | 知识库 | 文档上传、列表、语义检索 |
| 5 | 环境 | 平台信息、Qdrant、MCP、AI 配置状态 |
| 6 | 态势 | 本机实时大屏（浅色工作区；进入时侧栏自动折叠） |
| 7 | 诊断 | 上传日志、解析、AI 建议 |
| 8 | 报告 | 历次诊断结果 |
| 9 | 看板 | 近 7 日趋势；导出 PNG / PDF |

```bash
cd frontend
cp .env.example .env.development   # 可选，仓库已含 .env.development
npm install && npm run dev
```

---

## 日志分析流水线

实现：`LogAnalysisServiceImpl#startAnalysisAsync`

| 阶段 | 说明 |
|------|------|
| 分片读取 | `shardSize` 控制内存 |
| Drain-Plus | `log.pipeline.analyzer.drain` |
| 异常检测 | 启发式 + 综合评分 + IntelligentRouter（按模板去重，上限 400 条；大任务可 `skipLlm` 跳过逐条 LLM） |
| 进度 | 89% 后为综合评分与智能决策阶段（映射至 90%–97%）；前端轮询可显示细粒度步骤 |
| 导出 | `target/output/<taskId>/` |
| 取消 | `cancelAnalysis` |

解析原则：`LinuxLogParseSupport` 等失败时**不伪造时间戳**。

### 仪表盘统计口径

`GET /admin/statistics/log-summary?days=7`（实现：`StatisticsServiceImpl#getRecentLogSummary`）：

| 字段 | 含义 |
|------|------|
| `summaryDays` | 统计窗口天数（与 `days` 参数一致） |
| `totalTasks` / `totalLogs` | **窗口内**任务数、明细条数（`countSinceDays`） |
| `anomalyLogs` / `anomalyRate` | 窗口内异常条数；异常率 = 异常 / 窗口内总日志 × 100% |
| `taskTrend` / `anomalyDistribution` | 近 N 日趋势与分布 |

前端趋势图使用 `formatLocalDateKey`，避免 `toISOString()` 在 UTC+8 下日期错位。

实时分析写 ES 时优先走 **`LogDocumentAsyncIndexer`**（`log.elasticsearch.async.*`），避免阻塞分析线程。

### 后台日志采集（可选）

实现：`LogCollectorConfig` + `FileLogCollector` + `LogCollectorPathResolver`

| 配置 | 默认 | 说明 |
|------|------|------|
| `log.collector.enabled` | `true` | 关闭可显著降低开发机磁盘 I/O |
| `LOG_COLLECTOR_ROOT` / `log.collector.file.path` | `auto` | `auto` 时按 OS 扫描常见日志目录（非整盘） |
| `LOG_COLLECTOR_MAX_DEPTH` | `4` | 目录递归深度上限 |
| `LOG_COLLECTOR_POLL_MS` | `5000` | 文件轮询间隔（毫秒） |
| `log.pipeline.collector.dispatch-interval-ms` | `5000` | 采集批次派发间隔 |

开发机若 CPU/磁盘压力大，可在 `application-local.yml` 设 `log.collector.enabled: false`，或 `LOG_COLLECTOR_ROOT=./logs` 缩小范围。

---

## 配置参考

### 环境变量（常用）

| 变量 | 必填 | 含义 |
|------|------|------|
| `DB_URL` | 否 | JDBC；默认 **MySQL** `jdbc:mysql://localhost:3306/log_analysis?...` |
| `DB_DRIVER` | 否 | 默认 `com.mysql.cj.jdbc.Driver`；麒麟改为 `org.mariadb.jdbc.Driver` |
| `DB_PASSWORD` | **是** | 数据库密码 |
| `AI_API_KEY` | 助手需要 | 大模型密钥（DashScope 等） |
| `AI_BASE_URL` | 否 | 兼容网关 |
| `QDRANT_URL` | 知识库 | 默认 `http://localhost:6333`；Cloud 填集群 URL |
| `QDRANT_API_KEY` | Cloud 需要 | Qdrant Cloud 请求头 `api-key` |
| `QDRANT_COLLECTION` | 否 | 默认 `ops_knowledge` |
| `KNOWLEDGE_ENABLED` | 否 | 默认 `true` |
| `LOG_COLLECTOR_ROOT` | 否 | 默认 `auto`；可设为 `./logs` 或绝对路径 |
| `LOG_COLLECTOR_MAX_DEPTH` | 否 | 默认 `4` |
| `LOG_COLLECTOR_POLL_MS` | 否 | 默认 `5000` |
| `AGENT_MIN_PRIVILEGE` | 否 | 生产建议 `true` |
| `AGENT_RUN_AS_USER` | 否 | 默认 `award-agent` |
| `AGENT_RUNTIME_ENABLED` | 否 | Playbook 总开关 |
| `AGENT_AUTONOMOUS_ENABLED` | 否 | 自主运维 |
| `RF_FORCE_HEURISTIC_LOONGARCH` | 龙芯 | ONNX 不可用时启发式 |
| `SPRING_PROFILES_ACTIVE` | 环境 | `dev` / `prod` / `prod-kylin` / `test-kylin` |
| `APP_AI_AUDIT_RELAXED_READ` | 否 | **生产必须 false** |

本地密钥：复制 `application-local.example.yml` → `application-local.yml`（已 gitignore）。麒麟联调密钥见 `application-test-kylin.yml`。

### application.yml 关键块

| 路径 | 含义 |
|------|------|
| `server.servlet.context-path` | `/award-log` |
| `spring.sql.init.mode` | **`never`** — 不自动跑 `schema.sql`，需手动建表 |
| `spring.profiles.group.prod-kylin` | `prod` + `kylin` |
| `spring.profiles.group.test-kylin` | `prod` + `kylin` + `test-kylin` |
| `qdrant.*` / `knowledge.*` | 向量库地址、集合、分块与 RAG 开关 |
| `agent.runtime.enabled` | OpsRuntimeService |
| `agent.perception.cache-ttl-ms` | 感知快照缓存 |
| `agent.autonomous.*` | 自主运维阈值与端口探测 |
| `agent.security.risk-score-*` | 风险分阈值 |
| `agent.paths.*` | 路径策略版本 `path-policy-v2-kylin` |
| `agent.service-restart.allowlist` | 可重启服务 |
| `ops.patrol.*` | 巡检 Cron；历史落库 `ops_patrol_history` |
| `ops.auto-remediation.*` | 有界自动修复 HYBRID/CONFIRM_FIRST |
| `ops.dry-run.global` | 全局演练，写命令不落地 |
| `ops.websocket.replay-buffer-size` | WS 断线重连消息缓冲条数 |
| `audit.retention-days` / `audit.cleanup-cron` | 审计表自动清理 |
| `log.elasticsearch.async.*` | ES 异步批量索引 |
| `log.collector.*` | 后台文件日志采集（path / max-depth / poll-interval） |
| `log.pipeline.decision.rf.force-heuristic-on-loongarch` | 龙芯 ONNX 降级 |

路径白名单也可通过控制台「路径白名单」写入 `agent-path-policy-overrides.json`（工作目录，已 gitignore）。

---

## 快速开始

### 环境

JDK 17 · Maven 3.8+ · **MySQL 8**（本地）或 **MariaDB 10.6+**（麒麟）· Node 18+ · 可选 Qdrant

### 开发者一条龙（Windows / 本地 MySQL）

```bash
# 1. 数据库（任选其一）
#    A) 本机 MySQL
mysql -u root -p -e "CREATE DATABASE IF NOT EXISTS log_analysis DEFAULT CHARSET utf8mb4;"
mysql -u root -p log_analysis < src/main/resources/schema.sql

#    B) Docker MySQL
docker compose up -d mysql
# 等待 healthy 后对容器执行 schema.sql（见 docker-compose.yml 注释）

# 2. 密钥：application-local.yml（见 application-local.example.yml）或 .env 导出 DB_PASSWORD、AI_API_KEY

# 3. 可选向量库
docker compose up -d qdrant
# 或使用 Qdrant Cloud：在 application-local.yml 填写 qdrant.url / api-key

# 4. 后端
mvn spring-boot:run

# 5. 前端（Vite 3000 → 代理 8088）
cd frontend && npm install && npm run dev
```

- 接口文档：http://localhost:8088/award-log/doc.html  
- 前端控制台：http://localhost:3000  
- Windows 开发可将 `application-dev.yml` 或 `application-local.yml` 中 `agent.min-privilege.enabled` 设为 `false`  
- 演练环境可设 `ops.dry-run.global=true`，验证写工具而不改机器

### 麒麟 / MariaDB 联调

```bash
# Profile test-kylin（MariaDB + 麒麟路径策略），密钥见 application-test-kylin.yml
export SPRING_PROFILES_ACTIVE=test-kylin
# 或 docker compose --profile mariadb up -d mariadb
```

---

## 本地开发与测试

```bash
mvn test                    # 单元测试（~40+ 用例）
mvn -DskipTests package     # 打包
```

| 测试类 | 覆盖 |
|--------|------|
| `LinuxLogParseSupportTest` | 日志解析 |
| `LogSafetyClassifierTest` | 日志删除安全 |
| `DiskToolTest` | 磁盘工具 |
| `OsRuntimeTest` | 麒麟/龙芯检测 |
| `ProductionProfileValidatorKylinTest` | prod-kylin 校验 |
| `HistoryFilterTimeTest` | 历史列表日期边界 |
| `McpToolParamReaderTest` | 二次确认是否升级真实写入 |

**安全回归建议**（发版前手测）：

1. 「删除整个系统」→ 工作台拦截，不执行只读工具  
2. 「预览清理 /tmp」→ 二次确认后仍为 `dryRun`  
3. 「真实删除」+ 参数 `confirmDelete` → 才真删  
4. 历史任务按日期筛选与列表 `createTime` 一致  
5. 仪表盘近 7 日卡片与趋势图日期对齐  

CI：`.github/workflows/ci.yml`（`mvn test` + `frontend` build）。

---

## 构建与部署

### Fat Jar

```bash
mvn -DskipTests package
java -jar target/award-log-*.jar --spring.profiles.active=prod
```

### 前端静态资源

```bash
cd frontend && npm ci && npm run build
# dist/ 由 Nginx 托管；/award-log 反代到 8088
```

参考：`scripts/deploy/nginx.sample.conf`、`deploy.sh`、`deploy.ps1`。

### Docker Compose（可选依赖）

```bash
# MySQL（本地默认）
docker compose up -d mysql
# 首次：对库执行 src/main/resources/schema.sql

# Qdrant（知识库向量）
docker compose up -d qdrant

# MariaDB（麒麟联调 profile）
docker compose --profile mariadb up -d mariadb
```

应用进程建议仍本机 `mvn spring-boot:run` 或 jar，便于 MCP 访问宿主路径。

---

## 麒麟 V11 + LoongArch

**Profile**：`prod-kylin`（= `prod` + `kylin`）；联调 **`test-kylin`**（= `prod` + `kylin` + `application-test-kylin.yml`）  
**数据库**：系统 MariaDB（`jdbc:mariadb://…`，见 `env.kylin.prod.example` / `application-test-kylin.yml`）

```bash
# 首次：目标机环境（MariaDB / award-agent / /opt/threshcore）
sudo bash scripts/deploy/setup_kylin_host.sh

bash scripts/deploy/deploy_kylin_loongarch.sh
sudo cp deploy/release-kylin/award-log.jar /opt/threshcore/
sudo cp -r deploy/release-kylin/dist /opt/threshcore/
sudo cp deploy/release-kylin/award-log.service /etc/systemd/system/
sudo systemctl daemon-reload && sudo systemctl enable --now award-log

# 验收（无需登录）
bash scripts/deploy/smoke_kylin_acceptance.sh
curl -s http://127.0.0.1:8088/award-log/api/platform/info
```

| 资源 | 路径 |
|------|------|
| 环境变量模板 | `scripts/deploy/env.kylin.prod.example` |
| 目标机初始化 | `scripts/deploy/setup_kylin_host.sh` |
| 最小权限 sudoers | `scripts/deploy/sudoers.award-agent.example` |
| systemd 单元 | `scripts/deploy/award-log.service` |
| Nginx 样例 | `scripts/deploy/nginx.kylin.sample.conf` |
| 检查清单 | `docs/deployment/麒麟V11-LoongArch生产上线检查清单.md` |

`OsRuntime.isKylin()` / `isLoongArch()`；`deliveryTargetMatch` 仅在目标机为 `true`。

---

## 近期更新

| 日期 | 变更 |
|------|------|
| 2026-05-29 | **性能与 I/O**：Qdrant 集合惰性创建 + 进程内单次 POST；状态/文档数 120s 缓存；日志采集 `auto` 限常见目录 + 深度/轮询可配 |
| 2026-05-29 | **产品化体验**：对话去掉 trace 溯源展示；MCP 执行结果中文化；环境页 MCP 注册数解析修复；态势页改为浅色工作区主题 |
| 2026-05-29 | **日志分析**：大任务智能 `skipLlm`；进度 90%–97% 细粒度映射；模板去重上限 400 |
| 2026-05-29 | **知识库 RAG**：Qdrant + DashScope Embedding；`KnowledgeController`；对话 SSE 引用元数据；`KnowledgeBase.vue` |
| 2026-05-29 | **产品化前端**：工作台/洞察双分组导航；环境状态页；对话计划卡片 / RAG 依据条；结构化 MCP 结果与 GFM Markdown |
| 2026-05-29 | **数据库**：本地默认 MySQL 8；`spring.sql.init.mode=never` 手动建表；麒麟仍 MariaDB（`test-kylin` / `prod-kylin`） |
| 2026-05-29 | **稳定性**：日志分析 89% 后进度与 Router 上限；知识库空集合容错；前端 dev baseURL / Vite 代理修复 |
| 2026-05-23 | **McpToolCatalog**、MCP 分层、巡检持久化、感知缓存、ES 异步索引、全局 dry-run、审计清理、WS 缓冲 |
| 2026-05-24 | MCP 二次确认与 `INTENT_TOOL_MISMATCH`；一键安全自检 `GET /api/security/self-check` |

---

## 监控与排障

### Actuator

`health`、`metrics`、`prometheus`（见 `management.endpoints`）。指标示例：`threshcore_patrol_zombie_processes`、`threshcore_disk_usage_percent`。告警规则样例：`docs/deployment/prometheus-alerts.yml`。生产勿公网裸暴露。

### 常见问题

| 现象 | 处理 |
|------|------|
| prod 启动退出 | 检查 `DB_PASSWORD`；确认已手动执行 `schema.sql` |
| 前端 Network Error / 401 | 登录；`VITE_AWARD_LOG_BASE_URL=http://localhost:3000/award-log`（勿缺端口） |
| 知识库 500 / 空列表异常 | 检查 `QDRANT_URL` / `QDRANT_API_KEY`；空库应显示 0 文档而非报错；集合在**首次上传**时创建 |
| 日志分析卡在 89% | 大文件智能决策耗时属正常；升级后见 90%+ 进度；仍失败查后端日志 |
| 开发机 CPU/磁盘 I/O 高 | 设 `log.collector.enabled: false` 或 `LOG_COLLECTOR_ROOT=./logs`；重启后端 |
| MCP 0/0 或连接失败 | 确认已登录；**环境** 页查看 MCP 状态；`GET /api/mcp/tools`（响应为 `{ success, data }`） |
| AI 无回复 | `AI_API_KEY`、`AI_BASE_URL` |
| 助手不调工具 | `agent.assistant.use-tool-agent-default` |
| MCP 权限错误（Windows） | `agent.min-privilege.enabled=false`（仅 dev） |
| ONNX 龙芯加载失败 | `RF_FORCE_HEURISTIC_LOONGARCH=true` |
| 感知快照过旧 | `useCache=false` 或调小 `cache-ttl-ms` |
| 历史筛选无结果 | 确认 `startTime`/`endTime` 为 `YYYY-MM-DD` |
| 二次确认仍真删 | 检查是否传了「真实删除」或 `confirmDelete=true` |
| 审计表启动报错 | 建表失败已降级不阻断启动；需 DBA 补建 `ops_audit_trace` 等表 |

---

## 仓库结构

```
项目根目录/
├── pom.xml
├── README.md                     # 本文
├── docker-compose.yml
├── .env.example
├── docs/deployment/              # 麒麟上线清单、生产配置示例
├── frontend/                     # Vue 控制台（唯一前端主线）
├── scripts/deploy/               # 部署与验收脚本
├── src/main/java/com/award/log/
│   ├── AwardWinningLogApplication.java
│   ├── controller/               # REST（含 Knowledge、AutonomousOps、Perception、Platform、SecuritySelfCheck）
│   ├── agent/                    # OpsRuntimeService、编排器、OpsIntentRouter
│   │   └── audit/OpsAuditPhases.java
│   ├── util/HistoryFilterTime.java  # 历史列表日期解析
│   ├── mcp/
│   │   ├── McpToolCatalog.java / McpTool.java
│   │   ├── dispatch/             # McpToolDispatcher、McpToolParamReader
│   │   └── tools/                # 全部 @Tool
│   ├── service/mcp/              # McpSecurity/Execution/AuditService
│   ├── security/                 # Gate、OpsPathPolicy、OpsSecurityKernel
│   ├── service/                  # 业务、KnowledgeBase、PatrolHistory、LogDocumentAsyncIndexer、SecuritySelfCheck
│   ├── metrics/                  # PatrolMetricsExporter
│   ├── analyzer/ / analysis/     # Drain、日志解析
│   ├── collector/                # FileLogCollector、LogCollectorPathResolver
│   ├── decision/                 # RF ONNX、规则融合
│   ├── platform/                 # KylinCommandProbe、OsRuntime
│   └── config/                   # WebMvc、ProductionProfileValidator
├── src/main/resources/
│   ├── application.yml
│   ├── application-dev.yml
│   ├── application-kylin.yml     # 麒麟路径/命令探测
│   ├── application-local.example.yml
│   ├── application-test-kylin.yml  # 麒麟/MariaDB 联调
│   └── schema.sql
└── src/test/java/
```

### 名词速查

| 术语 | 说明 |
|------|------|
| **MCP** | 进程内 Java `@Tool`，非独立 MCP Server |
| **Playbook** | `OpsIntentRouter` 决定的规则编排路径 |
| **traceId** | 一次运维操作的审计流水号 |
| **dry-run** | 写工具默认只预览命令 |
| **RAG** | 知识库向量检索增强对话（Qdrant + Embedding） |
| **prod-kylin** | 生产 + 麒麟安全与路径策略 |
| **McpToolCatalog** | 启动时扫描工具 Bean，替代手写 ALLOWED_TOOLS |

---

## 报表导出（前端）

| 页面 | PNG | PDF | 说明 |
|------|-----|-----|------|
| 看板 | 分图导出 | jspdf 多图 A4 横向 | `dashboardExport.js` |
| 溯源 · 访问记录 | 表格截图 | 多页 PDF | `tableExport.js` |
| 溯源 · 执行链路 | 列表截图 | 多页 PDF | 同上 |
| 执行链路 · 详情 | 详情截图 | 多页 PDF | 含执行步骤与结果摘要 |

导出前会临时展开表格/摘要滚动区域，尽量包含当前已加载的全部行。

---

## Git 与发布

- 勿提交：`.env`、`application-local.yml`、`application-prod.yml`、`alarm-config.json`（含真实密钥）  
- CI：`.github/workflows/ci.yml`；`PROD_BUILD=1` 时 `deploy.sh` 强制跑测试  

---

## 许可协议

本项目采用 **Apache License, Version 2.0**，全文见 `LICENSE`。

Copyright 2026 zhaowenkai（见 `LICENSE` 与 `NOTICE`）。欢迎 Issue 与 Pull Request。
