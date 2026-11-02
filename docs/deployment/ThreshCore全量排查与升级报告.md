# ThreshCore 全量排查与升级报告

**检查日期：** 2026-10-29  
**项目目录：** `D:\软件杯历史代码\53011976源码\ThreshCore代码`  
**交付形式：** Markdown；未生成 PDF、临时脚本或中间报告。

## 一、结论摘要

本项目已具备较完整的受控运维 Agent 基础：日志采集与分析、持久化任务、AgentTeams 角色交接、人工审批、工具执行管线、效果验证和人工接管均有代码实现及测试覆盖。本轮针对日志时间/等级/异常标记与批次故障隔离，以及 AgentTeams 失败诊断记录，完成了小批修复。

截至当前升级批次，已运行的定向回归共 **94 项，0 失败、0 错误、0 跳过**，前端生产构建成功。这个本机测试结果不代表生产主机、真实 Kafka/Elasticsearch、麒麟/LoongArch 或真实业务效果已验收。仓库文档记录的上一轮完整 `verify` 为 **1235 项、0 失败、0 错误、1 跳过，指令覆盖率 80.8804%**；本轮修改后没有重跑完整门禁，因此不能把该数字称为本轮全量验证结果。

GOAI 2026 评审反馈与现有代码方向基本一致：基础工程和安全门控较完整，下一阶段应以可复现的 AgentTeams 真实协作、异常接管与恢复证据为重点。当前仍缺真实主机工具参与的端到端证据、稳定性/效果量化、跨平台实机验收以及论文/专利源文件。

## 二、系统组成与排查范围

- **后端：** Java/Spring Boot，负责日志采集、任务状态持久化、诊断与运维流程、身份/授权、AgentTeams 编排及工具调用。
- **前端：** Vue 工作台，提供日志分析、Agent/Skill、协作运行、审批和接管界面；前端构建成功不等于真实模型与主机工具的浏览器端到端验收。
- **持久化与交付：** Flyway 版本迁移管理业务结构；日志采集可对接 Kafka/Elasticsearch；部署材料覆盖容器与麒麟/LoongArch 目标环境，目标平台实测仍需区分。
- **受控执行：** 运维写操作由服务端权限、预览、人工确认、执行回执和独立效果验证组成；MCP/Runbook/AgentTeams 已有不同程度的管线接入，不能将 AgentTeams 的资源锁覆盖范围外推到旧入口。
- **本次范围：** 复核日志 WebSocket 消费与索引字段、日志批处理故障隔离、AgentTeams 工作线程失败事件；同时对照已有部署、恢复与竞赛评审材料，记录证据缺口。

## 三、本轮完成的修复

### 1. 实时日志字段和索引一致性

- WebSocket 日志等级改用全项目公共等级解析器，覆盖 Nginx/JUL/Syslog 等已有规则，避免入口间各自用关键字子串给等级。
- 事件时间沿用日志行解析出的时间，并传入 Elasticsearch 文档；无可解析时间时才回退到采集时间。此前消息有事件时间，但 ES 文档时间始终取入库时间。
- 异常标记只依据归一化后的 `ERROR/WARN/FATAL` 等级；移除了对正文中 `error/fail/warn` 的裸子串搜索，避免路径或普通说明文本误标异常。
- 每条日志的实时分析器和 ES 写入分别隔离异常。一条日志的分析失败不会中止后续索引；ES 单条写入失败也不会阻止下一条继续处理。
- 日志分析遇到无法转换的日期值时回退为“未解析时间”，不再因单条日期格式异常让整个分片失败。
- WebSocket 没有客户端时，服务端采集/分析消费者仍持续运行；断开最后一个客户端不再停止消费。关闭应用时注销分析器回调并清理会话。
- 前端同页并发上传会合并；单文件上传键保存在 session 中供超时重试复用，服务端按用户作用域和文件 SHA-256 校验后返回原任务。同键换内容会拒绝；批量上传按文件序号派生子键。
- 上传幂等记录通过 V7 持久化。预留在任务创建前写入；短时并发重试复用预留任务 ID；超过执行租约仍无任务行的孤立预留允许后续请求接管。

### 2. AgentTeams 失败可诊断性

- 工作线程失败现在会追加 `WORKER_FAILURE` 事件，包括失败阶段、稳定错误码、异常类型和脱敏、截断后的摘要，便于操作者从时间线判断接管原因。
- 事件不记录堆栈或原始输入；摘要经过现有凭据脱敏策略处理。旧执行令牌产生的迟到失败不会覆盖新执行状态。
- AgentTeams 运维说明已同步事件字段和记录边界。
- 报告导出异常现在在持有同一任务行锁的事务里提交 `FAILED`，再向调用方返回失败；并发重试按行锁顺序执行，较早的失败不再用迟到事务覆盖较新的成功状态。成功重试会清除旧的“报告生成失败”错误，同时保留其他任务错误。

## 四、验证结果

| 定向套件 | 用例数 | 失败/错误/跳过 |
|---|---:|---:|
| `LogStreamTimestampTest` | 6 | 0 / 0 / 0 |
| `LowCoveragePackagesBulkTest` | 11 | 0 / 0 / 0 |
| `LogCollectorManagerTest` | 4 | 0 / 0 / 0 |
| `AgentTeamServiceTest` | 10 | 0 / 0 / 0 |
| `TeamRunStoreTest` | 6 | 0 / 0 / 0 |
| `DurableLogTaskStoreTest` | 13 | 0 / 0 / 0 |
| `TaskStateTransitionTest` | 1 | 0 / 0 / 0 |
| `LogAnalysisServiceImplTest` | 13 | 0 / 0 / 0 |
| `LogAnalysisServiceImplCoverageTest` | 7 | 0 / 0 / 0 |
| `DatabaseMigrationTest` | 6 | 0 / 0 / 0 |
| `LogAnalysisControllerSmokeTest` | 8 | 0 / 0 / 0 |
| 前端既有 Node 测试 | 9 | 0 / 0 / 0 |
| **合计** | **94** | **0 / 0 / 0** |

执行方式为 Maven 离线定向测试，使用项目本地 Maven 仓库；任务存储/删除导出竞争用例以 H2 和临时文件验证。Windows 沙箱限制系统 Temp 目录，任务相关测试通过 Maven 同进程模式及项目 `target/test-tmp` 路径运行。AgentTeams 用例使用 H2 与模型/工具替身；日志 WebSocket 用例验证本地解析和失败隔离。以上不等于连接外部模型、Kafka、ES 或生产主机的联调。

前端命令：`npm test`（9 项通过）、`npm run build`（成功）。生产构建仍报告 ECharts 与 Element Plus 等分包大于 720 kB 的体积警告；本轮未调整拆包策略。

## 五、系统排查结论与剩余风险

### P1：AgentTeams 真实运行证据仍需补齐

代码已实现诊断、规划、人工批准、受控执行、执行后验证、失败接管和迟到回执隔离。仓库现有文档记载：真实模型曾在隔离环境完成一次三角色与人工批准流程；这不证明真实文件操作、业务效果或稳定成功率。默认跳过的主机联合实测也不能视为生产验收。

需要补充可复现的受控试验：多次独立运行、成功/接管/失败率、阶段耗时、模型调用和工具回执、人工接管原因、恢复结果；分别呈现纯模型、模拟工具、真实隔离主机工具三种边界。真实主机步骤需由项目方明确隔离目标和授权环境，本报告不将外部写操作列为已执行项目。

### P1：日志跨进程可靠性和统一资源协调尚未闭环

- 实时 WebSocket fan-out 有容量上限，压力下会丢弃最旧记录以限制内存；应增加可见的丢弃指标/告警及恢复说明。
- 文件采集游标、Kafka 发送确认与重启续传已有实现和定向故障测试；真实 Kafka broker、网络中断和重复消费语义仍需实测。没有 Kafka 确认时的重放/重复风险应纳入验收。
- WebSocket、日志分析、AgentTeams 和旧运维入口的所有写操作仍未统一到一个跨入口资源锁/审计视图；现有 AgentTeams 锁只覆盖其自身入口。
- Elasticsearch 单条写入失败目前不会再中止整批，但本轮没有增加持久失败队列或自动补投；需要监控/补偿策略来避免索引缺口长期不可见。

### P2：安全审计、Skill 治理与运行指标

- 已有审批、执行意图、工具回执、状态时间线和基础凭据脱敏；统一审计保留周期、个人信息识别及跨入口查询还需补齐，并以合成敏感样本验证。
- Skill 发布/指纹固定已有代码和测试基础；应继续补齐 Skill 契约兼容性、版本迁移/回滚策略及对外可复现制品。
- 需要建立可量化运行指标：任务完成率、接管率、误报率、日志丢弃/重试数、端到端耗时、恢复成功率和资源开销，并明确基线和采样方法。

### P2：部署与交付材料

- 部署、迁移、恢复和麒麟 V11/LoongArch 清单已存在；Windows 测试不能替代麒麟/LoongArch 实机启动、迁移、采集、工具执行及恢复验收。
- 仓库没有找到论文或专利正文/申请文件。当前只能审查代码、实验报告和部署材料，不能评价论文论证、专利权利要求、查新结果或法律状态。
- 仓库有实验报告，但在形成比赛提交材料前仍应核对原始数据、样本范围、对照组、指标定义、运行日志、许可证和可复现命令，不能用模拟工具测试冒充真实业务结果。

## 六、建议升级顺序

1. **真实 AgentTeams 证据闭环：** 固定隔离主机、合成日志与只读基线，逐步验收三角色协作、人工批准、异常接管、迟到回执和恢复；每次试验保存脱敏输入、版本、运行时间线、外部状态前后快照与指标。
2. **日志交付可靠性：** 接入真实 Kafka/ES 测试环境，覆盖重启、断网、权限错误、慢消费者、磁盘满和毒性日志；补丢弃/失败指标及可操作的补投或人工恢复流程。
3. **统一写入管控：** 将 AgentTeams 资源锁、Runbook、MCP、自动修复等写入口纳入统一资源键、批准 trace 和审计查询，验证并发冲突和跨入口竞态。
4. **安全与 Skill 治理：** 扩展合成凭据/个人信息脱敏样本，定义审计保留周期、Skill 契约版本与回滚方案。
5. **平台与竞赛复现：** 完成麒麟/LoongArch 真实部署验收，形成基准数据及可复现材料；取得论文/专利原件后再做专门审阅。
6. **门禁收尾：** 每个升级批次跑定向回归；发布候选前重新跑完整 `verify`、前端生产构建和目标平台验收，并将当前提交与验证报告绑定。

## 七、证据文件

- AgentTeams 运行、接管和验证边界：`docs/deployment/AgentTeams运行与接管.md`
- 版本迁移、任务恢复与运行边界：`docs/deployment/版本迁移与任务恢复.md`
- 部署与麒麟/LoongArch 上线清单：`docs/deployment/部署文档.md`、`docs/deployment/麒麟V11-LoongArch生产上线检查清单.md`
- 实验报告：`docs/demo/应用方案六组实验报告.md`
- 本轮代码：`src/main/java/db/migration/V7__Upload_idempotency.java`、`src/main/java/com/award/log/websocket/LogStreamWebSocketHandler.java`、`src/main/java/com/award/log/service/impl/LogAnalysisServiceImpl.java`、`src/main/java/com/award/log/task/DurableLogTaskStore.java`、`src/main/java/com/award/log/agent/team/AgentTeamService.java`、`src/main/java/com/award/log/agent/team/TeamRunStore.java`
- 本轮回归：`src/test/java/com/award/log/websocket/LogStreamTimestampTest.java`、`src/test/java/com/award/log/service/impl/LogAnalysisServiceImplTest.java`、`src/test/java/com/award/log/task/DurableLogTaskStoreTest.java`、`src/test/java/com/award/log/agent/team/AgentTeamServiceTest.java`

**最终判断：** 本轮日志数据质量、批处理故障隔离和 AgentTeams 失败可观测性已完成并通过定向回归；项目整体已具备受控 Agent 运维的工程基础，但“真实 AgentTeams 协作运行证据、跨入口资源协调、生产级日志补偿及跨平台实测”仍是发布/比赛材料中的关键未闭环项。请按“已核实事实 / 代码存在但未实测 / 待团队补证据”口径对外呈现。
