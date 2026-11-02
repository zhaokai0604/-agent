# ThreshCore Windows 打包说明

## 构建

在 Windows PowerShell、项目根目录执行：

```powershell
.\packaging\build-windows.ps1
```

默认执行前端回归测试、前端构建和后端 `mvn verify`（含原有覆盖率门禁），任一步失败都会停止。`-SkipTests` 仅供明确跳过验收的本地调试，不能作为正式发布通过的证明。Node 要求与 frontend/package.json 一致：20.19+ 或 22.12+。

脚本会依次完成：

1. 构建 Vue 生产前端；
2. 将 `frontend/dist` 放入 Spring Boot JAR 的 `static` 目录；
3. 用 `jlink` 生成随包携带的 Java 17 运行时；
4. 用 `jpackage` 生成应用目录和 Windows EXE 安装包；
5. 没有 WiX 时，将 WiX 3.14 便携构建工具缓存到 `target/tools`，不修改系统安装环境。

默认产物位于：

```text
target/windows-package/ThreshCore/       # 便携版
target/installer/ThreshCore-1.0.0.exe    # Windows 安装包
```

不生成安装器、只生成便携版时：

```powershell
.\packaging\build-windows.ps1 -PortableOnly
```

## 首次配置

安装包会在程序目录的 `config` 文件夹中放置一个不含密钥的 `application-local.yml`。
安装后请编辑它，填写本机 MySQL/MariaDB 密码；需要 AI 对话时再填写 API Key：

```text
config/application-local.yml
```

也可以通过 `DB_PASSWORD` 和 `AI_API_KEY` 环境变量提供。真实密钥不放入 JAR、安装包或 Git。

双击 `ThreshCore.exe` 后，程序会启动后端并自动打开：

```text
http://127.0.0.1:8088/award-log/
```

如果浏览器没有自动打开，先查看程序目录旁的 `logs\award-log.log`。常见原因是数据库未启动、数据库密码未配置，或 8088 端口已被占用。

当前版本仍然需要可访问的 MySQL/MariaDB；Qdrant、Elasticsearch、Kafka、Redis 等按配置开关决定是否启用。

## 本地验收

启动安装目录中的 `ThreshCore.exe` 后检查：

```powershell
curl.exe http://localhost:8088/award-log/actuator/health
curl.exe http://localhost:8088/award-log/
```

预期健康接口返回 HTTP 200 和 `{"status":"UP"}`，根路径返回 Vue 页面，而不是 Vite 开发服务器页面。
