#!/bin/sh
# 全栈单镜像入口：nginx（后台，托管前端 + 反代 /api）+ java（前台 PID1，接 SIGTERM）
set -e
nginx
exec java -jar /app/datalake-service.jar
