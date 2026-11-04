package com.litisle.litislesearch.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestClientBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Elasticsearch 客户端装配（架构文档 §1.2 es 包：连接配置收口）：
 * - 按 SearchProperties.es.hosts（host:port，逗号分隔多节点）构建低级 RestClient
 * - 超时：connect-timeout（默认 3s）+ socket-timeout（默认 10s，ai 侧 RAG 依赖此超时可容忍）
 * - 凭证：username/password 非空时启用 Basic 认证（开发环境 ES 已关闭安全认证，留空跳过；
 *   生产走环境变量 LITISLE_ES_USERNAME / LITISLE_ES_PASSWORD 注入）
 * - 序列化：JacksonJsonpMapper 复用 Spring 容器的 ObjectMapper（与全局 Jackson 配置一致）
 *
 * 说明：索引/mapping 初始化（article_idx / book_idx / user_idx 建索引、ik 分词器、
 * dense_vector mapping、completion 子字段）属批次D D1 任务，本骨架仅装配客户端连接
 */
@Configuration
public class ElasticsearchConfig {

    /**
     * 构建 ElasticsearchClient Bean（search 模块唯一 ES 入口，业务侧直接注入使用）
     *
     * @param properties    search 配置绑定 Bean（集群地址/凭证/超时均来自 yml 外置）
     * @param objectMapper  Spring 容器全局 ObjectMapper（与 REST 层序列化口径一致）
     * @return 可复用的 ElasticsearchClient（transport 内部自建连接池，单例复用）
     */
    @Bean
    public ElasticsearchClient elasticsearchClient(SearchProperties properties,
                                                   com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        // 变量：解析后的 HttpHost 节点列表（host:port 字符串 → HttpHost，默认 http 协议）
        List<HttpHost> httpHosts = parseHosts(properties.getEs().getHosts());

        // 变量：低级 REST 客户端构建器（连接/查询超时 + 可选 Basic 认证均在此收口）
        RestClientBuilder builder = RestClient.builder(httpHosts.toArray(new HttpHost[0]))
                .setRequestConfigCallback(requestConfig -> requestConfig
                        // 建连超时（毫秒）
                        .setConnectTimeout((int) properties.getEs().getConnectTimeout().toMillis())
                        // 查询 socket 超时（毫秒）
                        .setSocketTimeout((int) properties.getEs().getSocketTimeout().toMillis()));

        // 分支：凭证非空才启用 Basic 认证（开发环境 ES 关闭安全认证，留空跳过）
        if (StringUtils.hasText(properties.getEs().getUsername())) {
            // 变量：Basic 凭证提供器（凭证仅对 ES 集群地址作用域生效）
            BasicCredentialsProvider credentialsProvider = new BasicCredentialsProvider();
            credentialsProvider.setCredentials(AuthScope.ANY,
                    new UsernamePasswordCredentials(properties.getEs().getUsername(),
                            properties.getEs().getPassword()));
            builder.setHttpClientConfigCallback(httpClientBuilder ->
                    httpClientBuilder.setDefaultCredentialsProvider(credentialsProvider));
        }

        // 变量：低级 REST 客户端（durable，随 Bean 生命周期由容器关闭）
        RestClient restClient = builder.build();

        // 组装：RestClientTransport + Jackson 序列化 → 高级客户端（业务侧直接注入 ElasticsearchClient）
        return new ElasticsearchClient(new RestClientTransport(restClient, new JacksonJsonpMapper(objectMapper)));
    }

    /**
     * 解析 ES 集群地址列表：每项 host:port，支持 http/https 前缀（未写前缀默认 http）
     *
     * @param hosts 集群地址字符串列表（如 ["localhost:9200"] 或多节点）
     * @return HttpHost 节点列表（顺序与配置一致）
     */
    private List<HttpHost> parseHosts(List<String> hosts) {
        // 变量：解析结果集
        List<HttpHost> result = new ArrayList<>();
        for (String host : hosts) {
            // 防御：跳过空白配置项
            if (!StringUtils.hasText(host)) {
                continue;
            }
            // 变量：协议（默认 http，支持 https:// 前缀显式指定）
            String scheme = "http";
            // 变量：去掉协议前缀后的 host:port
            String hostPort = host.trim();
            int schemeIdx = hostPort.indexOf("://");
            if (schemeIdx > 0) {
                scheme = hostPort.substring(0, schemeIdx);
                hostPort = hostPort.substring(schemeIdx + 3);
            }
            // 变量：按最后一个冒号拆 host 与 port（IPv6 场景暂不支持，按需扩展）
            int portIdx = hostPort.lastIndexOf(':');
            // 分支：无端口则用 ES 默认 9200
            if (portIdx > 0) {
                result.add(new HttpHost(hostPort.substring(0, portIdx),
                        Integer.parseInt(hostPort.substring(portIdx + 1)), scheme));
            } else {
                result.add(new HttpHost(hostPort, 9200, scheme));
            }
        }
        return result;
    }
}
