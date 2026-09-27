## 暂不实现（注释中已声明的预留项）

- [ ] safe_flag 审核链路（MQ 异步消费 + getUrl 闸口校验）——架构已预留，接入零侵入
- [x] 大文件流式下载（方案A，已实现）——默认仍走直连范式（PUBLIC 直链 / PRIVATE presign），
  另提供 GET /api/file/{fileId}/download 服务端流式代理端点（手动透传 Range：200/206/416），
  用于“必须经服务端中转”场景（鉴权收紧/防直链/水印）；详见下方“本次已实现”
- [ ] CDN 接入——public-url-base 已分离，届时仅改配置

## 待实现（按优先级排序）

## 本次已实现（2026-09-26，FileFeignApi batchGetUrls 跨服务暴露）

- [x] litisle-api：FileClient 新增 POST /api/feign/file/batch-urls（Result<Map<String,String>>），
  业务服务批量解析 fileId→URL 避免列表页 N+1；属展示类读操作，降级静默返回空 Map
- [x] litisle-api：FileClientFallbackFactory 补 batchGetUrls 静默降级实现，类 javadoc 降级语义同步修订
- [x] litisle-file：FileFeignProviderController 实现 batchGetUrls，委托 FileService（仅服务间可达，无 REST 暴露）

## 本次已实现（2026-09-26，大文件流式下载 · 方案A）

- [x] litisle-file：SeaweedFsClient 新增 getStream(bucket,key,start,end)——ResponseInputStream，
  按需带 S3 Range 头按字节定位，不整份载入内存（服务端代理下载专用）
- [x] litisle-file：新增 domain/dto/FileDownloadResource（record：stream/contentType/fileName/etag/totalSize/start/end/partial/rangeInvalid）
- [x] litisle-file：FileService#download + FileServiceImpl 实现——查元数据→PRIVATE 校验 owner/ADMIN→
  解析单段 Range（bytes=start-end / start- / -suffix，多段与非法语法回退全量）→200 全量/206 部分/416 非法
- [x] litisle-file：FileController 新增 GET /{fileId}/download——唯一非 Result<T> 端点，
  写 Accept-Ranges/ETag/Content-Range/Content-Disposition 头 + 8KB 流式回写（try-with-resources 关闭对象流）
- [x] litisle-file：FileLimitConstant 新增下载限流阈值（DOWNLOAD_WINDOW_SECONDS / DOWNLOAD_MAX_COUNT）


