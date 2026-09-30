# syntax=docker/dockerfile:1
FROM maven:3.9-eclipse-temurin-21-alpine AS build
WORKDIR /app
COPY . .
# `--mount=type=cache,target=/root/.m2` 是这里唯一值得加的东西。
# 下面这行 COPY 让 `mvn install` 这一层跟着源码一起失效：源码一改，依赖就要重下一次
# （约 200MB+）。挂 cache mount 后 ~/.m2 由 buildx 缓存目录托管、日常发版不再有依赖
# 下载，只剩编译。分层结构刻意不动 —— 试过「先只 COPY 全部 pom.xml 再
# dependency:go-offline」，多模块 reactor 会因找不到兄弟模块 artifact 直接失败，收益不抵风险。
# 需要 BuildKit：buildx 可用；`docker build` 请确保 DOCKER_BUILDKIT=1。
RUN --mount=type=cache,target=/root/.m2 mvn install -Dmaven.test.skip=true -q

# 把 Spring Boot fat jar 拆成 4 层。实测本 jar 75.4MB，其中：
#   dependencies        73.6MB  ← 只在 pom 变化时才需要重推
#   application          1.7MB  ← 项目自身模块 jar + classes，每次发版只需推这一层
#   spring-boot-loader   0.4MB
#   snapshot-dependencies 空
# 不拆的话整包是「一个巨大层」，每次发版都要整层重推 —— 而推的是阿里云 ACR（境外 runner 跨境推送），
# 流水线会长期卡在 "exporting to image" 且看不到进度。拆层后日常发版推送量约 75MB → 2MB（约 40 倍）。
#
# 注：-Djarmode=layertools 在 Boot 3.4 会打印 deprecated 提示（已实测可用、布局稳定）。
# 新的 -Djarmode=tools extract 会把结果放进「以 artifact 名命名的子目录」、需写死版本号，故暂不迁移。
RUN java -Djarmode=layertools -jar agent-sphere-bootstrap/target/agent-sphere-bootstrap-*.jar \
        extract --destination /app/extracted \
 && mkdir -p /app/extracted/snapshot-dependencies

FROM eclipse-temurin:21-jre-alpine
RUN apk add --no-cache tzdata
ENV TZ=Asia/Shanghai
WORKDIR /app
# 顺序即层顺序：变动越少的层越靠前，命中缓存的概率越高（缓存按 COPY 指令逐层判定）
COPY --from=build /app/extracted/dependencies/ ./
COPY --from=build /app/extracted/spring-boot-loader/ ./
COPY --from=build /app/extracted/snapshot-dependencies/ ./
COPY --from=build /app/extracted/application/ ./
EXPOSE 8080
# Boot 3.2+ 的启动器位于 org.springframework.boot.loader.launch 包（3.4.3 已实测）
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
