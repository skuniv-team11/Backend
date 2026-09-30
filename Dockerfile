# ---- build ----
FROM gradle:8.14.3-jdk21 AS build
WORKDIR /app
# 의존성 층을 먼저 받아 두면 소스만 바뀐 빌드는 이 층을 재사용한다
COPY settings.gradle build.gradle ./
RUN gradle dependencies --no-daemon -q > /dev/null || true
COPY src ./src
RUN gradle bootJar --no-daemon -q -x test

# ---- run ----
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar
# Render Starter/Free는 메모리 512MB. 힙을 컨테이너 메모리의 70%로 묶는다
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+UseSerialGC -Xss512k"
# Render가 PORT(기본 10000)를 넣어 주고, application.yml이 그 값을 쓴다
EXPOSE 10000
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
