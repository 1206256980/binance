# 使用 Eclipse Temurin JDK 8
FROM eclipse-temurin:8-jdk

# 设置工作目录
WORKDIR /app

# 先复制 pom.xml 和源码
COPY pom.xml .
COPY src ./src

# 使用 Maven 构建 fatJar
RUN apt-get update && apt-get install -y maven
RUN mvn clean package -DskipTests

# 将生成的 Jar 复制为 app.jar
RUN cp target/binance-1.0-SNAPSHOT.jar app.jar

# 暴露端口
EXPOSE 4567

# 运行 Jar
CMD ["java", "-jar", "app.jar"]
