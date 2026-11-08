# AGENTS.md · 项目约定（AI 编码助手每次会话先读本文件）

## 1. 这是什么

Java 21 + Spring Boot 3.5.16 + Spring AI 1.1.8 的**教学 / 面试作品**项目。
业务场景：隧道与高速路段的监控中心，RAG 知识库问答 + Agent 事件处置。
读者是刚从 Java 后端转 AI 应用开发的工程师，**代码的可读性和注释的教学价值优先于抽象技巧**。

## 2. 启动与验证命令

```bash
$env:ZHIPU_API_KEY="真实Key"
./mvnw spring-boot:run                 # 启动（自动增量入库）
./mvnw test                            # 纯单元测试，不调 API
set RUN_INTEGRATION=true
./mvnw.cmd test -Dtest=RagIntegrationEvalTest   # 端到端评估，需真 Key 与网络
```

- 从**项目根目录**运行：`app.rag.store-path` / `state-path` 是相对路径 `./data/...`，
  在别处启动会新建一个空向量库，表现为「所有问题都答未找到」。
- 本机未预装 Maven，用仓库自带的 `mvnw`；离线排障可用 `.tooling` 下的便携 Maven。

## 3. 目录职责

| 路径 | 职责 | 改动前注意 |
| --- | --- | --- |
| `config/` | Key 守卫、配置属性、VectorStore / ChatMemory Bean | 换向量库只改 `VectorStoreConfig` 这一个 Bean |
| `rag/ChineseTextSplitter` | 中文切块，纯 Java 无 Spring 依赖 | 必须保持可脱离 Spring 单测 |
| `rag/KnowledgeBase` | 加载 / 切块 / 入库 / 增量 / 落盘 | 先删旧块再写新块，顺序不能反 |
| `rag/RagService` | 检索 / 拼 context / 生成 / 溯源 / 拒答 | 故意不用 QuestionAnswerAdvisor，教学需要 |
| `agent/TunnelTools` | `@Tool` 实时数据查询（模拟） | **工具 description 就是提示词**，改逻辑必须同步改描述 |
| `agent/IncidentAssistant` | Tool Calling + ChatMemory + 结构化输出 | 结构化输出失败时降级为 `.content()` + ObjectMapper |
| `api/` | REST 薄层 + 全局异常 | 不写提示词、不写业务逻辑 |
| `src/main/resources/knowledge/` | 5 份 UTF-8 Markdown 知识库 | 文件名保持 ASCII；改内容后需 reindex |
| `src/test/resources/eval-cases.json` | 20 条评估集 | 期望关键词必须能在 `source` 文档逐字找到 |

## 4. 关键耦合（改之前先确认）

- `metadata["source"]` 与 `Document.id = "<文件名>#<序号>"` 被 `IngestState.chunkIds()` 依赖，
  用于精确删除旧块。**不要为了「统一命名」改掉字段名或 id 格式。**
- `app.rag.*` 的 `chunkSize` / `topK` / `similarityThreshold` 会影响评估集命中率，
  改动后必须重跑集成评估并对比基线。
- `application.yml` 里 `completions-path` / `embeddings-path` 是为智谱端点定制的，
  换成标准 OpenAI 端点时要改回 `/v1/...`。
- embedding 维度或模型一旦变化，磁盘上的 `data/vector-store.json` 全部失效，必须 force 重建。

## 5. 硬性禁止

- 禁止把任何真实 API Key、token 写进源码、配置、文档、测试、commit message。
- 禁止提交 `.env`、`data/vector-store.json`、`data/ingest-state.json`、`target/`。
- 禁止为了让测试变绿而删除/放宽评估集断言；只能修实现或明确说明理由并请人确认。
- 禁止引入新的外部中间件（Redis / PgVector / MQ）作为「顺手优化」—— 这是零依赖入门项目。
- 禁止在 `RagService` 里悄悄换成 Advisor 写法；要换先在 docs 里写明教学影响。
- 禁止改 `KeyGuard` 的 fail-fast 行为来「让服务先跑起来」。
- 禁止跳过测试就声称「已完成」。

## 6. 输出契约

| 阶段 | 交付 | 谁确认 |
| --- | --- | --- |
| 需求澄清 | 规格（目标 / 非目标 / 验收 / 开放问题），**停下来等确认** | 人 |
| 实现 | 代码 + 注释（说明「为什么」，不是「做了什么」） | Agent |
| 验证 | `./mvnw test` 全绿；涉及检索时跑集成评估并给出命中率变化 | Agent |
| 文档 | 新踩的坑回写到本文件 §8 | Agent |

## 7. 文档索引

- `README.md` — 快速上手与选型理由
- `docs/00-学习路线.md` — 分阶段练习
- `docs/01-架构与链路.md` — 三条数据流
- `docs/02-Java到AI概念映射.md` — 概念对照
- `docs/03-面试手册.md` — 追问与讲法
- `docs/04-已知问题与演进.md` — 局限与下一步

## 8. 已知坑点（真实踩过，改动前先扫一眼）

| ID | 现象 | 原因 / 处理 |
| --- | --- | --- |
| K01 | 启动正常、首次提问 404 | 智谱 v4 端点无 `/v1` 前缀；必须覆盖 `completions-path` / `embeddings-path` |
| K02 | 配置了模型却悄悄用了默认模型 | 1.1.x 属性是 `embedding.options.model` / `chat.options.model`（带 `options`），2.x 文档写法不同会静默失效 |
| K03 | 所有问题都答「暂未找到」 | 在错误的工作目录启动，读到了空的 `./data/vector-store.json` |
| K04 | 改了知识库内容但答案不变 | 增量入库按 sha256 跳过；需 `POST /api/admin/reindex` |
| K05 | 全量重建后集合里出现重复内容 | force 时状态被清空会跳过删旧块；必须先 `delete(旧id)` 再 `add` |
| K06 | 换 embedding 维度后检索报错 | 旧向量与新查询维度不一致；force 重建或删 `data/` 下两个 json |
| K07 | Windows curl 调智谱报 `schannel ... 0x80092013` | 证书吊销检查问题，加 `--ssl-no-revoke`；或改用 Python urllib |
| K08 | Git Bash 发中文 JSON 报 `Invalid UTF-8 middle byte` | shell 按 GBK 编码；用 Python 发送或显式 UTF-8 文件 |
| K09 | 工具「该调时不调」 | `TunnelTools` 的 `@Tool(description=...)` 描述过时或含糊 —— 工具描述就是提示词 |
| K10 | Maven 依赖下载慢 / 超时 | 本机 Clash 在 7897；Git 与 Maven 的代理要分别配置（Git 用 `http.https://github.com.proxy`，Maven 用 settings.xml） |
| K11 | 密钥扫描测试自己命中自己 | 正则字面量写在源码里会被扫到；用字符串拼接规避自指 |
| K12 | 评估集「模型总答错」 | 标准答案其实不在知识库里；`EvalCaseIntegrityTest` 专门防这个 |
| K13 | health 显示 UP，但首次问答答错 | Spring Boot 的 Web 容器早于 `ApplicationRunner` 就绪，向量库还没入库完。已加 `KnowledgeBaseHealthIndicator`（入库前 503）+ `RagService` 就绪兜底 |

> 每完成一次排障，把新坑追加到本表。这是把个人经验变成团队资产的最小动作。
