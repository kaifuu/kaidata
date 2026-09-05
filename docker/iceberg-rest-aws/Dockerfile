# tabulario/iceberg-rest 的 S3 增强版
# 原镜像的 fat jar 不含 iceberg-aws（S3FileIO），建表时服务端只能走 HadoopFileIO → s3:// 报
# "Failed to get file system"。本镜像从 Maven Central 追加 iceberg-aws + iceberg-aws-bundle，
# 并把 `java -jar` 换成 `java -cp`（fat jar 优先、extra 兜底），服务端元数据写入即走 S3FileIO → MinIO。
FROM tabulario/iceberg-rest:latest

ADD --chmod=644 https://repo1.maven.org/maven2/org/apache/iceberg/iceberg-aws/1.5.2/iceberg-aws-1.5.2.jar /usr/lib/iceberg-rest/extra/
ADD --chmod=644 https://repo1.maven.org/maven2/org/apache/iceberg/iceberg-aws-bundle/1.5.2/iceberg-aws-bundle-1.5.2.jar /usr/lib/iceberg-rest/extra/

# 与原镜像一致（workdir=/usr/lib/iceberg-rest），仅改 classpath 启动
CMD ["java", "-cp", "iceberg-rest-image-all.jar:extra/*", "org.apache.iceberg.rest.RESTCatalogServer"]
