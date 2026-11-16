# 临时任务清单汇总

## 目录及文档说明

- 目录及文档说明
- 归档记录（已完成批次索引）
- 待办部分

### 文档说明:

- 本文档为临时任务清单（区别于 `docs/next/next_update.md` 正式待办），收纳临时立项、批次化改造类任务，与正式待办并行使用
- 临时清单为独立闭环：任务内容与落地摘要直接记录在本清单条目内，**不引用 status.md 细案**
- **不拆分「代码部分/验证部分」**：模块直接挂在「待办部分」下，代码任务与验证任务（V{n}）统一按 模块 → 批次/任务 组织
- 每阶段/批次完成后勾选 \[x] 并补 ✅ 落地摘要；清单全部完成后按「归档与迁移」规则处理

### 文档模版

\*\* temp.md 模版: \*\*

```
# {清单主题}（临时任务清单）

> 方案：{关键方案决策，如：方案A（xx）+ xx 校验}
> 原则：{推进原则，如：xx 链路先行，xx 待确认后跟进；每阶段完成后勾选 [x]}

## 目录及文档说明

- 目录及文档说明
- 待办部分

### 文档说明:

- 临时清单不引用 status.md，任务细案直接写在条目内
- 不拆分代码/验证部分：验证项（V{n}）直接挂在对应模块下，与批次任务并列

--------------------------

## 待办部分

#### {模块名}模块

> 批次划分依据：{排查/设计文档来源}；推进顺序：批次一 → 二 → ...（有依赖的批次注明前置）

##### 批次{n}：{批次名称}

###### {批次n}.{m} {任务短标题}

- [ ] {任务内容一句话 + 关键口径/铁律 + 涉及的表/事件/常量}
  - ✅ {yyyy-MM-dd}：{落地摘要：核心类/方法 + 关键分支决策}；跨批次遗留：{遗留项（所属批次）}；编译验证已通过（{yyyy-MM-dd}）

------------

（每个批次之间用 ------------ 分隔线隔开）

##### V{n} {验证主题}

- [ ] {验证内容}（依赖{前置批次/模块}）
```

### 归档与迁移（批次完成后的处理规则）

**相关文档职责**：

| 文档                       | 职责                                                                                                                   |
| ------------------------ | -------------------------------------------------------------------------------------------------------------------- |
| `docs/temp/temp.md`（本文档） | 仅收纳临时立项的活跃任务，按 模块 → 批次/任务（含验证项 V{n}）组织                                                                               |
| `docs/archive/`          | 已完成批次的实现记录归档：按模块分子目录追加对应板块文件（新模块新建归档子目录 + 同名索引页），如 content-09-temp-checklist.md                                      |
| `docs/next/temporarily/` | 挂起/预留项转移：代码类挂起进 temporarily-01-code.md，验证类挂起进 temporarily-02-verify.md，预留/暂不实现进 temporarily-04-deferred.md，每条必须附触发条件 |

**批次完成后的迁移动作（按顺序执行）**：

1. **实现记录归档**：将已完成批次的条目（含口径修正、注意事项）原样搬运至 `docs/archive/` 对应模块板块文件，不压缩关键细节。
2. **本文档回填**：已完成批次从本文档「待办部分」删除，并在顶部「归档记录」补一行批次索引（日期 + 归档去向 + 挂起项去向），保持本文档只留活跃任务。
3. **挂起项转移**：无法落地的事项移入 `docs/next/temporarily/` 对应文件并写明触发条件，供宿主就绪时迁回。
4. **预留项登记**：明确「暂不实现/暂不做」的事项（含触发条件与决策原因），移入 temporarily-04-deferred.md。

> 纪律：迁移时条目内容原样搬运；归档文档与本文档/temporarily 清单之间用相对路径互链，保证可回溯。

### 归档记录（已完成批次索引）

- 2026-11-05：search 模块「搜索链路企业级优化」批次一~三（1.1~1.3、2.1、3.1）→ [search-11-temp-checklist.md](../archive/search/search-11-temp-checklist.md)；挂起项 1.4、2.2 → [temporarily-04-deferred.md](../next/temporarily/temporarily-04-deferred.md)；search 重组批次一~八定版记录 → [next_update.md](../next/next_update.md) search模块（2026-11-03）
- 2026-11-05：content 模块「阅读历史 bizType 枚举化」实施记录 → [content-09-temp-checklist.md](../archive/content/content-09-temp-checklist.md) §E.5
- 2026-11-15：本清单收口——① search「索引同步并发竞态决策记录（2026-11-05）」原样归档 → [search-11-temp-checklist.md](../archive/search/search-11-temp-checklist.md) 板块十二（P2 条件项与 updateTime 待拍板项此前已录 temporarily-04-deferred.md 9.8 / 9.9-E）；② content「阅读历史 bizType 枚举化方案与配套检查表（参考件）」原样归档 → [content-09-temp-checklist.md](../archive/content/content-09-temp-checklist.md) §E.5 附；③ 未完成的 V1 联想链路优化验证（运行态联调）迁出 → [temporarily-02-verify.md](../next/temporarily/temporarily-02-verify.md) search 模块小节。待办部分已无活跃任务。
- 2026-11-16：两个 Agent 立项（条件化相似文章推荐 + Agent 交互扩展）**整体转正式待办**——代码条目按板块拆入 [next_update.md](../next/next_update.md)（AI 板块八~十四 / search 板块二~四，任务编号沿用原批次编号不重排，跨立项引用改为板块号）；验证项 V1~V5 迁入 [next_verify.md](../next/next_verify.md)（AI-V4~V8，编号映射留注）；暂不做清单（has_image / OR 组合 / 关注列表 filter / 已读去重 / 用户画像 / stance/audience 词表 / suggestions 字段 / 订阅提醒）迁入 [temporarily-04-deferred.md](../next/temporarily/temporarily-04-deferred.md) ai 模块节。待办部分已无活跃任务。

***

***

## 待办部分

> 待办部分已无活跃任务（2026-11-16 收口）：两个 Agent 立项已整体转正式待办，去向见顶部「归档记录」末行。
