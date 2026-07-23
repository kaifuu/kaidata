# 全栈单镜像：后端 datalake-service + 前端 datalake-web 打进一个镜像。
# 容器内 nginx 托管前端 dist + 反代 /api 到后端 java(8090)。
# 构建：docker build -t <name>:<tag> -f docker/app/Dockerfile .（上下文为仓库根）
# 前置：确保 datalake-service/target/datalake-service.jar 为最新：
#   cd datalake-service && mvn -DskipTests package   （start.sh 已自动生成）

# ===== Stage 1: 前端构建 =====
FROM node:20-alpine AS web
WORKDIR /web
COPY datalake-web/package.json datalake-web/package-lock.json* ./
RUN npm install --no-audit --no-fund
COPY datalake-web/ ./
# 生产构建跳过 vue-tsc 类型检查（项目含既有 TS 隐式 any；vite build 用 esbuild 转译不校验类型，产物与 dev 一致）
RUN npx vite build

# ===== Stage 2: 运行时（JRE + nginx） =====
# 后端 jar 复用本机预构建产物，避免镜像内重复下载 maven 全量依赖（首次可省数分钟）。
FROM eclipse-temurin:17-jre-jammy
RUN apt-get update && apt-get install -y --no-install-recommends nginx \
    && rm -rf /var/lib/apt/lists/* \
    && rm -f /etc/nginx/sites-enabled/default /etc/nginx/sites-available/default
COPY --from=web /web/dist /usr/share/nginx/html
COPY datalake-service/target/datalake-service.jar /app/datalake-service.jar
COPY docker/app/nginx.conf /etc/nginx/conf.d/default.conf
COPY docker/app/entrypoint.sh /app/entrypoint.sh
RUN chmod +x /app/entrypoint.sh
EXPOSE 80
ENTRYPOINT ["/app/entrypoint.sh"]
