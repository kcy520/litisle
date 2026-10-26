
## 二、litisle-content 模块（数据库：litisle_content）

# （草稿上的图片也能暂存在数据库中）

# 发布时可以选择文章是否公开，可以在之后几章同时发布，或者先暂时存着（后续可以多选发布-即修改公开状态）
# 作者写好了一批内容（几篇文章、几个章节），暂时不对外公开，等攒够了/准备好了，一次性把它们一起发布出去。

# ============================================================
# 模块：litisle-content
# 数据库：litisle_content
# 说明：文章 / 草稿 / 书籍 / 章节 / 图片 / 阅读进度
# ============================================================

# ============================================================
# 模块：litisle-content
# 数据库：litisle_content
# 说明：文章 / 草稿 / 书籍 / 章节 / 图片 / 阅读进度
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
    `del_flag`      tinyint         not null default 0 comment '删除标志：0-未删除 1-已删除',
    `version`       int             not null default 0 comment '乐观锁版本号',
    `create_time`   datetime        not null default current_timestamp comment '创建时间',
    `update_time`   datetime        not null default current_timestamp on update current_timestamp comment '更新时间',
    index `idx_author_id` (`author_id`),
    index `idx_zone_status` (`zone`, `status`),
    index `idx_visibility` (`visibility`),
    index `idx_publish_batch` (`publish_batch_id`),
    index `idx_create_time` (`create_time`)
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
    index `idx_author_id` (`author_id`)
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

# 6. 阅读进度表
create table if not exists `reading_progress`
(
    `id`         bigint unsigned not null auto_increment primary key comment '主键ID',
    `user_id`    bigint unsigned not null comment '读者ID',
    `book_id`    bigint unsigned not null comment '书籍ID',
    `chapter_id` bigint unsigned not null comment '最后阅读章节ID',
    `update_time` datetime       not null default current_timestamp on update current_timestamp comment '更新时间',
    unique key `uk_user_book` (`user_id`, `book_id`),
    index `idx_book_id` (`book_id`)
    ) engine = InnoDB
    default charset = utf8mb4 comment = '阅读进度表';

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

# 设计原因：
# article 计数字段冗余（view_cnt/like_cnt）：避免每次列表查询都 join 聚合，配合 Redis 异步刷新。
# article_image 独立表：设计文档要求图片在任意位置插入 + 5 种对齐，落表后支持"图片管理"与重新排版。
# article_draft 与 article 分离：草稿是高频写、低频读，独立表避免污染主表查询缓存。article_draft 单行 UPSERT（每草稿仅一行、只存最新内容，历史版本不留存），draft_version 作多端并发编辑的乐观锁计数器；发布后 article_id 回填，关联到正式文章。
# publish_batch_id：批量发布时同一批次文章共享一个 batch_id，便于前端"按批次展示"和"批量操作"。
# book_chapter 用 (book_id, chapter_no) 唯一索引：保证章节序号不重复，序号即排序键。
# content_html 用 longtext：支持大篇幅切片前的完整正文。
# tag + article_tag 独立多对多：标签是开放词汇关键词（区别于 category 单选分类），独立池支撑改名/封禁/热门统计；每篇上限 5 个防堆砌，新建过 DFA，与正文方案B（禁编已发布正文）解耦——标签不影响 content_hash。
