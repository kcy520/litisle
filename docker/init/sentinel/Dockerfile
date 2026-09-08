FROM eclipse-temurin:25-jre

WORKDIR /app

# Sentinel Dashboard jar（1.8.9，与业务服务 SCA 2025.0.0.0 的 client 1.8.9 匹配）
COPY sentinel-dashboard-1.8.9.jar app.jar

# JAVA_OPTS 由 compose 的 environment 注入（端口、账号、内存）
ENV JAVA_OPTS=""

EXPOSE 8090

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]