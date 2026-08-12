#!/usr/bin/env bash
# 一键打包发版产物（后端 jar + 前端 dist），供上传 GitHub Release。幂等可重复执行。
# 用法： bash release.sh      → 产物输出到 release-out/
set -e
cd "$(dirname "$0")"

echo "==> [1/3] 重建后端 jar（跳过测试）"
( cd datalake-service && mvn -q -DskipTests package )

echo "==> [2/3] 重建前端 dist"
( cd datalake-web && npm run build )

echo "==> [3/3] 打包到 release-out/"
rm -rf release-out && mkdir -p release-out
cp datalake-service/target/datalake-service.jar release-out/kaidata-service.jar
( cd datalake-web && tar -czf ../release-out/kaidata-web-dist.tar.gz dist )

echo ""
echo "✅ 打包完成："
ls -lh release-out/
echo ""
echo "下一步（需先 gh auth login）："
echo "  gh release create v1.0 release-out/kaidata-service.jar release-out/kaidata-web-dist.tar.gz \\"
echo "    --title 'v1.0' --notes-file RELEASE_NOTES.md"
