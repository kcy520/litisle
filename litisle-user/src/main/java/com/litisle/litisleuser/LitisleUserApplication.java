package com.litisle.litisleuser;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 一键启动docker容器
cd /d e:\NewJavaPro\LitIsle  &&  docker-compose up -d
*一键关闭docker容器
docker-compose stop



 /**
 ---------------------------------------------------------------------
 knife4j-url:
 http://localhost:8081/doc.html

 ---------------------------------------------------------------------
 docker-compose
 cd /d e:\NewJavaPro\LitIsle && docker-compose start

 ---------------------------------------------------------------------
 sentinel:
 cd /d E:\importantFile\sentinel && java -Dserver.port=8090 -Dcsp.sentinel.dashboard.server=localhost:8090 -Dproject.name=sentinel-dashboard -jar sentinel-dashboard-1.8.9.jar

 ---------------------------------------------------------------------
 重建compose:
 cd /d E:\NewJavaPro\LitIsle
 docker-compose down
 docker-compose up -d

 //自动创建缺失的容器
 cd /d e:\NewJavaPro\LitIsle &&  docker-compose up -d

 ---------------------------------------------------------------------
 RabbitMQ:
 http://localhost:15672

 查看日志
 docker logs --tail 60 ms-demo-rabbitmq


 ---------------------------------------------------------------------
 控制台清屏
 cls

 ---------------------------------------------------------------------

 版本
 Elasticsearch-Kibana-IK分词器
 8.18.8
 网络：
 es-net
 */



@SpringBootApplication
public class LitisleUserApplication {

    public static void main(String[] args) {
        SpringApplication.run(LitisleUserApplication.class, args);
    }

}
