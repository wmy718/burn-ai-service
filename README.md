# Burn AI 服务与测试前端

本仓库用于交接独立烧伤与瘢痕 AI 服务，包含指标提取、决策树推理、八段结构化报告、SSE 日志，以及配套测试页面。

## 目录

| 目录 | 内容 |
|---|---|
| `burn-ai-service` | Java 17 / Spring Boot 后端，默认8091端口 |
| `burn-ai-service-test-ui` | Node 原生代理与静态测试页面，默认5179端口，无需 npm install |
| `database` | 11张当前配置表的结构、配置数据和一键导入 BAT |
| `scripts` | BAT 调用的内部辅助脚本 |
| `docs/运行部署说明.md` | 从数据库导入到启动、模型配置和验证的完整步骤 |

## 快速开始

1. 安装 JDK 17、Maven 3.9、Node.js 18或以上和 MySQL 8.0或以上。
2. 双击 `database/import-database.bat`，按提示输入 MySQL 连接信息。密码隐藏输入；目标库已有同名配置表时停止，避免覆盖。
3. 将 `burn-ai-service/config-example.bat` 复制为同目录的 `config-local.bat`，填写数据库密码和自己的千问 API key。
4. 双击 `burn-ai-service/start-backend.bat`。
5. 双击 `burn-ai-service-test-ui/start-test-ui.bat`，打开 http://127.0.0.1:5179 。
6. 关闭时分别双击对应目录的 `stop-*.bat`。

详细配置及 Linux 本地运行方法见 [运行部署说明](docs/运行部署说明.md)。本仓库不包含患者业务库、历史执行记录、旧系统数据库或 Docker 部署材料。

配置数据来自2026年10月12日本机当前数据库：烧伤74个指标、瘢痕78个指标。未从远端服务器同步本次交接期间的新修改。
