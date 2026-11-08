# Java 后端 → AI 应用开发 · 概念映射速查

> 你的后端经验不是被稀释了，而是**更值钱了**。AI 应用开发真正难的部分
> （幂等、超时、降级、可观测、评估、安全）全是你已经会的东西。
> 这张表把陌生名词挂到熟悉概念上，看完再读代码会顺很多。

## 一、核心名词对照

| AI 名词 | 你熟悉的等价物 | 关键差异（面试就考这个） |
| --- | --- | --- |
| `ChatClient` | `RestClient` / `RestTemplate` | 返回值**不确定**，同样输入不保证同样输出 |
| `ChatModel` | 一个远程 HTTP 客户端 + 连接池 | 按 token 计费，有速率限制，延迟秒级 |
| `EmbeddingModel` | 把文本映射成定长 `float[]` 的纯函数 | 输出不可读、不可反向还原；**建库与查询必须同一个模型/维度** |
| `VectorStore` | 一种特殊索引的数据库 | 查的是「语义相近」不是「条件相等」；结果是**排序 Top-K**，没有精确匹配语义 |
| `Document` | 一行记录 + metadata | `text` 才是被向量化的内容，metadata 只用于过滤与溯源 |
| `Chunk` | 分库分表里的「一条记录粒度」 | 切太大混入噪声，切太小丢失语义 —— 没有通用最优值，只能靠评估集调 |
| `Prompt` / System Prompt | 接口契约 + 参数校验规则 | 用自然语言写的「契约」，**不具备强制力**，模型可能不遵守 |
| `temperature` | 一个全局配置项 | 不是「 creativity 开关」这么简单：客服/运维场景必须低，否则数值会飘 |
| `ChatMemory` | `HttpSession` / 会话缓存 | 内存实现同样有「重启丢失、多实例不共享」问题，生产要外置 |
| `Advisor` | `HandlerInterceptor` / Filter 链 | 在 LLM 调用前后改写请求，RAG 与记忆都是 Advisor 实现的 |
| `@Tool` | 一次内部 RPC / Feign 调用 | **谁来决定调不调**变成了 LLM，不是你的 if-else |
| 结构化输出 `entity()` | `ObjectMapper.readValue(json, DTO.class)` | 上游不保证返回合法 JSON，必须做降级与重试 |
| 评估集 | 集成测试用例 + 断言 | 不能写 `assertEquals`，只能算**命中率 / 通过率基线** |
| Token | 请求体大小 / 计费单位 | 上下文有硬上限，塞太多反而让准确率下降（注意力稀释） |
| Hallucination（幻觉） | 一个「不报错但返回脏数据」的下游 | 最危险的不是失败，是**看起来很合理且 HTTP 200** |
| RAG | 查询前先查一份本地资料再拼进请求 | 不消除幻觉，只是把「凭空编」变成「有据可查」 |
| Agent | 一个会自己决定调哪些服务的编排器 | 延迟、成本、确定性都变差，别什么都用 |

## 二、三段代码对照

### 1. 调用模型 ≈ 调用远程接口

```java
// 你熟悉的
String json = restClient.get().uri("/orders/{id}", id).retrieve().body(String.class);

// Spring AI
String answer = chatClient.prompt().user("隧道常态限速多少？").call().content();
```

差别只有一句：**前者可以 `assertEquals`，后者不行。**
所以后面所有工程动作（低 temperature、提示词约束、评估集、来源溯源）
本质上都是在给这个不确定的下游补上「可验证性」。

### 2. 向量检索 ≈ 带相似度的查询

```java
// 传统：条件精确匹配
List<Order> orders = orderMapper.selectByStatus("PAID");

// 向量：语义相近排序，返回 Top-K，永远有结果（哪怕全不相关）
List<Document> docs = store.similaritySearch(
        SearchRequest.builder().query("滤网多久换一次").topK(4).build());
```

**最关键的心智转变**：传统查询「查不到就是没有」，向量检索「查不到也会返回最像的 4 条」。
所以必须额外做两件事：相似度阈值过滤 + 提示词里明确允许模型说「查不到」。
少了任何一件，系统就会一本正经地用不相干的资料编答案。

### 3. 工具调用 ≈ 把 if-else 交给模型

```java
// 传统：路由逻辑写在你手里
if (question.contains("实时") || question.contains("现在")) {
    return deviceService.query(stake);
}
return knowledgeService.search(question);

// Agent：把「何时调」写进工具描述，由模型判断
@Tool(description = "当用户询问当前/实时状态，或需要依据实时数值判断处置级别时调用")
public String queryDeviceStatus(String stake) { ... }
```

于是**工具描述从注释变成了生产逻辑的一部分**。改实现忘改描述，
表现就是「模型该调时不调」——这类 bug 不看日志里的 tool_call 根本定位不到。

## 三、五个必须改掉的后端直觉

1. **「加个断言就稳了」** → LLM 输出不能断言，要改成命中率基线 + 拒答率，并保留失败明细。
2. **「失败会抛异常」** → 幻觉是 HTTP 200 + 一段通顺的错话，必须靠事实校验和来源引用来发现。
3. **「重试大概率能好」** → 同一提示词重试可能给出不同答案；对非确定性错误，重试前先想清楚要不要换提示词/降 temperature。
4. **「上下文越多越准」** → 塞满窗口会稀释注意力，反而更差。**上下文工程的重点是决定不放什么。**
5. **「日志越全越好」** → 提示词里可能有用户隐私/业务敏感数据，全量打日志等于数据外泄；要做脱敏与采样。

## 四、AI 应用里，后端能力具体用在哪

| 你已有的能力 | 在本项目的落点 | 面试说法 |
| --- | --- | --- |
| 幂等 / 增量 | `IngestState` 的 sha256 指纹、先删旧块再写新块 | 「把入库做成幂等 ETL，重复启动不产生重复向量」 |
| 事务一致性 | state 与 vector-store 两个文件同时落盘 | 「这里还有瑕疵：不是真事务，生产要换支持事务的向量库」 |
| 超时 / 降级 | `GlobalExceptionHandler`、`spring.ai.retry.*` | 「模型超时按下游故障处理，不能让堆栈直接返回前端」 |
| 可观测性 | Actuator + Micrometer、`/api/rag/search` 调试端点 | 「把召回结果单独暴露，排障时不消耗生成 token」 |
| 接口契约 | `IncidentReport` record + 结构化输出 | 「AI 输出必须落成类型，才能接校验、事务、审计」 |
| 安全 | `KeyGuard`、环境变量、gitleaks | 「把不许提交 Key 从口头约定变成机器强制」 |
| 测试分层 | 纯单元 / 集成评估默认跳过 / CI 用 dummy Key | 「CI 不依赖外部服务和账单」 |

---

**一句话收尾**：AI 应用开发不是「学会调模型」，而是
**把一个不确定、按量计费、会自信犯错的新下游，用你已经熟悉的工程手段管起来**。
