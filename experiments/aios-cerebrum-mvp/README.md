# AIOS + Cerebrum 最小可行性样例

这个隔离样例用于先验证比赛架构边界：两个专业角色通过 Cerebrum 调用 AIOS Kernel 上的模型接口；两个角色的首轮分析并发执行；随后交换结构化摘要完成一轮双向交互；磁盘角色调用本地合成数据工具。样例不连接真实主机，也不执行任何系统写操作。

## 边界与证据口径

- 角色模型调用使用 Cerebrum 官方示例展示的 `llm_chat_with_tool_call_output` 接口和 `config.get_kernel_url()`，因此每次推理请求经 Cerebrum 配置的 AIOS Kernel URL 发出。
- 首轮并发和对话交接由这个 Python 样例协调。它验证 SDK 与 Kernel 调用链，不足以证明 AIOS Kernel scheduler 本身编排了业务 DAG；赛题正式架构需先通过该样例检查 Kernel 调度记录，并确认 SDK 暴露的 Agent 级运行/调度接口。
- `fake_capacity_probe` 只返回仓库内固定的合成观测，不读取本机目录、服务或指标。
- 输出只含四次角色调用摘要、交接记录、工具结果与耗时。不保存 API Key、完整提示或模型私有思维过程。
- 不在这里实现与 ThreshCore 重复的审批、权限或写入路径。正式集成时，Python Agent 通过受限 REST 工具调用 ThreshCore 现有预览和执行 API；写操作仍必须由 ThreshCore 服务端审批门与 `OpsExecutionPipeline` 放行。

## 环境要求

官方 Cerebrum README 当前建议 Python 3.10 或 3.11。用户已在另一台可连接的 Ubuntu 22.04 shell 核实 `python3` 为 3.10.12；本会话未附加该 shell，无法独立复核或直接在那里执行。该环境比 Windows 原生 Python 3.14 更适合本 PoC。需要准备 AIOS Kernel 和 Cerebrum 源码仓库，并在 Linux 侧使用独立虚拟环境安装 SDK。不要把密钥提交到项目；模型与 Kernel 配置沿用 AIOS 本地配置。

在已安装 Cerebrum 的虚拟环境中，从项目根目录运行：

```powershell
python experiments/aios-cerebrum-mvp/run_mvp.py
```

Linux 环境使用对应虚拟环境的 `python3` 执行同一脚本。脚本需要 AIOS Kernel 可用并已配置模型；不满足时会保留实际异常，不生成伪成功输出。

## 预期事件

输出事件应包含 `FAKE_TOOL_RESULT`、两条并发首轮调用的 `AGENT_REPLY`、`DISK_TO_LOG`、`LOG_TO_DISK` 两条交接，以及双方针对对方摘要的 `AGENT_REPLY`。只有这些调用经真实 Cerebrum SDK 返回且 Kernel 侧能查到对应模型请求，才算完成 SDK/Kernel 冒烟；静态代码检查不算实跑。

## 执行环境检查（2026-11-02）

- `python -m py_compile experiments/aios-cerebrum-mvp/run_mvp.py` 通过。
- Windows PowerShell 当前解析到 Python 3.14.0；在那里运行脚本因未安装 `cerebrum` 包，在 SDK 导入阶段以 `ModuleNotFoundError` 退出。没有发生模型请求。此结果不代表 Linux 环境的安装或运行结果。
- 用户报告 Ubuntu 22.04 shell 上 `python3 --version` 为 3.10.12，并指出其尝试安装时遇到的 PyPI 403 是远程连接代理限制。该 shell 未附加到本会话，因此安装、Kernel 连通和模型调用仍待在该环境实测。
- Windows 本机 Docker CLI 有安装，但 Docker Engine 命名管道不可用；这不影响上述独立 Ubuntu 环境判断，也不是当前首选验证路径。
- 因此这是已写好、语法通过且尚未在目标 Linux 环境实跑的 PoC。真实 SDK/Kernel 可行性仍未确认。

## 后续决策

若本样例无法通过官方 SDK 调用 AIOS Kernel，应先处理 Python 版本、AIOS 模型配置、Kernel 连通或 SDK 版本问题，不开始开发四路协作。若调用可用，再用最小实例检查 AIOS Kernel 侧实际调度和上下文管理证据，然后决定 DAG 执行器应使用的 AIOS 接口。只有 Kernel 调度确实覆盖 Agent 任务运行，才把正式 DAG 提交给该调度层；否则将尚未覆盖的上层 DAG 能力如实标注并与赛题维护方确认。

参考： [Cerebrum 官方仓库](https://github.com/agiresearch/Cerebrum)（SDK README 展示 Python 3.10/3.11 环境、`config.get_kernel_url()` 与 `llm_chat_with_tool_call_output` 用法）；[AIOS 官方仓库](https://github.com/agiresearch/AIOS)（说明 Kernel 与 SDK 的职责边界）。
