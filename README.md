# Giftia

Giftia 是一个带长期记忆的 AI 陪伴助手。它把情绪理解、记忆检索和流式对话组合成一条可追踪的陪伴链路，并用浅色自然风的「记忆花园」把聊天记录、用户画像和重要回忆变成可以继续探索的空间。

![Giftia](frontend/public/logo.jpg)

## 界面

| 页面 | 体验 |
| --- | --- |
| **今日** | 心情签到、最近对话和记忆概览，快速回到当天的陪伴状态 |
| **专注聊天** | 角色卡驱动的对话、状态提示、逐 Token 回复和可选图片输入 |
| **记忆花园** | 植物式记忆地图、时间线、分类/关键词筛选、详情查看和继续聊天 |
| **个人与设置** | 用户切换、角色预设、自定义角色、用户画像和模型/访问设置 |

## 核心能力

- **固定 Agent 工作流**：并行情绪分析与记忆检索，汇合后组装上下文并流式生成回复，完成后异步更新记忆。
- **分层长期记忆**：核心、重要、常规三级记忆，结合时间、情绪、重要性和访问记录控制保留与召回。
- **混合检索**：查询改写、向量余弦相似度、中文关键词双路召回、RRF 融合和规则重排。
- **跨会话上下文**：SQLite 持久化会话、工作摘要、未完成话题、用户画像和记忆详情。
- **角色卡系统**：Giftia、知夏、晴川、理白、星遥五套系统预设；用户可以保存自定义角色，固定系统规则由服务端维护。
- **可运营服务**：SSE 输出、图片大小/类型校验、访问/管理密钥、用户与 IP 限流、调用指标、日志和健康检查。

## 技术栈

| 层次 | 技术 |
| --- | --- |
| 前端 | React 18、TypeScript、Vite 5、Zustand、Vitest |
| 后端 | Java 17、Spring Boot 3.5、Spring MVC、Spring AI |
| Agent 编排 | `CompletableFuture` + 有界线程池的代码化固定工作流 |
| 模型 | OpenAI 兼容 Chat / Embedding 接口；默认 DeepSeek 对话 + SiliconFlow `BAAI/bge-m3` 向量 |
| 检索与存储 | 关键词匹配、余弦相似度、RRF、SQLite JDBC、Spring JDBC、WAL |

当前运行时是 Java 工作流，不依赖 LangGraph、LangChain 或 Python 子进程；仓库中的 `backend/` Python 代码仅作为历史实现参考。

## 快速启动

环境要求：**JDK 17+**（必须包含 `javac`）、**Node.js 18+**（含 npm）。首次启动需要联网下载 Maven 和前端依赖。

```powershell
# Windows PowerShell
Copy-Item .env.example .env
```

```bash
# macOS / Linux
cp .env.example .env
```

在根目录 `.env` 中至少填写：

```dotenv
DEEPSEEK_API_KEY=your_deepseek_key
SILICONFLOW_API_KEY=your_siliconflow_key
```

默认使用 `deepseek-v4-flash` 对话模型和 SiliconFlow 的 `BAAI/bge-m3` 向量模型；也可以通过 `CHAT_BASE_URL`、`CHAT_MODEL`、`CHAT_API_KEY`、`EMBED_BASE_URL` 和 `EMBEDDING_API_KEY` 接入其他 OpenAI 兼容服务。

启动：

```powershell
.\start.bat       # Windows
```

```bash
bash ./start.sh   # macOS / Linux
```

访问地址：

- 前端：<http://127.0.0.1:3000>
- 健康检查：<http://127.0.0.1:8000/api/health>
- 本地日志和进程 ID：`.run/`

## 部署建议

1. **前后端分离**：执行 `cd frontend && npm run build`，用 Nginx、Caddy 或 CDN 托管 `frontend/dist`，并将 `/api` 反向代理到 Java 服务。
2. **进程托管**：生产环境用 `java -jar backend-java/target/giftia-backend-1.0.0.jar` 启动后端，并交给 systemd、Docker 或 Windows Service 管理；不要运行 Vite 开发服务器。
3. **密钥管理**：通过 Secret、EnvironmentFile 或云平台环境变量注入模型密钥、`GIFTIA_ACCESS_KEY` 和 `GIFTIA_ADMIN_KEY`，不要提交 `.env`。
4. **数据持久化**：为 `backend/`（尤其是 `backend/giftia.db`）挂载持久化磁盘。SQLite 使用 WAL，备份请使用在线备份工具或在停止写入后执行，避免直接复制活动数据库文件。
5. **网络安全**：生产环境在 HTTPS 反向代理后运行，收紧 `CORS_ORIGINS`，后端只监听内网地址，并通过 `/api/health` 配置存活检查。
6. **扩容边界**：当前限流、任务协调和指标都在单 Java 进程内，SQLite 适合单实例和低到中等并发。多实例部署前应迁移到集中式数据库，并准备共享限流和任务队列。
7. **现有仓库形态**：仓库未提供 Dockerfile、Terraform 或 Kubernetes 清单，部署编排需要按目标平台补充。

更多迁移背景见 [Java 后端迁移说明](docs/backend-java-migration.md)。

## 许可证

[GNU AGPL-3.0](LICENSE)
