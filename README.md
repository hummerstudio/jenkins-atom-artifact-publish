# jenkins-atom-artifact-publish

把构建产物推到 Artifactory / Nexus。

两者只差一段 URL：`/artifactory/<repo>/<path>` 与 `/repository/<repo>/<path>`；鉴权都是 basic 或 bearer。所以一个原子按 `provider` 切换，校验和只算一次（`X-Checksum-Sha256`，Artifactory 会自己核对）。

`pathTemplate` 支持 `{name}`（文件名）、`{path}`（工作区相对路径）、`{dir}`（所在目录），所以既能平铺成 `1.0.0/app.jar`，也能保留 `com/acme/app/1.0.0/app.jar` 的 Maven 布局。

```bash
mvn verify    # 编译、打包到 dist/artifact-publish/，再跑 src/test/java/atom/ArtifactPublishIT.java
```
