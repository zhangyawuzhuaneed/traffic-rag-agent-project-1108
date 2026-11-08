# 规格：为 RAG 检索增加 BM25 混合召回（P0）

> 这是一份**填好的**规格示例，用来演示 `backend-sdd` 技能在 Java 项目里如何落地。
> 对应 `docs/04-已知问题与演进.md` 的 P0 改进。注意它停在「待确认」状态 ——
> 规格的价值就在于**先评审再写代码**。

## 1. Goal

在 `RagService.ask()` 的召回阶段，同时执行关键词检索（BM25）与向量检索，
用 RRF 融合后取 Top-K，提升精确术语（桩号 `K12+300`、`80 ℃`、`12122`、`70 ppm`）的召回稳定性。

## 2. Non-goals（本次明确不做）

- 不引入 Elasticsearch / 外部检索服务。
- 不重训或新增 embedding 模型，不重建线上向量库。
- 不改 `KnowledgeBase` 的切块策略与增量逻辑。
- 不改 REST 接口契约（`Answer` 结构保持兼容）。
- 不引入 Rerank 模型（列为下一阶段）。

## 3. Context（先写耦合，防止 plausible-but-wrong）

- 当前 `RagService` **手写**检索（`similaritySearch`），未使用 `QuestionAnswerAdvisor`；
  这是刻意的教学设计（`AGENTS.md` §5 禁止悄悄换成 Advisor）。
- `Document.id` 格式 `<文件名>#<序号>` 被 `IngestState.chunkIds()` 用于精确删旧块；
  新增的 BM25 索引必须使用**同一套 id**，否则删除时会漏掉关键词索引里的残留。
- `metadata["source"]` / `["section"]` 被 `RagService.buildContext()` 用于来源标记与溯源。
- 评估集 `eval-cases.json` 里 E05–E08、E11、E13 是精确数值题，是本需求的主要验证对象。
- 知识库是 5 份 classpath 内的 Markdown，启动时由 `AutoIngestRunner` 增量入库。

## 4. Constraints

| 约束 | 说明 |
| --- | --- |
| 依赖 | **不得新增外部中间件**；允许新增纯 Java 库（如 Lucene core），但需在 S2 明确报批 |
| 兼容 | `app.rag.*` 现有配置项全部保留；新增开关默认 `false`，保证行为可回滚 |
| 性能 | 单次问答 P95 增量 < 50ms（本地 5 份文档规模） |
| 可测 | 纯检索逻辑必须能脱离 Spring 与 LLM 单测，不消耗 API 费用 |
| 安全 | 不得把检索到的原文写进 INFO 日志（可能含敏感路段信息） |

> ⚠️ **已知违规项，必须在 S2 请求批准**：
> 若采用 Lucene，需要在 `pom.xml` 新增依赖，与 `AGENTS.md` §5「禁止顺手加外部中间件」的边界
> 需要澄清（纯库 vs 中间件）。**不得先加依赖再补说明。**

## 5. Interfaces

### 5.1 配置

```yaml
app:
  rag:
    hybrid-enabled: false        # 灰度开关，默认关闭，异常时一键回滚
    hybrid-keyword-weight: 1.0
    hybrid-vector-weight: 1.0
    rrf-k: 60
```

### 5.2 内部接口（新增）

```java
public interface HybridRetriever {
    /** 融合关键词与向量召回结果，返回按 RRF 排序的 Top-K。 */
    List<Document> retrieve(String question, int topK);
}
```

### 5.3 外部接口

`POST /api/rag/ask` 请求/响应结构**不变**；`Answer.citations` 的元素可能来自不同召回通道，
需在 `Citation` 中新增 `channel` 字段（`VECTOR` / `KEYWORD` / `BOTH`）——
⚠️ 这会改变 JSON 响应结构，属于对外契约变更，**需 S2 明确确认是否允许**。

### 5.4 错误与降级

| 情况 | 行为 |
| --- | --- |
| 关键词索引未构建 / 构建失败 | 自动退化为纯向量检索，WARN 日志，不抛异常到用户 |
| 两路都无结果 | 返回空 context，由既有提示词触发拒答 |
| 融合后重复 id | 按 id 去重，保留更高分，`BOTH` 标记 |

## 6. State & Data

- 新增内存态关键词索引：与 `SimpleVectorStore` 同生命周期，进程内、重启重建。
- **一致性要求**：`KnowledgeBase.ingest()` 中「删旧块 → 写新块 → 落盘」的三步，
  关键词索引必须在**同一临界区内同步更新**；当前 `ingest` 已是 `synchronized`，需确认索引更新也在其中。
- 持久化：本期**不**持久化关键词索引，启动时从 5 份 Markdown 重建（数据量小，成本可忽略）。
  这避免了 state 文件从 2 个变 3 个带来的一致性风险。

## 7. Acceptance（必须可证伪）

1. `hybrid-enabled=false` 时，全部既有单元测试与集成评估结果与改动前**逐条一致**。
2. `hybrid-enabled=true` 时，评估集 E05–E08、E11、E13 六条精确数值题命中率 **≥ 5/6**（当前基线需先跑一次记录）。
3. 拒答用例 E18–E20 在两种开关下**全部保持拒答**（新增召回通道不得诱发幻觉）。
4. 单元测试 `HybridRetrieverTest`：同一 id 同时出现在两路时，结果中只出现一次且标记 `BOTH`。
5. 单元测试：关键词索引抛异常时，`retrieve()` 退化为纯向量结果且不抛异常。
6. `./mvnw test` 全绿，且**不产生任何真实 API 调用**（CI 用 dummy Key 可跑通）。

## 8. Done When

- [ ] 规格经人工确认（本文件状态改为「已确认」）
- [ ] `HybridRetriever` 实现 + 纯单元测试
- [ ] `KnowledgeBase` 同步更新索引，且删旧块逻辑经回归验证
- [ ] 灰度开关与降级路径可用
- [ ] 记录改动前基线 → 改动后跑集成评估 → 在 PR 里给出命中率对比
- [ ] `AGENTS.md` §8 回写新踩的坑
- [ ] `docs/04-已知问题与演进.md` 更新该项状态

## 9. Open Questions（Agent 不得自行假设，必须带问题停下来）

1. 允许引入 Lucene core 这类纯 Java 库吗？还是要求只用 JDK 自带能力实现一个简化 BM25？
2. `Citation.channel` 会改变对外 JSON，前端/调用方是否接受？若不接受，是否改为只在 DEBUG 日志里体现？
3. 中文分词如何处理？桩号、单位、数值这类 token 是否需要自定义分词规则？
4. 命中率提升的验收阈值定 5/6 合适吗，还是应该要求全部 6/6 且不降低其他用例？
5. 本期是否需要把 `hybrid-enabled` 接入 Actuator 做运行时动态切换，还是只允许重启生效？

---

## 示范要点（读完可删）

- **Context 先写耦合**：`Document.id` 被增量删除依赖，不写这句，实现时很可能为了「统一索引结构」换掉 id 格式，
  悄悄破坏入库幂等 —— 这是最典型的 plausible-but-wrong。
- **违规项主动上报**：加依赖违反项目约定，规格里抬到 S2 请求批准，而不是先斩后奏。
- **Open Questions 留白**：中文分词、对外契约、验收阈值这三类，Agent 不得自行脑补。
- **验收可证伪**：「提升检索效果」不合格，「六条精确数值题 ≥ 5/6 且拒答用例不退化」才合格。
- **降级与回滚**：灰度开关默认关闭 + 索引失败自动退化，是 AI 链路引入新组件时的基本纪律。
