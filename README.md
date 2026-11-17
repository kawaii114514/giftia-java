# Giftia — 有长期记忆的 AI 陪伴助手

Giftia 将情绪理解、记忆检索与流式对话组合成一个可本地运行的陪伴应用。前端采用浅色自然风的「记忆花园」，可以聊天、探索过往记忆、切换角色卡并管理用户档案。

默认后端已迁移为 **Java 17 + Spring Boot 3.5 + Spring AI**。本次后端迁移沿用现有 React 页面、HTTP 接口与数据路径；`backend/` 内的 Python 实现作为回退参考保留，默认启动不再依赖 Python。

## 已实现的功能

- **固定的三个职责节点**：通过 Java `CompletableFuture` 并行情绪分析与记忆检索，汇合后生成回复；SSE 逐 Token 推送，完成后保存对话并安排记忆更新。
- **分层长期记忆**：事实按核心、重要、常规三级组织，包含情绪、重要性、访问次数与时间标签；借鉴遗忘曲线计算时间权重，核心记忆获得更高保留优先级。
- **混合检索**：模型改写查询，使用向量余弦相似度与关键词匹配召回，通过 RRF 融合，再按检索分数、时间、情绪、重要性和层级五项规则加权排序。
- **跨会话上下文**：SQLite 保存工作摘要、未完成话题与结构化画像；对话时加载相关背景，长历史采用近期消息窗口和早期摘要缓存。
- **角色卡**：提供 Giftia、知夏、晴川、理白、星遥五套预设，支持按用户选择与保存自定义身份、性格、场景、开场白和示例。固定系统规则由服务端维护，不属于可编辑人设字段。
- **记忆花园**：植物式记忆探索、时间线、分类与文本组合筛选、记忆详情及继续聊天草稿；每日心情签到保存在当前浏览器中。
- **服务基础能力**：访问/管理双级密钥、用户与 IP 限流、请求与模型调用计数、平均耗时、错误数、有限容量的日志，以及依赖健康检查。
- **可选图片输入**：模型及其服务支持视觉时可发送图片。文本模型会在开启 SSE 前返回说明；图片解码大小上限为 10MB。

对话模型与向量模型使用外部 API，用户会话和记忆保存在本地；本地存储并不意味着模型推理离线运行。

## 技术与代码组织

| 部分 | 当前实现 |
| --- | --- |
| 前端 | React 18、TypeScript、Vite 5、Zustand |
| HTTP 与流式输出 | Spring MVC、`StreamingResponseBody`、SSE |
| 模型调用 | Spring AI 的 OpenAI 兼容 Chat / Embedding 客户端 |
| 工作流 | Java `CompletableFuture`、有界线程池、按用户协调对话与记忆回写 |
| 检索 | BAAI/bge-m3、关键词匹配、RRF、规则加权重排 |
| 持久化 | SQLite JDBC、Spring JDBC、事务、WAL |
| 验证 | JUnit 5、Mockito、MockMvc；前端 ESLint、TypeScript 构建与 Vitest |

Java 工作流是代码定义的固定编排，运行时不使用 LangGraph，也不调用 Python 子进程。原 Python 版本的 LangGraph 实现仅保留在 `backend/` 中。

| 目录 | 用途 |
| --- | --- |
| `backend-java/src/main/java/com/giftia/web/` | 接口、SSE、认证、限流、日志与指标 |
| `backend-java/src/main/java/com/giftia/workflow/` | 情绪与检索并行、上下文组装、流式生成、后台记忆更新 |
| `backend-java/src/main/java/com/giftia/memory/` | 事实、画像、工作摘要、混合检索与时间标签 |
| `backend-java/src/main/java/com/giftia/model/` | Spring AI 模型适配 |
| `backend-java/src/main/java/com/giftia/character/` | 固定规则、角色卡、配置持久化 |
| `backend-java/src/main/java/com/giftia/conversation/` | 会话管理与历史持久化 |
| `frontend/` | 当前记忆花园界面；此次后端迁移不修改前端源码 |
| `backend/` | 旧 Python 源码与沿用的数据位置 |
| `.run/` | 本地启动日志与进程 ID，不提交版本库 |

## 快速启动

需要完整 **JDK 17+**（`java`、`javac`）和 **Node.js 18+**（含 npm）。首次使用 Maven wrapper 和安装前端依赖需要网络，无需预先安装 Maven。`JAVA_HOME` 如已设置，应指向同一套 JDK。

首次配置：

```powershell
# Windows PowerShell；已有 .env 时不要覆盖
Copy-Item .env.example .env
```

```bash
# macOS / Linux；已有 .env 时不要覆盖
cp .env.example .env
```

在 `.env` 填入对话与向量模型的真实密钥。现有 `.env` 可直接沿用，不必重新创建。

```powershell
# 在项目根目录运行
.\start.bat
```

```bash
bash ./start.sh
```

启动入口会检查 Java、Node 和端口，调用 Maven wrapper 打包 Java 后端，再运行 Spring Boot jar 与现有 Vite 前端。它们固定使用后端 `127.0.0.1:8000`、前端 `127.0.0.1:3000`，避免 Vite 静默换端口；发现端口冲突时退出并提示，不结束已有进程。

- 前端：`http://127.0.0.1:3000`
- 后端健康检查：`http://127.0.0.1:8000/api/health`
- Windows：服务在隐藏的后台进程中运行，日志和 PID 写入 `.run/`。关闭启动窗口不会停止服务；通过输出的 PID 核对相应 Java / Node 进程后停止它们。
- macOS / Linux：脚本保持运行，按 Ctrl+C 停止本次启动的两个进程。

启动脚本为缩短启动时间使用 `-DskipTests package`；修改代码后请另外执行下面的完整验证命令。

### 分别启动，便于查看控制台

在项目根目录的终端 A：

```powershell
# Windows
cd backend-java
.\mvnw.cmd --batch-mode --no-transfer-progress -DskipTests package
java -jar target/giftia-backend-1.0.0.jar
```

```bash
# macOS / Linux
cd backend-java
sh ./mvnw --batch-mode --no-transfer-progress -DskipTests package
java -jar target/giftia-backend-1.0.0.jar
```

在另一个位于项目根目录的终端 B：

```bash
cd frontend
npm install
npm run dev -- --host 127.0.0.1 --port 3000 --strictPort
```

从项目根目录或 `backend-java/` 启动 Java 时，应用会查找项目根目录的 `.env`。默认数据目录仍为 `backend/`。默认 Java 服务不再提供旧 FastAPI 的 `/docs` Swagger 页面，健康检查请使用 `/api/health`。

## 模型与认证配置

配置入口为根目录 `.env` 或进程环境变量；进程环境变量及显式命令行配置优先于 `.env`。修改模型配置后重启后端生效，不需要编辑 Python 文件。

| 变量 | 默认值 / 作用 |
| --- | --- |
| `CHAT_MODEL` | `deepseek-v4-flash` |
| `CHAT_BASE_URL` | `https://api.deepseek.com/v1` |
| `CHAT_API_KEY` | 可选，直接指定对话密钥，优先于提供商变量 |
| `CHAT_MULTIMODAL` | 默认 `auto`，沿用原模型视觉能力目录；自定义接口可用 `true` / `false` 明确覆盖 |
| `CHAT_THINKING_ENABLED` | 默认 `true`，在支持的 DeepSeek / GLM / Qwen 对话中启用思考；设为 `false` 可关闭，分析与记忆调用始终关闭 |
| `DEEPSEEK_API_KEY` / `OPENAI_API_KEY` / `ZHIPU_API_KEY` / `DASHSCOPE_API_KEY` / `SILICONFLOW_API_KEY` | 根据对话接口地址选择相应密钥 |
| `EMBED_MODEL` | `BAAI/bge-m3` |
| `EMBED_BASE_URL` | `https://api.siliconflow.cn/v1/embeddings` |
| `EMBEDDING_API_KEY` | 优先使用；留空时尝试向量服务提供商的密钥 |
| `GIFTIA_ACCESS_KEY` | 可选，配置后业务接口需要 `X-Access-Key` |
| `GIFTIA_ADMIN_KEY` | 可选，日志、指标和旧版全局人设写接口需要 `X-Admin-Key` |
| `CORS_ORIGINS` | 默认允许 `http://localhost:3000,http://127.0.0.1:3000` |
| `GIFTIA_DATABASE` | 可选，指定数据库路径；默认 `backend/giftia.db`，建议使用绝对路径 |

例如，只需填写自己的密钥即可沿用默认 DeepSeek 对话与硅基流动 bge-m3 向量配置。两类接口的密钥分别校验；对话能成功不代表向量服务有额度或密钥正确。

未单独配置管理员密钥但已配置访问密钥时，管理员请求仍使用 `X-Admin-Key` 请求头，其值填写访问密钥。两项均未配置时按本地无认证模式运行。用户 ID 用于数据分区，这套共享密钥机制不是账号系统或租户权限隔离。

## 数据兼容与清空语义

Java 沿用 `backend/giftia.db`，以及 `backend/character_config.json`、`backend/prompt_config.json` 的现有格式和位置。不要为迁移删除数据库、重新初始化用户或覆盖已有配置文件。每个用户的会话、记忆、工作摘要和画像继续按原用户 ID 读取。

**清空记忆**现在会同时删除该用户的长期记忆、跨会话工作摘要和画像，保留会话历史与角色选择。旧对话的文字仍然可见，并非删除全部个人数据；若继续携带旧对话发送，模型仍可能从历史中读到原信息。要从空白上下文重新开始，请同时新建会话。后台旧记忆任务使用代次检查，避免清空前的任务随后写回被删除的记忆。

迁移范围、备份位置、验收与回退步骤见 [Java 后端迁移说明](docs/backend-java-migration.md)。

## API 兼容

以下接口同时支持 `/api/...` 与 `/api/v1/...`。错误响应使用 `{"detail":"说明"}`；参数校验通常为 422，不存在的资源为 404，无效记忆层级为 400，限流为 429。

| 方法 | 路径（以 `/api` 为例） | 用途 |
| --- | --- | --- |
| POST | `/chat/{user_id}` | SSE 对话 |
| GET / POST | `/conversations/{user_id}` | 列表 / 新建会话 |
| GET / PATCH / DELETE | `/conversations/{user_id}/{conv_id}` | 详情 / 改名 / 删除 |
| GET / POST | `/users` | 用户列表 / 使用 `user_id` 查询参数创建用户 |
| GET | `/memory/{user_id}/stats` | 记忆统计 |
| GET | `/memory/{user_id}/detail` | 记忆详情与工作摘要 |
| GET | `/memory/{user_id}/layers` | 记忆层级信息 |
| PATCH | `/memory/{user_id}/{memory_id}/layer?new_layer=2` | 调整层级 |
| DELETE | `/memory/{user_id}` | 清空长期记忆、工作摘要与画像，保留会话 |
| GET / PUT | `/profile/{user_id}` | 查看 / 更新画像 |
| GET / PUT | `/characters/{user_id}` | 查看 / 选择角色卡或保存自定义人设 |
| GET | `/config/model` | 当前模型配置与视觉能力标识 |
| GET | `/config/model-presets` | 预设模型目录 |
| GET / PUT | `/config/prompt` | 兼容旧版人设接口；写入需要管理员密钥 |
| GET | `/logs` | 有限容量的服务日志，需要管理员密钥 |
| GET | `/metrics` | 请求计数、错误计数、平均耗时与模型调用计数，需要管理员密钥 |
| GET | `/health` | 公开的服务和模型依赖状态 |

SSE 每个事件为一行 `data: ` 加 JSON，再以空行结束：

```text
data: {"type":"status","text":"正在回忆"}

data: {"type":"token","text":"你"}

data: {"type":"reply","text":"完整回复"}

data: {"type":"done","conversation_id":"uuid"}

```

失败时发送 `error` 事件，失败回复不会伪造 `done`。聊天默认按用户限流 30 次/分钟，普通请求按 IP 限流 60 次/分钟；限流状态位于单个 Java 进程内。健康检查的模型连通性结果缓存 30 秒；上游不支持模型列表接口时可能报告 `unreachable`，需结合实际聊天和服务日志判断。

## 开发与验证

```powershell
# Windows 后端测试与打包
cd backend-java
.\mvnw.cmd --batch-mode --no-transfer-progress verify
```

```bash
# macOS / Linux 后端测试与打包
cd backend-java
sh ./mvnw --batch-mode --no-transfer-progress verify
```

```bash
# 在 frontend/ 中执行
npm run lint
npm run build
npm run test
```

Java 测试使用临时数据库、临时配置、模拟模型或本地假服务，不要求真实模型密钥，也不应连接用户数据库。CI 默认执行 Java Maven 验证，并保留前端检查；Python 参考实现的检查可在 GitHub Actions 手动触发。

页面验收建议覆盖旧会话读取、流式聊天、切换用户、五套角色卡、自定义角色保存、记忆花园与详情、画像更新和清空记忆。测试通过不等于已经测量线上吞吐量；本项目未据此宣称 QPS、准确率或性能提升百分比。

## 许可证

沿用仓库 [GNU AGPL-3.0 许可证](LICENSE)。Copyright (c) 2026 wenbo-zhang1。
