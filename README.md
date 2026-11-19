# Giftia · 有长期记忆的 AI 陪伴助手

Giftia 是一个可本地运行的 AI 陪伴应用。它把流式对话、情绪理解和跨会话长期记忆放在同一条体验里：用户可以选择角色卡聊天，也可以在「记忆花园」中回看、检索和继续使用曾经留下的记忆。

当前默认运行时为 **Java 17 + Spring Boot + Spring AI** 后端与 **React + TypeScript + Vite** 前端。模型通过 OpenAI 兼容接口调用；会话、记忆和用户画像保存在本地 SQLite。

## 界面

前端采用浅色自然风和横向导航，主要包含四个入口：

- **今日空间**：每日心情签到、近期状态和陪伴入口。
- **专注聊天**：SSE 流式回复、会话管理、角色卡切换，以及模型支持时的图片输入。
- **记忆花园**：植物式记忆地图、时间线、关键词搜索、分类筛选和记忆详情；详情可以直接带入聊天草稿。
- **角色设置**：Giftia、知夏、晴川、理白、星遥五套系统预设，也支持保存自定义角色卡。系统规则由服务端维护，用户可编辑字段与固定规则分离。

## 核心能力

- **固定工作流编排**：Java `CompletableFuture` 并行情绪分析和记忆检索，汇合后组装上下文并流式生成回复；回复完成后异步抽取事实、更新摘要和用户画像。
- **分层长期记忆**：核心、重要、常规三级记忆，保存情绪、重要性、访问次数和时间标签，并按时间衰减与层级进行保留和排序。
- **混合检索**：使用 LLM 改写查询，结合 `BAAI/bge-m3` 向量和中文关键词双路召回，通过 RRF 融合，再按相关性、时效、情绪、重要性和层级重排。
- **跨会话上下文**：SQLite 持久化会话、工作摘要、未完成话题和结构化画像，在长对话中提供近期消息与历史摘要。
- **服务治理**：可选访问密钥和管理员密钥、用户/IP 限流、健康检查、请求与模型调用指标、有限容量日志。

## 技术栈

| 层次 | 技术 |
| --- | --- |
| 前端 | React 18、TypeScript、Vite 5、Zustand、React Markdown |
| 后端 | Java 17、Spring Boot 3.5.16、Spring MVC、Spring AI 1.1.8 |
| 对话与流式 | OpenAI 兼容 Chat / Embedding API、`StreamingResponseBody`、SSE |
| 编排 | Java `CompletableFuture`、有界线程池、固定步骤工作流 |
| 记忆与存储 | SQLite JDBC、Spring JDBC、WAL、混合检索与规则重排 |
| 验证 | JUnit 5、Mockito、MockMvc、ESLint、TypeScript、Vitest |

当前 Java 服务使用代码定义的固定编排，不依赖 LangGraph、LangChain 或 Python 子进程。`backend/` 中的 Python 代码作为旧实现和数据兼容参考保留；默认启动使用 `backend-java/`。

## 快速启动

### 环境要求

- JDK 17+（同时提供 `java` 和 `javac`）
- Node.js 18+（含 npm）
- 对话模型和向量模型的 API Key

### 配置并启动

在项目根目录复制配置模板并填写密钥：

```powershell
# Windows PowerShell
Copy-Item .env.example .env
```

```bash
# macOS / Linux
cp .env.example .env
```

默认配置为 DeepSeek 对话模型和硅基流动 `BAAI/bge-m3` 向量模型。可在 `.env` 中调整 `CHAT_MODEL`、`CHAT_BASE_URL`、`EMBED_MODEL`、`EMBED_BASE_URL`，并填写对应的 `CHAT_API_KEY` / 提供商密钥与 `EMBEDDING_API_KEY`。

最小配置示例：

```dotenv
DEEPSEEK_API_KEY=your_deepseek_key
SILICONFLOW_API_KEY=your_siliconflow_key
```

```powershell
# Windows
.\start.bat
```

```bash
# macOS / Linux
bash ./start.sh
```

启动脚本会构建 Java 后端并启动后端与 Vite 前端：

- 前端：<http://127.0.0.1:3000>
- 健康检查：<http://127.0.0.1:8000/api/health>

Windows 的日志和进程 ID 写入 `.run/`；修改模型配置后重启后端即可。默认数据库为 `backend/giftia.db`，也可用 `GIFTIA_DATABASE` 指定绝对路径。

## 部署建议

1. **前端静态化**：生产环境执行 `cd frontend && npm install && npm run build`，用 Nginx 或 Caddy 托管 `frontend/dist`，并将 `/api` 反向代理到 Java 服务。
2. **只暴露代理层**：Java 服务和前端开发服务器绑定回环或内网地址，由 Nginx/Caddy 统一提供 HTTPS、域名和 SSE 转发。
3. **安全注入密钥**：通过部署平台 Secret、systemd EnvironmentFile 或容器环境变量注入 API Key，不把真实密钥写入仓库或镜像。
4. **持久化数据**：持久化 `backend/` 数据目录、SQLite 数据库和角色配置；备份 SQLite 时使用一致性备份方式，并保留 WAL 文件处理策略。
5. **先单实例运行**：SQLite 适合单实例部署；需要横向扩展时，先把会话、记忆和画像迁移到服务化数据库，再增加应用实例。
6. **可观测与守护**：为 `/api/health` 配置健康检查，使用 systemd、Docker Compose 或 Windows 服务管理进程，并设置日志轮转和磁盘告警。

迁移背景和兼容细节见 [Java 后端迁移说明](docs/backend-java-migration.md)。项目沿用 [GNU AGPL-3.0](LICENSE) 许可证。
