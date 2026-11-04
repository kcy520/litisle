> **归档说明**：新建板块文件（批次7，2026-11-02 归档）——迁自 docs/temp/temp.md「review 模块（DFA 引擎切换 hankcs ACDAT 批次）」节，正文原样搬运（含三批次完成登记与挂起项标注）。
> **前置脉络**：同步闸门引擎演进链 houbb → 自研 AcTextScanEngine → 本批次 hankcs ACDAT；自研 AC 引擎与词库治理记录见 [review-08-wordlib-dfa.md](review-08-wordlib-dfa.md)（按清单决策保留历史原貌，不追加）。

## 板块九：DFA 引擎切换 hankcs ACDAT（2026-11-01；批次7 于 2026-11-02 归档）

### review 模块（DFA 引擎切换 hankcs ACDAT 批次，2026-11-01 登记）

> 来源：DFA 引擎选型专项评审（2026-11-01）：由自研 AcTextScanEngine（朴素 trie + fail 指针 AC 自动机）切换为 hankcs AhoCorasickDoubleArrayTrie（双数组 trie 内核，严格 O(n)，官方基准英文 5 倍 / 中文 9 倍）。此前评审结论为「暂不切换、留作 SPI 预定升级路径」，本次为用户主动决策切换。
> 语义不变红线（全部保留，仅替换匹配内核）：变体展开三重封顶（MAX_EXTRA_REPEATS=2 / MAX_TOTAL_EXTRA_REPEATS=3 / MAX_VARIANTS_PER_WORD=128）· allow 区间「完全落入才剔除」· deny 最长优先去重 · hitWords 规范词回映 · 构建产物不可变 + 快照热更原子切换。
> 依赖坐标：com.hankcs:aho-corasick-double-array-trie:1.2.3（Maven Central 直引，零传递依赖）。
> 影响面：代码 6 文件（pom 配置 1 / 新增适配类 1 / TextScanEngine 工厂 1 行 / 测试改造 1 / 删除旧引擎 1 / CharNormalizer 注释顺手同步可选），宿主服务零波及（调用方仅依赖 DfaFacade，引擎被 TextScanEngine SPI 隔离）。
> 批次总览：**批次1 依赖与适配类（纯新增，零行为变更）→ 批次2 工厂接线 + 内核边界核对 + 影子比对 → 批次3 旧引擎收口与文档同步**；每批次以编译+启动验证收口。

#### 批次1：依赖与适配类（纯新增，零行为变更）✅ 已完成（2026-11-01）

- [x] litisle-api/pom.xml 新增 ACDAT 依赖（坐标见上）
- [x] 新增 `litisle-api/.../dfa/AcdatTextScanEngine.java`（预计 ~150–250 行，类/方法/关键逻辑均带注释）：
  - [x] build()：deny/allow 逐词经 CharNormalizer 归一化；deny 调 expandRepeatVariants 变体展开（三重封顶 + 同形去重逻辑自 AcTextScanEngine 平移）；pattern→规范词映射以 TreeMap 载荷喂 ACDAT（Hit 直接带回规范词）；allow 归一化后存独立集合
  - [x] scan()：文本侧无损宽化归一（沿用 CharNormalizer，文本永不折叠）→ parseText 取 Hit 区间 → allow 区间裁决 + deny 最长优先去重（裁决逻辑平移）→ 返回规范词去重列表（未命中空列表，绝不 null）
  - [x] 构建产物不可变（build/scan 严格分离），维持 WordlibSnapshot 快照热更语义（DfaFacade 原子换引用零改动）
- [x] 编译验证（litisle-api 模块 + 依赖方编译：根聚合 compile + litisle-api -am test-compile 均 exit 0；批次1 纯新增未接线，宿主启动行为零变化，启动验证随批次2 接线后收口）

#### 批次2：工厂接线 + 内核边界核对 + 影子比对 ✅ 已完成（2026-11-01）

- [x] 内核边界三核对（接线前完成，任一不对齐都会悄悄引入漏审/误报）——以运行时用例双引擎断言完成核对，全部通过：
  - [x] ① 区间端点口径：hankcs Hit 的 begin/end 与旧引擎 Hit(start,end) 开闭约定对齐（影响 allow「完全落入」几何判定）——allow/deny 区间裁决仅做 begin/end 相对比较，begin 含端语义一致，end 开闭差异在「完全落入」与「最长优先」判定下数学等价（平移不变）；用例：kernelBoundaryAllowIntervalEndpoints（右等界剔除 + 首尾相接不误剔）双引擎通过
  - [x] ② 重叠命中完整性：确认 ACDAT 返回全部嵌套/重叠命中（未启用 onlyWholeWords 类过滤）——用例：kernelBoundaryOverlapCompleteness（AB/B 只报 AB；AB/BC 部分重叠都报；同词多处去重）双引擎通过
  - [x] ③ char 位置 vs 码点：hankcs parseText 内部逐 charAt 与旧引擎 charAt 同口径，代理对占 2 char 不合并——用例：kernelBoundarySurrogatePairs（emoji 打断不命中/邻接命中/词中插 emoji 不命中）双引擎通过
- [x] TextScanEngine.build() 工厂方法改 1 行指向 AcdatTextScanEngine.build
- [x] AcTextScanEngineTest.java 改造为面向 TextScanEngine SPI 的契约测试（既有 103 行用例复用，接线后自动跑新内核；8→12 用例：补内核边界①②③ + 同形 deny 优先用例，双引擎断言守护）+ ShadowComparisonTest 影子比对测试类新增
- [x] 影子比对：双引擎并行扫同一批样本，命中集合逐条相等（固定样本批 24 组覆盖命中/变体/allow 过滤/嵌套重叠/全角繁体数字归一/emoji/同形 deny 优先/空输入 + 固定 seed=42 随机批 500 组词表字符拼文）；差异归零（0 条不一致），符合进入批次3 条件
- [x] 编译 + 启动验证：litisle-api 测试 15/15 全过 + 根聚合 compile exit 0（全部依赖方模块零波及）；⚠️ 宿主启动复验待中间件可用（本机 Nacos 8848/Redis 6379/MySQL 3306 均不可达，与 review-08 归档先例同口径，批次3 四宿主启动复验一并收口）

#### 批次3：旧引擎收口与文档同步 ✅ 已完成（2026-11-01）

- [x] 删除 AcTextScanEngine.java（483 行；影子比对通过后执行，**删除前需用户明确同意**——用户已确认执行批次3；同批删除批次2 过渡性影子比对测试 ShadowComparisonTest.java，经用户确认删除）
- [x] 确认 expandRepeatVariants 等平移逻辑已由 AcdatTextScanEngine 自持，dfa 包无跨类残留引用（grep 全库 *.java 仅剩 2 处合理历史表述：AcTextScanEngineTest 类名沿用经用户确认 + TextScanEngine.java 删除说明注释；无悬空 {@link} 引用，CharNormalizer/DfaFacade/AcdatTextScanEngine 类注释已同步新内核表述）
- [x] 文档同步：
  - [x] Review-Architecture.md：§1.1 技术选型（AcTextScanEngine → hankcs ACDAT 内核表述，含 houbb→自研 AC→hankcs ACDAT 演进链）+ §1.1 三类能力同步闸门行；§1.2 代码结构 dfa 节核对（review 模块 dfa 包为词库供给侧不含引擎类，无需改动）；批次3 ⑤b 相关历史表述核对（历史事实保留，仅更新引擎指向）
  - [x] Review-Business-Guide.md：§一标题/功能描述（零第三方依赖→内核零传递依赖）/引擎与 SPI 段/代码部分/热更链路重建表述共 5 处同步
  - [x] review-08-wordlib-dfa.md（归档）：不动，保留历史原貌
- [ ] 四宿主（content/file/social/review）启动复验：DFA 冷启动装载 + 词库热更（Pub 广播重建/原子切换）链路正常 ⚠️ **待中间件可用后执行**（2026-11-01 探测 Nacos 8848/Redis 6379/MySQL 3306 均不可达；批次1/2/3 三次探测结论一致，与 review-08 归档先例同口径；代码层验证已由 15/15→13/13 测试 + 根聚合编译覆盖）
- [x] 编译 + 启动验证收口（litisle-api 测试 13/13 全过：AcTextScanEngineTest 12 用例单引擎契约 + LitisleApiApplicationTests 1；根聚合 compile exit 0 全依赖方零波及；启动复验见上条 ⚠️ 待中间件）；全部勾选后按临时清单纪律经确认归档/删除——本批次尚余四宿主启动复验挂起项，**temp.md 暂不归档/删除，待启动复验完成后一并处理**
