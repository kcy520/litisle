```
/**
 * 一键启动docker容器
cd /d e:\NewJavaPro\LitIsle  &&  docker-compose up -d
*一键关闭docker容器
docker-compose stop

 cd /d e:\NewJavaPro\LitIsle

重新挂载
 docker-compose down -v
 docker-compose up -d


 /**
 ---------------------------------------------------------------------
 knife4j-url:
 http://localhost:8081/doc.html

 ---------------------------------------------------------------------
 docker-compose
 cd /d e:\NewJavaPro\LitIsle && docker-compose start

 ---------------------------------------------------------------------
 docker exec -it litisle-redis redis-cli -a 123

 ---------------------------------------------------------------------
 sentinel:
 cd /d E:\importantFile\sentinel && java -Dserver.port=8090 -Dcsp.sentinel.dashboard.server=localhost:8090 -Dproject.name=sentinel-dashboard -jar sentinel-dashboard-1.8.9.jar

 ---------------------------------------------------------------------
 重建compose:
 cd /d E:\NewJavaPro\LitIsle
 docker-compose down
 docker-compose up -d

 //sentinel
 docker compose --profile obs up -d litisle-sentinel

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

 ---------------------------------------------------------------------

 执行初始化sql
 docker exec -i litisle-mysql mysql -uroot -pverysecret < docker/init/nacos/mysql-schema.sql

 ---------------------------------------------------------------------

# 方式一：后台隐藏窗口启动（推荐，关掉终端不影响服务）
Start-Process ollama "serve" -WindowStyle Hidden

# 方式二：前台窗口启动（能看到实时日志，Ctrl+C 即停止）
ollama serve

## 验证是否在跑
ollama list          # 能列出 bge-m3 即服务正常

关闭
Stop-Process -Name ollama -Force -ErrorAction SilentlyContinue

 */


```