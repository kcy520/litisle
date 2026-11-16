package com.litisle.litisleai.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.litisle.litisleai.provider.embedding.EmbeddingProvider;
import com.litisle.litisleai.provider.embedding.OllamaEmbeddingProvider;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestClientBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.List;

/*
 * 通俗说明：
 * 这个类只做一件事：把知识库功能要用的两个"外部连接"创建成 Spring Bean 给别处用——
 * ① 向量化客户端：把文字转成向量，连本地 Ollama；
 * ② Elasticsearch 客户端：存向量、按相似度检索。
 * 只有 litisle.ai.rag.enabled=true 时才创建；关闭时不创建，没装 Ollama/ES 的本地环境也能启动服务。
 */
/**
 * RAG 基础设施装配（板块四 4.1：ES 客户端与向量通道的唯一装配点）
 *
 * <p>【条件装配】litisle.ai.rag.enabled=false（本地默认）时本类全部 Bean 缺席：
 * RAG 消费者/检索工具同步缺席（工具返回占位降级说明 Q54），本地无 Ollama/ES 也可启动服务。</p>
 *
 * <p>【ES 口径（P9 定档）】与 search 共用集群实例，AI 自建自管切片索引（索引名隔离，
 * 默认 article_chunks_ai）；客户端装配与 search 同构（低级 RestClient + JacksonJsonpMapper），
 * 但 AI 侧持有独立 Bean（连接参数来自 AiRagProperties，互不依赖）。</p>
 *
 * @author litisle
 */
@Configuration
@EnableConfigurationProperties(AiRagProperties.class)
public class AiRagConfig {

    /**
     * Ollama 向量通道 Bean（rag.enabled=true 时装配；P3 定档本地 bge-m3）
     *
     * @param ragProperties RAG 配置
     * @return 向量通道实例
     */
    @Bean
    @ConditionalOnProperty(prefix = "litisle.ai.rag", name = "enabled", havingValue = "true")
    public EmbeddingProvider embeddingProvider(AiRagProperties ragProperties) {
        return new OllamaEmbeddingProvider(ragProperties);
    }

    /**
     * Elasticsearch 客户端 Bean（rag.enabled=true 时装配；AI 侧唯一 ES 入口）
     *
     * @param ragProperties RAG 配置（集群地址/凭证/超时均来自 yml 外置）
     * @param objectMapper  Spring 全局 ObjectMapper（JSON 映射器基础）
     * @return 可复用的 ElasticsearchClient（transport 内部自建连接池，单例复用）
     */
    @Bean
    @ConditionalOnProperty(prefix = "litisle.ai.rag", name = "enabled", havingValue = "true")
    public ElasticsearchClient aiElasticsearchClient(AiRagProperties ragProperties,
                                                     com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        // 解析集群节点（host:port → HttpHost，默认 http 协议）
        List<HttpHost> httpHosts = Arrays.stream(ragProperties.getEsHosts().split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(host -> {
                    String[] parts = host.split(":");
                    return new HttpHost(parts[0], Integer.parseInt(parts[1]), "http");
                })
                .toList();
        // 低级 REST 客户端构建器：连接/查询超时 + 可选 Basic 认证在此收口
        RestClientBuilder builder = RestClient.builder(httpHosts.toArray(new HttpHost[0]))
                .setRequestConfigCallback(requestConfig -> requestConfig
                        .setConnectTimeout((int) ragProperties.getEsConnectTimeout().toMillis())
                        .setSocketTimeout((int) ragProperties.getEsSocketTimeout().toMillis()));
        // 凭证非空时启用 Basic 认证（开发环境 ES 关闭安全认证留空跳过）
        if (StringUtils.hasText(ragProperties.getEsUsername())) {
            BasicCredentialsProvider credentialsProvider = new BasicCredentialsProvider();
            credentialsProvider.setCredentials(AuthScope.ANY,
                    new UsernamePasswordCredentials(ragProperties.getEsUsername(), ragProperties.getEsPassword()));
            builder.setHttpClientConfigCallback(httpClientBuilder ->
                    httpClientBuilder.setDefaultCredentialsProvider(credentialsProvider));
        }
        // 高级客户端：JacksonJsonpMapper 复用 Spring 序列化口径
        RestClient restClient = builder.build();
        return new ElasticsearchClient(new RestClientTransport(restClient, new JacksonJsonpMapper(objectMapper)));
    }
}
