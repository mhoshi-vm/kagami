# S3 storage backend

Difficulty: Medium

Depends on 001 (decouple the storage layer from the local filesystem), which is done:
`StorageService` is the single path to stored artifacts, `StorageServiceContractTest`
and `BrowserE2ETestBase` exist. This item is a new implementation of a settled contract.

## Goal

Add an S3-backed `StorageService` implementation so Kagami can mirror artifacts into
Amazon S3 or any S3-compatible object storage, selectable by configuration, with the
filesystem backend remaining the default.

## Requirements

### 1. Configuration

Extend `KagamiProperties.Storage`:

- `kagami.storage.type` - `LOCAL` (default) or `S3`.
- `kagami.storage.path` - unchanged, used by `LOCAL`.
- `kagami.storage.s3.bucket` - required for `S3`.
- `kagami.storage.s3.key-prefix` - optional prefix prepended to `{repositoryId}/...`.

Endpoint, region and credentials come from Spring Cloud AWS properties
(`spring.cloud.aws.*`), not from `kagami.*`.

Remove `@Service` from `LocalStorageService` and select the implementation in a
`@Configuration(proxyBeanMethods = false)` class inside the `storage` package, using
`@ConditionalOnProperty(prefix = "kagami.storage", name = "type", havingValue = "s3")`
and a matching default. Keep `storage` free of dependencies on other feature packages.

`application.properties` wires `management.health.diskspace.path` and
`management.metrics.system.diskspace.paths` to `kagami.storage.path`; both are
meaningless with S3. Disable the diskspace health indicator and metric when the type is
`S3`.

### 2. Dependency

Import the Spring Cloud AWS BOM in `dependencyManagement` and declare the starter
without a version. The `dependencyManagement` section was dropped in commit `0e7a4c7`
and has to be reintroduced for this.

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>io.awspring.cloud</groupId>
      <artifactId>spring-cloud-aws-dependencies</artifactId>
      <version>${spring-cloud-aws.version}</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>
```

```xml
<dependency>
  <groupId>io.awspring.cloud</groupId>
  <artifactId>spring-cloud-aws-starter-s3</artifactId>
</dependency>
```

with `<spring-cloud-aws.version>4.1.1</spring-cloud-aws.version>` in `<properties>`.

Consider excluding `software.amazon.awssdk:netty-nio-client` if only the synchronous
`S3Client` is used. Verify the GraalVM native build still works (Spring Cloud AWS ships
`S3RuntimeHints`).

### 3. Implementation

New `am.ik.kagami.storage.S3StorageService` using Spring Cloud AWS.

- `store` - `S3Template#upload` (or `S3Client#putObject` with
  `RequestBody.fromContentProvider`) for unknown-length streams. Set the content type
  from the file extension.
- `retrieve` - return the `S3Resource` for the key, `Optional.empty()` on `NoSuchKey`.
  `ArtifactController` calls `exists()` and `contentLength()` on the returned resource,
  each of which triggers a `HeadObject`; check whether one `HeadObject` in `retrieve` can
  serve both.
- `list` - `ListObjectsV2` with `prefix` and `delimiter = "/"`, mapping `CommonPrefixes`
  to directories (no last-modified) and `Contents` to files.
- `stat` - `HeadObject`.
- `delete` - delete the object, and every key under the prefix when the location denotes
  a directory (batched `DeleteObjects`).
- `stats` - paginated `ListObjectsV2` over the whole repository prefix.

`S3Operations#listObjects` returns a flat list with no delimiter support, so directory
listing must use `S3Client#listObjectsV2` directly.

### 4. Tests with rustfs + Testcontainers

Use `rustfs/rustfs` as the S3-compatible server. A `GenericContainer` is enough:

- image `rustfs/rustfs:1.0.0-rc.5`, exposed port `9000`
- env `RUSTFS_VOLUMES=/data`, `RUSTFS_ADDRESS=0.0.0.0:9000`,
  `RUSTFS_CONSOLE_ENABLE=false`, `RUSTFS_ACCESS_KEY` / `RUSTFS_SECRET_KEY`
- wait strategy: HTTP 200 on `/health` (port 9000)
- properties: `spring.cloud.aws.s3.endpoint`,
  `spring.cloud.aws.s3.path-style-access-enabled=true`,
  `spring.cloud.aws.region.static`, `spring.cloud.aws.credentials.access-key` /
  `secret-key`
- create the bucket in test setup

Test coverage:

- Subclass the storage contract test from 001 against `S3StorageService`.
- An integration test equivalent to `KagamiIntegrationTest` running with
  `kagami.storage.type=s3`: fetch an artifact from the `MockServer` remote through
  `/artifacts/**`, confirm the object lands in the bucket, confirm the second request is
  served from storage, and confirm `DELETE` removes it.
- Browser API tests against S3 storage (listing, breadcrumbs, file info with
  sha1/sha256).
- Run the Playwright E2E scenario from 001 against the S3 backend as well.

The existing `TestcontainersConfiguration` is empty and no test currently starts a
container; this item introduces the first one. CI (`categolj/workflows`
`unit-test.yaml`, `ubuntu-latest`) has Docker, so no workflow change should be needed
for the container itself.

### 5. Documentation

- `README.md` - S3 configuration section, and move "S3 Storage" out of the roadmap list
  around line 720.
- `CLAUDE.md` - update the storage layer description and the package structure list.

## Verified during planning

Confirmed by hand before writing this item, so these do not need re-investigation:

- `io.awspring.cloud:spring-cloud-aws-dependencies:4.1.1` exists and its parent is
  `spring-cloud-dependencies-parent:5.0.3`; it resolves cleanly under
  `spring-boot-starter-parent:4.1.1` and pulls AWS SDK `2.54.3`.
- Relevant property names: `spring.cloud.aws.s3.endpoint`,
  `spring.cloud.aws.s3.path-style-access-enabled`, `spring.cloud.aws.s3.region`,
  `spring.cloud.aws.region.static`, `spring.cloud.aws.credentials.access-key` /
  `secret-key`.
- `rustfs/rustfs:1.0.0-rc.5` starts in single-node single-drive mode with
  `RUSTFS_VOLUMES=/data` and answers `GET /health` with 200 in about one second.
- AWS SDK v2 2.54.3 works against rustfs with default checksum settings: bucket
  creation, unknown-length streaming `PutObject` (aws-chunked + trailer checksum),
  `HeadObject`, `GetObject`, `ListObjectsV2` with `delimiter=/` returning the expected
  `CommonPrefixes`, batched `DeleteObjects`, and `NoSuchKey` on a missing key.

## Done criteria

- `./mvnw spring-javaformat:apply test` passes, including the rustfs-backed tests.
- `kagami.storage.type=local` and `kagami.storage.type=s3` pass the same storage contract
  test, and the browser UI behaves identically on both.
