
## 二、litisle-content 模块（数据库：litisle_content）

# （草稿上的图片也能暂存在数据库中）

# 发布时可以选择文章是否公开，可以在之后几章同时发布，或者先暂时存着（后续可以多选发布-即修改公开状态）
# 作者写好了一批内容（几篇文章、几个章节），暂时不对外公开，等攒够了/准备好了，一次性把它们一起发布出去。

# ============================================================
# 模块：litisle-content
# 数据库：litisle_content
# 说明：文章 / 草稿 / 书籍 / 章节 / 图片 / 阅读历史 / 收藏
# ============================================================

create database if not exists litisle_content
character set utf8mb4
collate utf8mb4_unicode_ci;

use litisle_content;

# 1. 文章主表
create table if not exists `article`
(
    `id`            bigint unsigned not null auto_increment primary key comment '文章ID',
    `author_id`     bigint unsigned not null comment '作者用户ID',
    `zone`          tinyint         not null default 1 comment '分区：1-普通分区 2-作家分区（推荐流为 zone 1/2 聚合视图，非分区）',
    `title`         varchar(100)    not null comment '标题',
    `summary`       varchar(500)    null comment '摘要',
    `content_html`  longtext        not null comment '正文HTML',
    `cover_file_id`  varchar(64)    null comment '封面文件ID（SeaweedFS）',
    `category`      varchar(20)     not null default '散文' comment '分类：小说/散文/现代诗/随笔等',
    `status`        tinyint         not null default 0 comment '状态：0-草稿 1-待审核 2-已发布 3-驳回 4-下架',
    `visibility`    tinyint         not null default 1 comment '可见性：1-公开 2-仅自己可见 3-仅关注者可见（阶段9：原 4-密码访问已下线，存量 4 迁移为 1）',
    `publish_batch_id` bigint unsigned null comment '批量发布批次ID（同批次发布的多篇文章/章节共享，便于按批次展示与批量操作）',
    `view_cnt`      bigint unsigned not null default 0 comment '浏览量',
    `like_cnt`      bigint unsigned not null default 0 comment '点赞数',
    `comment_cnt`   bigint unsigned not null default 0 comment '评论数',
    `content_hash`  char(64)        null comment '内容指纹SHA256（文本=标题+正文，对齐 review §2.1；配图聚合指纹属 ARTICLE_IMG_RE_REVIEW 场景不并入本列；免审直落/转公开不重审校验键）',
    `scheduled_publish_time` datetime null comment '定时发布时间（项2 2026-11-02）：仅草稿态（status=0）允许设置，到点由 ArticleScheduledPublishJob 扫描走批量送审链路自动发布；置 NULL 即取消定时',
    `del_flag`      tinyint         not null default 0 comment '删除标志：0-未删除 1-已删除',
    `version`       int             not null default 0 comment '乐观锁版本号',
    `create_time`   datetime        not null default current_timestamp comment '创建时间',
    `update_time`   datetime        not null default current_timestamp on update current_timestamp comment '更新时间（业务编辑/状态流转刷新；浏览量刷回显式保留原值，防老文章被顶到「最新列表」顶部）',
    `stat_update_time` datetime      not null default current_timestamp comment '热度信号统计时间（仅浏览/点赞/评论计数回写刷新；热度榜增量灌榜按此列捞变更，与 update_time「最新列表」语义解耦）',
    index `idx_author_id` (`author_id`),
    index `idx_zone_status` (`zone`, `status`),
    index `idx_visibility` (`visibility`),
    index `idx_publish_batch` (`publish_batch_id`),
    index `idx_create_time` (`create_time`),
    index `idx_stat_update_time` (`stat_update_time`),
    index `idx_feed_zone` (`del_flag`, `status`, `visibility`, `zone`, `create_time`),
    index `idx_feed_category` (`del_flag`, `status`, `visibility`, `category`, `create_time`)
    ) engine = InnoDB
    default charset = utf8mb4 comment = '文章表';

# 2. 文章图片表（记录插入位置与对齐方式）
create table if not exists `article_image`
(
    `id`              bigint unsigned not null auto_increment primary key comment '主键ID',
    `article_id`      bigint unsigned null comment '文章ID（草稿期可空——新草稿插图仅 draft_id 归属，发布归属切换时回填；与 draft_id 二选一）',
    `draft_id`        bigint unsigned null comment '草稿ID（与 article_id 二选一，发布后清空）',
    `file_id`         varchar(64)     not null comment 'SeaweedFS 文件ID',
    `position_order`  int             not null comment '图片在正文中的顺序号',
    `align`           tinyint         not null default 2 comment '对齐：1-左 2-左偏中 3-中 4-中偏右 5-右',
    `width`           int             null comment '宽度（px）',
    `height`          int             null comment '高度（px）',
    `create_time`     datetime        not null default current_timestamp comment '创建时间',
    index `idx_article_id` (`article_id`),
    index `idx_draft_id` (`draft_id`),
    index `idx_file_id` (`file_id`)
    ) engine = InnoDB
    default charset = utf8mb4 comment = '文章图片表（idx_file_id 支撑图片复审再聚合：图片人审/图片机审结果落库/申诉改判后按 file_id 反查引用该图的所有文章，再定位其 ARTICLE_IMG_RE_REVIEW 复审任务重算聚合结论，防挂起任务永久卡待审，见 Review-Plan §4.2.2④）';



# 3. 草稿自动保存表（多次编辑历史）
create table if not exists `article_draft`
(
    `id`              bigint unsigned not null auto_increment primary key comment '草稿ID',
    `article_id`      bigint unsigned null comment '关联文章ID（新草稿为 null，发布后回填）',
    `author_id`       bigint unsigned not null comment '作者用户ID',
    `title`           varchar(100)    not null comment '标题',
    `summary`         varchar(500)    null comment '摘要',
    `content_html`    longtext        not null comment '正文HTML',
    `cover_file_id`   varchar(64)     null comment '封面文件ID（SeaweedFS）',
    `category`        varchar(20)     not null default '散文' comment '分类',
    `visibility`      tinyint         not null default 1 comment '可见性（与 article 一致，发布时继承）',
    `draft_version`   int             not null default 1 comment '草稿版本号（乐观锁计数器：内容变更+1，多端并发比对 baseVersion）',
    `create_time`     datetime        not null default current_timestamp comment '创建时间',
    `update_time`     datetime        not null default current_timestamp on update current_timestamp comment '更新时间',
    index `idx_article_id` (`article_id`),
    index `idx_author_id` (`author_id`),
    index `idx_create_time` (`create_time`)
    ) engine = InnoDB
    default charset = utf8mb4 comment = '草稿自动保存表（单行 UPSERT：每草稿仅一行、只存最新内容；发布后 article_id 回填）';

# 4. 书籍/合集表
create table if not exists `book`
(
    `id`           bigint unsigned not null auto_increment primary key comment '书籍ID',
    `author_id`    bigint unsigned not null comment '作者ID',
    `title`        varchar(100)    not null comment '书名',
    `intro`        varchar(500)    null comment '简介',
    `cover_file_id` varchar(64)    null comment '封面文件ID',
    `category`     varchar(20)     not null default '小说' comment '分类',
    `status`       tinyint         not null default 0 comment '状态：0-连载中 1-已完结 2-下架（默认连载中）',
    `del_flag`     tinyint         not null default 0 comment '删除标志',
    `version`      int             not null default 0 comment '乐观锁版本号',
    `create_time`  datetime        not null default current_timestamp comment '创建时间',
    `update_time`  datetime        not null default current_timestamp on update current_timestamp comment '更新时间',
    index `idx_author_id` (`author_id`),
    index `idx_feed` (`del_flag`, `status`, `category`, `create_time`)
    ) engine = InnoDB
    default charset = utf8mb4 comment = '书籍/合集表';

# 5. 章节表（文章作为章节挂到书籍）
create table if not exists `book_chapter`
(
    `id`         bigint unsigned not null auto_increment primary key comment '章节ID',
    `book_id`    bigint unsigned not null comment '书籍ID',
    `article_id` bigint unsigned not null comment '文章ID',
    `chapter_no` int             not null comment '章节序号',
    `create_time` datetime       not null default current_timestamp comment '创建时间',
    unique key `uk_book_chapter_no` (`book_id`, `chapter_no`),
    index `idx_book_id` (`book_id`),
    index `idx_article_id` (`article_id`)
    ) engine = InnoDB
    default charset = utf8mb4 comment = '书籍章节表';


# 6. 阅读历史表（单表承载书籍+独立文章两类阅读历史，每人每类型每对象一行=上次阅读记录）
create table if not exists `reading_history`
(
    `id`         bigint unsigned not null auto_increment primary key comment '主键ID',
    `user_id`    bigint unsigned not null comment '读者ID',
    `biz_type`   tinyint         not null default 1 comment '阅读对象类型：1-书籍 2-独立文章',
    `biz_id`     bigint unsigned not null comment '阅读对象ID（biz_type=1→book.id，biz_type=2→article.id）',
    `chapter_id` bigint unsigned null comment '最后阅读章节ID（仅 biz_type=1 有值，指向 book_chapter.id；即上次阅读记录，update_time 即最后阅读时间）',
    `duration_ms` bigint unsigned not null default 0 comment '累计阅读时长(毫秒，预留字段，批次十一 11.11 本次不写入)',
    `update_time` datetime       not null default current_timestamp on update current_timestamp comment '更新时间',
    unique key `uk_user_biz` (`user_id`, `biz_type`, `biz_id`),
    index `idx_user_time` (`user_id`, `biz_type`, `update_time`),
    index `idx_user_time_all` (`user_id`, `update_time`),
    index `idx_biz` (`biz_type`, `biz_id`, `update_time`)
    ) engine = InnoDB
    default charset = utf8mb4 comment = '阅读历史表（单表承载书籍+独立文章两类阅读历史，每人每类型每对象一行=上次阅读记录；idx_user_time 支撑书籍/文章单类型 tab 倒序分页，idx_user_time_all 支撑「全部」混合时间线倒序分页）';

# 7. 标签表（全局池，受控新建）
create table if not exists `tag`
(
    `id`          bigint unsigned not null auto_increment primary key comment '标签ID',
    `name`        varchar(20)     not null comment '标签名（≤20字符，新建过 DFA 闸门）',
    `use_count`   bigint unsigned not null default 0 comment '引用文章数（冗余计数，异步刷新，支撑热门 TOP N）',
    `status`      tinyint         not null default 0 comment '状态：0-正常 1-封禁（违规标签运营处置，封禁后列表侧不再聚合展示）',
    `del_flag`    tinyint         not null default 0 comment '删除标志：0-未删除 1-已删除',
    `create_time` datetime        not null default current_timestamp comment '创建时间',
    unique key `uk_name` (`name`),
    index `idx_use_count` (`use_count`)
    ) engine = InnoDB
    default charset = utf8mb4 comment = '标签表';

# 8. 文章-标签关联表
create table if not exists `article_tag`
(
    `id`          bigint unsigned not null auto_increment primary key comment '主键ID',
    `article_id`  bigint unsigned not null comment '文章ID',
    `tag_id`      bigint unsigned not null comment '标签ID',
    `create_time` datetime        not null default current_timestamp comment '创建时间',
    unique key `uk_article_tag` (`article_id`, `tag_id`),
    index `idx_tag_id` (`tag_id`)
    ) engine = InnoDB
    default charset = utf8mb4 comment = '文章标签关联表（冲突幂等跳过；不参与推荐流排序权重，仅聚合列表页）';

# 9. 收藏表（一期 content 临时承载，单层收藏；social 收藏夹后续迁移）
create table if not exists `favorite`
(
    `id`          bigint unsigned not null auto_increment primary key comment '主键ID',
    `user_id`     bigint unsigned not null comment '收藏用户ID',
    `target_type` tinyint         not null comment '收藏目标类型：1-文章 2-书籍',
    `target_id`   bigint unsigned not null comment '收藏目标ID（target_type=1→article.id，target_type=2→book.id）',
    `create_time` datetime        not null default current_timestamp comment '创建时间',
    unique key `uk_user_target` (`user_id`, `target_type`, `target_id`),
    index `idx_target` (`target_type`, `target_id`),
    index `idx_user_time` (`user_id`, `create_time`)
    ) engine = InnoDB
    default charset = utf8mb4 comment = '收藏表（一期 content 临时承载，单层收藏无分组；social 收藏夹立项后迁移 favorite_item 或另定合并方案）';

# 10. 事件发件箱表（批次3 2026-11-02：MQ 事件必达兜底，本地 outbox 落表）
create table if not exists `event_outbox`
(
    `id`           bigint unsigned not null auto_increment primary key comment '主键ID',
    `event_id`     varchar(64)     not null comment '事件唯一ID（UUID，与事件体 eventId 同值，消费端幂等去重锚点）',
    `event_type`   varchar(64)     not null comment '事件类型（事件体类简名，如 ContentPublishedEvent，排查定位用）',
    `payload_type` varchar(128)    not null comment '事件体 Java 类全名（重发时 JSON 反序列化依据）',
    `exchange`     varchar(64)     not null comment '目标交换机（MqConstant.EXCHANGE_*）',
    `routing_key`  varchar(128)    not null comment '路由键（MqConstant.ROUTING_*）',
    `payload`      text            not null comment '事件体 JSON 快照（事件体均为小对象：submitted 不含正文，published/unpublished 仅指纹与业务ID）',
    `status`       tinyint         not null default 0 comment '状态：0-待发送 1-已发送 2-发送失败 3-重试超限放弃（死信留痕人工介入）',
    `retry_count`  int             not null default 0 comment '重发次数（重发 job 累计，达上限置 3 放弃）',
    `create_time`  datetime        not null default current_timestamp comment '创建时间（生产者发送前落表时刻）',
    `update_time`  datetime        not null default current_timestamp on update current_timestamp comment '更新时间',
    `send_time`    datetime        null comment '成功发送时间（status=1 时有值）',
    index `idx_status_create` (`status`, `create_time`),
    index `idx_sent_update` (`status`, `update_time`)
    ) engine = InnoDB
    default charset = utf8mb4 comment = '事件发件箱表（本地 outbox：事件发送前先落表 status=0，发送成功置 1、失败置 2 由重发 job 兜底重发；防「发送前崩溃/发送失败」两类事件丢失——search 索引/ai RAG 依赖 content.published/unpublished 必达；idx_status_create 支撑重发扫描，idx_sent_update 支撑已发送行过期清理）';

# 设计原因：
# article 计数字段冗余（view_cnt/like_cnt）：避免每次列表查询都 join 聚合，配合 Redis 异步刷新。
# article_image 独立表：设计文档要求图片在任意位置插入 + 5 种对齐，落表后支持"图片管理"与重新排版。
# article_draft 与 article 分离：草稿是高频写、低频读，独立表避免污染主表查询缓存。article_draft 单行 UPSERT（每草稿仅一行、只存最新内容，历史版本不留存），draft_version 作多端并发编辑的乐观锁计数器；发布后 article_id 回填，关联到正式文章。
# publish_batch_id：批量发布时同一批次文章共享一个 batch_id，便于前端"按批次展示"和"批量操作"。
# scheduled_publish_time：定时发布落内容表（1:1 发布属性，参照 WordPress post_date），到点 job 扫描草稿态走既有送审链路；不加索引（草稿量小，status 过滤足够）。
# book_chapter 用 (book_id, chapter_no) 唯一索引：保证章节序号不重复，序号即排序键。
# content_html 用 longtext：支持大篇幅切片前的完整正文。
# tag + article_tag 独立多对多：标签是开放词汇关键词（区别于 category 单选分类），独立池支撑改名/封禁/热门统计；每篇上限 5 个防堆砌，新建过 DFA，与正文方案B（禁编已发布正文）解耦——标签不影响 content_hash。
# favorite 单层收藏（一期 content 临时承载）：uk_user_target 防重复收藏，idx_target 支撑按目标反查，idx_user_time 支撑我的收藏倒序分页；social 收藏夹立项后迁移 favorite_item 或另定合并方案。
# event_outbox 本地事件发件箱（批次3 2026-11-02）：ContentEventProducer 发送前落表（status=0），发送成功置 1、失败置 2，EventOutboxResendJob 定时重发（retry_count 达上限置 3 放弃留痕）；已发送行按保留天数（默认 7 天）定时清理；「发送前崩溃」「发送失败」两类事件丢失兜底，消费端 eventId 幂等去重保证重发无副作用。

# ============================================================
# 批次十一补充表（原 zz- 增量脚本内容，2026-11-06 合并回本文件，
# 使本文件即等于最终结构；新建库一次到位，无需再跑补丁脚本）
# ============================================================

# 11. 文章互动统计表（原 zz-content-stat.sql 建表段）
# 口径：文章与统计行一对一，主键即 article.id；推荐流/热度榜统一读本表计数。
create table if not exists `article_stat`
(
    `article_id`       bigint unsigned not null comment '文章ID（article.id，一对一）',
    `collect_count`    bigint unsigned not null default 0 comment '收藏数（favorite target_type=1 同步增量，递减带下限保护；排除作者自收藏）',
    `like_count`       bigint unsigned not null default 0 comment '点赞数（11.8 点赞接口上线后同步增量；此前为 0）',
    `comment_count`    bigint unsigned not null default 0 comment '评论数（social 评论回写；评论模块未落地前为 0）',
    `view_count`       bigint unsigned not null default 0 comment '浏览量（ViewCountFlushJob 落库后同步绝对值，只增不减）',
    `stat_update_time` datetime        not null default current_timestamp on update current_timestamp comment '统计最近更新时间（仅计数变更刷新）',
    primary key (`article_id`)
) engine = InnoDB default charset = utf8mb4 comment = '文章互动统计表（批次十一 11.1：推荐流/热度榜统一计数读源）';

# 12. 书籍互动统计表（原 zz-content-stat.sql 建表段 + zz-user-like.sql 补 like_count 列）
# 口径：书籍与统计行一对一，主键即 book.id；chapter_count 只统计「未删+已发布+公开」章节。
create table if not exists `book_stat`
(
    `book_id`          bigint unsigned not null comment '书籍ID（book.id，一对一）',
    `collect_count`    bigint unsigned not null default 0 comment '收藏数（favorite target_type=2 同步增量，递减带下限保护；排除作者自收藏）',
    `reader_count`     bigint unsigned not null default 0 comment '阅读人数（首次阅读 +1，只增不减，与 reading_history 裁剪解耦）',
    `chapter_count`    bigint unsigned not null default 0 comment '章节数（只统计未删+已发布+公开章节，book_chapter 聚合同步；11.2 切读后用于书籍卡片）',
    `like_count`       bigint unsigned not null default 0 comment '点赞数（批次十一 11.8：书籍点赞同步增量，递减带下限保护）',
    `view_count`       bigint unsigned not null default 0 comment '浏览量（书籍浏览量暂无采集链路，保留字段，当前为 0）',
    `stat_update_time` datetime        not null default current_timestamp on update current_timestamp comment '统计最近更新时间（仅计数变更刷新）',
    primary key (`book_id`)
) engine = InnoDB default charset = utf8mb4 comment = '书籍互动统计表（批次十一 11.1：书籍流排序与列表展示统一计数读源）';

# 13. 内容互动统计窗口表（原 zz-content-stat-window.sql）
# 口径：同一目标按不同窗口天数各存一行（默认 30/90），翻页只读不聚合。
create table if not exists `content_stat_window`
(
    `id`               bigint unsigned not null auto_increment comment '自增主键（仅作物理主键，业务唯一键见 uk_target_window）',
    `target_type`      tinyint unsigned not null comment '目标类型：1-文章 2-书籍（对齐 favorite.target_type）',
    `target_id`        bigint unsigned not null comment '目标ID（文章ID或书籍ID）',
    `window_days`      smallint unsigned not null comment '统计窗口天数（默认 30/90，由 litisle.content.stat.window 配置）',
    `collect_count`    bigint unsigned not null default 0 comment '窗口内收藏数（favorite 按 create_time 落在窗口内计数）',
    `read_count`       bigint unsigned not null default 0 comment '窗口内阅读人数（reading_history 按 update_time 落在窗口内去重用户数）',
    `stat_update_time` datetime            not null default current_timestamp on update current_timestamp comment '统计最近更新时间（每日重算刷新）',
    primary key (`id`),
    unique key `uk_target_window` (`target_type`, `target_id`, `window_days`),
    key `idx_window_lookup` (`window_days`, `target_type`, `target_id`)
) engine = InnoDB default charset = utf8mb4 comment = '内容互动统计窗口表（批次十一 11.3：收藏/阅读 30/90 天窗口预计算，翻页只读不聚合）';

# 14. 书籍粗完读率代理统计表（原 zz-book-read-quality.sql）
# 口径：reading_history.chapter_id 反查 book_chapter.chapter_no，与该书最大可见章节号比较。
create table if not exists `book_read_quality`
(
    `book_id`               bigint unsigned not null comment '书籍ID（与 book.id 一对一）',
    `finish_reader_count`   bigint unsigned not null default 0 comment '追到最大可见章节号的读者人数（分子）',
    `eligible_reader_count` bigint unsigned not null default 0 comment '有可见章节阅读历史的读者人数（分母，与分子同源计算）',
    `stat_update_time`      datetime not null default current_timestamp on update current_timestamp comment '本轮全量重算时间',
    primary key (`book_id`),
    key `idx_stat_update_time` (`stat_update_time`)
) engine = InnoDB default charset = utf8mb4 collate = utf8mb4_unicode_ci comment = '书籍粗完读率代理统计（11.13，只读统计，不参与排序）';

# 15. 用户点赞表（原 zz-user-like.sql）
# 口径：一行为一次有效点赞；取消点赞物理删除，delete 影响行数 > 0 才递减统计。
create table if not exists `user_like`
(
    `id`           bigint unsigned not null auto_increment comment '主键ID',
    `user_id`      bigint unsigned not null comment '点赞用户ID',
    `target_type`  tinyint         not null comment '目标类型（1-文章 2-书籍，口径与 favorite.target_type 一致）',
    `target_id`    bigint unsigned not null comment '目标ID（target_type=1 时为 article.id，=2 时为 book.id）',
    `create_time`  datetime        not null default current_timestamp comment '点赞时间（取消点赞即物理删除本行）',
    primary key (`id`),
    unique key `uk_user_target` (`user_id`, `target_type`, `target_id`) comment '同一用户对同一目标只允许一条点赞记录（重复点赞幂等依据）',
    key `idx_target` (`target_type`, `target_id`) comment '按目标聚合点赞数/排查单目标点赞明细'
) engine = InnoDB default charset = utf8mb4 comment = '用户点赞表（批次十一 11.8：content 临时承载点赞，取消点赞物理删除）';

# 16. 推荐流曝光/点击日聚合表（原 zz-feed-exposure.sql）
# 口径：同一身份同一天同一条内容曝光最多 +1、点击最多 +1（Redis 当日 Set 去重）；
# identity_type 区分 login/anon，登录 CTR 只看 login 行。
create table if not exists `feed_exposure_daily`
(
    `id`             bigint unsigned not null auto_increment comment '自增主键（仅物理主键，业务唯一性由 uk_feed_exposure_daily 保证）',
    `stat_date`      DATE            not null comment '统计日期（按天聚合，唯一键最左列，也是清理条件列）',
    `stream`         VARCHAR(16)     not null comment '流类型：article=文章流 / book=书籍流（取 FeedConstant.STREAM_*）',
    `target_id`      BIGINT UNSIGNED not null comment '内容 ID（文章 ID 或书籍 ID，按 stream 区分命名空间）',
    `identity_type`  VARCHAR(16)     not null comment '身份口径：login=登录用户 / anon=匿名（X-Anon-Id），取 FeedConstant.IDENTITY_TYPE_*',
    `exposure_count` INT UNSIGNED    not null default 0 comment '当日曝光人数（同身份同日同条最多 +1，由 Redis 曝光 Set 去重）',
    `click_count`    INT UNSIGNED    not null default 0 comment '当日点击人数（同身份同日同条最多 +1，由 Redis 点击 Set 去重）',
    `update_time`    DATETIME        not null default CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP comment '最后写入时间',
    primary key (`id`),
    unique key `uk_feed_exposure_daily` (`stat_date`, `stream`, `target_id`, `identity_type`)
) engine = InnoDB default charset = utf8mb4 collate = utf8mb4_unicode_ci comment ='推荐流曝光/点击日聚合（批次十一 11.6；保留 30 天，配置化）';

# 17. 推荐流负反馈表（原 zz-feed-feedback.sql）
# 口径：feedback_type 1-不感兴趣(单条) 2-屏蔽作者 3-屏蔽分类；撤销即删除行；30 天窗口内生效。
create table if not exists `feed_feedback`
(
    `id`            bigint unsigned not null auto_increment comment '主键',
    `user_id`       bigint unsigned not null comment '提交反馈的用户ID（必须登录）',
    `feedback_type` tinyint not null comment '1-不感兴趣(单条内容) 2-屏蔽作者 3-屏蔽分类',
    `stream`        varchar(16) not null default '' comment '内容流：article/book（内容ID与作者ID按流区分）',
    `target_id`     bigint unsigned not null default 0 comment '内容ID或作者ID；分类类型固定为0',
    `target_key`    varchar(64) not null default '' comment '分类名；内容/作者类型固定为空串',
    `create_time`   datetime not null default current_timestamp comment '提交时间（屏蔽时间窗按该列过滤）',
    primary key (`id`),
    unique key `uk_user_type_stream_target` (`user_id`, `feedback_type`, `stream`, `target_id`, `target_key`),
    key `idx_user_time` (`user_id`, `create_time`)
) engine = InnoDB default charset = utf8mb4 collate = utf8mb4_unicode_ci comment ='推荐流负反馈（11.11：内容/作者/分类三类屏蔽，30 天窗口内生效）';

# 设计原因（批次十一补充表）：
# article_stat/book_stat：推荐流排序与卡片计数统一读统计表，避免翻页现算聚合；累计计数只增不减，
#   收藏数排除作者自收藏（作者可收藏自己的作品，但统计不收这一条）。
# content_stat_window：窗口值预计算落表，排序优先读 30 天窗口、缺失回退累计值。
# book_read_quality：零前端埋点的粗完读代理，只读统计，不参与排序。
# user_like：content 临时承载点赞，唯一键保证重复点赞幂等，取消点赞物理删除。
# feed_exposure_daily：曝光/点击按天聚合，同身份同日同条各最多 1 次，登录/匿名分行便于两种 CTR 口径。
# feed_feedback：负反馈名单，内容级在切片阶段过滤，作者/分类级在建池阶段过滤。
