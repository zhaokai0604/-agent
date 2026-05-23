# 麒麟高级服务器版 V11 + LoongArch64 生产上线检查清单

**系统**：ThreshCore（award-log）  
**交付 Profile**：`prod-kylin`（= `prod` + `kylin`）  
**验收接口**：`GET /award-log/api/platform/info`（无需登录）

---

## 一、目标环境

| 项 | 要求 |
|----|------|
| 操作系统 | 银河麒麟高级服务器版 V11 |
| CPU | LoongArch64（`uname -m` → `loongarch64`） |
| JDK | 17（龙架构本地构建，非 x86 交叉运行） |
| MySQL | 8.0+，库表已执行 `schema.sql` |
| 可选 | Nginx 反代、systemd 托管 |

---

## 二、构建（在目标架构机器执行）

```bash
cd /path/to/threshcore
bash scripts/deploy/deploy_kylin_loongarch.sh
# 产物: deploy/release-kylin/
```

- [ ] `uname -m` 为 `loongarch64`
- [ ] `java -version` 为 17
- [ ] Maven 测试全部通过（脚本强制 `SKIP_TESTS=0`）
- [ ] 生成 `award-log.jar` 与 `dist/` 前端静态资源

---

## 三、部署

```bash
sudo mkdir -p /opt/threshcore/logs
sudo cp deploy/release-kylin/award-log.jar /opt/threshcore/
sudo cp -r deploy/release-kylin/dist /opt/threshcore/
sudo cp scripts/deploy/award-log.service /etc/systemd/system/
sudo cp scripts/deploy/env.kylin.prod.example /etc/threshcore/threshcore.env
# 编辑 threshcore.env 填写 DB_PASSWORD、AI_API_KEY
sudo systemctl daemon-reload
sudo systemctl enable --now award-log
```

- [ ] 环境变量文件权限 `chmod 600 /etc/threshcore/threshcore.env`
- [ ] `SPRING_PROFILES_ACTIVE=prod-kylin`
- [ ] `RF_FORCE_HEURISTIC_LOONGARCH=true`（ONNX 龙架构回退）
- [ ] `APP_AI_AUDIT_RELAXED_READ=false`
- [ ] `AGENT_MIN_PRIVILEGE=true`，已配置 `award-agent` + sudoers

---

## 四、启动日志核对

- [ ] 出现 `[prod-kylin] 交付目标环境匹配：麒麟 + LoongArch64`（目标机上）
- [ ] `[Kylin] 系统命令探测` 缺失数 ≤ 2
- [ ] 无 `[prod] 未配置数据库密码` 退出

---

## 五、自动化验收脚本

```bash
export BASE=http://127.0.0.1:8088/award-log
bash scripts/deploy/smoke_kylin_acceptance.sh
```

| 检查项 | 通过标准 |
|--------|----------|
| Actuator | `/actuator/health` → UP |
| 平台信息 | `acceptance.deliveryTargetMatch` = true（目标机） |
| MCP | 工具列表含 `AutonomousOpsTool` |
| 自主运维 | `POST /api/ops/autonomous/run?readOnly=true` 返回 traceId |

---

## 六、功能验收（建议截图留档）

1. **登录** → 仪表盘有数据  
2. **工作台** → 「自主运维」→ 全工具融合报告  
3. **工具控制台** → `AutonomousOpsTool` / `DiskTool` 可执行  
4. **写操作护栏** → 清理类工具需风险确认 + 「确认执行」  
5. **运维链路审计** → traceId 可查到感知→处置步骤  
6. **日志分析** → 上传小文件完成；RF 状态为 `HEURISTIC_LOONGARCH` 或 `READY`  
7. **定时巡检** → 顶栏告警/关联快照更新  

---

## 七、安全（生产必查）

- [ ] Knife4j `/doc.html` 不对公网开放  
- [ ] Actuator 仅内网  
- [ ] `agent.service-restart.allowlist` 无 sshd/mysqld 等关键服务  
- [ ] `agent.paths` 白名单与现场目录一致  
- [ ] `agent.autonomous.run-after-patrol=false`（首轮建议）

---

## 八、回滚

```bash
sudo systemctl stop award-log
sudo cp /opt/threshcore/award-log.jar.bak /opt/threshcore/award-log.jar
sudo systemctl start award-log
# 急停写操作: 在 threshcore.env 设 OPS_AUTO_REMEDIATION_ENABLED=false
```

---

## 九、交付物清单（赛题/验收）

- [ ] `deploy/release-kylin/` 构建产物  
- [ ] 目标机 `uname -a`、`java -version` 截图  
- [ ] `/api/platform/info` JSON 截图（`deliveryTargetMatch: true`）  
- [ ] 自主运维 Markdown 报告截图  
- [ ] journalctl / systemctl 样例（可选）

---

*文档版本：与工程 `prod-kylin` profile 同步（2026-05）*
