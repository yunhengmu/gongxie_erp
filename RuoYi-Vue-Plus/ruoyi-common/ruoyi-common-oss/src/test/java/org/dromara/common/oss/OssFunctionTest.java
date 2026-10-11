package org.dromara.common.oss;

import org.dromara.common.oss.enums.AccessPolicy;
import org.dromara.common.oss.exception.S3StorageException;
import org.dromara.common.oss.util.BucketUrlUtil;
import org.dromara.common.oss.config.OssClientConfig;
import org.dromara.common.oss.properties.OssProperties;
import software.amazon.awssdk.regions.Region;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("common-oss 功能单元测试")
class OssFunctionTest {

    /**
     * 验证桶地址生成会规范化已有协议头，并正确区分路径风格与站点风格。
     */
    @Test
    @DisplayName("生成规范的桶访问地址")
    void shouldBuildNormalizedBucketUrls() {
        assertEquals("https://s3.example.com/images",
            BucketUrlUtil.getPathStyleBucketUrl(true, "http://s3.example.com", "images"));
        assertEquals("http://images.s3.example.com",
            BucketUrlUtil.getSiteStyleBucketUrl(false, "https://s3.example.com", "images"));
        assertEquals("https://s3.example.com", BucketUrlUtil.rebuildUrlHeader(true, "HTTP://s3.example.com"));
    }

    /**
     * 验证访问策略类型可以映射到枚举，未知类型会抛出明确的存储异常。
     */
    @Test
    @DisplayName("解析 OSS 访问策略")
    void shouldResolveAccessPolicyOrRejectUnknownType() {
        assertEquals(AccessPolicy.PRIVATE, AccessPolicy.formType("0"));
        assertEquals(AccessPolicy.PUBLIC_READ_WRITE, AccessPolicy.formType("1"));
        assertEquals(AccessPolicy.PUBLIC_READ, AccessPolicy.formType("2"));
        assertThrows(S3StorageException.class, () -> AccessPolicy.formType("9"));
    }

    /**
     * 验证旧版 OSS 属性可转换为客户端配置，并按 endpoint 类型推断路径风格和默认 Region。
     */
    @Test
    @DisplayName("从兼容属性构建 OSS 客户端配置")
    void shouldBuildClientConfigFromLegacyProperties() {
        OssProperties properties = properties("http://minio.example.com", null, "images");
        properties.setRegion(" ");
        properties.setIsHttps("Y");
        properties.setAccessPolicy("2");

        OssClientConfig config = OssClientConfig.formProperties(properties);

        assertTrue(config.useHttps());
        assertTrue(config.usePathStyleAccess());
        assertEquals(Region.US_EAST_1, config.region().orElseThrow());
        assertEquals("https://minio.example.com", config.getEndpointUrl());
        assertEquals("https://minio.example.com/images", config.getBucketUrl());
        assertTrue(config.accessControlPolicyConfig().enabled());
        assertEquals(AccessPolicy.PUBLIC_READ, config.accessControlPolicyConfig().accessPolicy());
    }

    /**
     * 验证自定义域名只直接服务默认桶，访问其他桶时仍回退标准 S3 endpoint 地址。
     */
    @Test
    @DisplayName("区分默认桶和其他桶的自定义域名")
    void shouldUseCustomDomainOnlyForDefaultBucket() {
        OssProperties properties = properties("https://oss-cn-hangzhou.aliyuncs.com", "https://cdn.example.com", "images");
        properties.setRegion("ap-southeast-1");

        OssClientConfig config = OssClientConfig.formProperties(properties);

        assertFalse(config.usePathStyleAccess());
        assertEquals("https://cdn.example.com", config.getBucketUrl());
        assertEquals("https://archive.oss-cn-hangzhou.aliyuncs.com", config.getBucketUrl("archive"));
        assertEquals(Region.AP_SOUTHEAST_1, config.region().orElseThrow());
    }

    /**
     * 验证必要 endpoint 或 bucket 缺失时明确失败，并且复制配置会深复制嵌套配置对象。
     */
    @Test
    @DisplayName("校验 OSS 必要配置并复制客户端配置")
    void shouldValidateRequiredConfigAndCopyNestedSettings() {
        OssClientConfig missingEndpoint = OssClientConfig.builder().bucket("images").build();
        OssClientConfig missingBucket = OssClientConfig.builder().endpoint("s3.example.com").build();
        OssClientConfig config = OssClientConfig.formProperties(properties("s3.example.com", null, "images"));

        assertThrows(S3StorageException.class, missingEndpoint::getEndpointUrl);
        assertThrows(S3StorageException.class, missingBucket::getBucketUrl);

        OssClientConfig copied = config.copy();
        assertNotSame(config, copied);
        assertEquals(config.getEndpointUrl(), copied.getEndpointUrl());
        assertEquals(config.getBucketUrl(), copied.getBucketUrl());
        assertEquals(config.region(), copied.region());
        assertEquals(config.prefix(), copied.prefix());
        assertEquals(config.accessControlPolicyConfig(), copied.accessControlPolicyConfig());
        assertEquals(config.asyncExecutorConfig(), copied.asyncExecutorConfig());
        assertNotSame(config.accessControlPolicyConfig(), copied.accessControlPolicyConfig());
        assertNotSame(config.asyncExecutorConfig(), copied.asyncExecutorConfig());
    }

    /**
     * 验证路径风格（如 MinIO）下自定义域名同样生效：域名不含桶，按路径风格拼上桶名。
     *
     * <p>用于容器化部署：endpoint 为容器服务名（如 {@code minio:9000}）浏览器不可达，
     * 通过 {@code sys_oss_config.domain_url} 给出对外可访问地址。</p>
     */
    @Test
    @DisplayName("路径风格下自定义域名拼接桶名")
    void shouldAppendBucketForCustomDomainUnderPathStyle() {
        OssProperties properties = properties("minio:9000", "oss.example.com", "ruoyi");
        properties.setIsHttps("N");

        OssClientConfig config = OssClientConfig.formProperties(properties);

        assertTrue(config.usePathStyleAccess());
        assertEquals("http://oss.example.com/ruoyi", config.getBucketUrl());
        assertEquals("http://oss.example.com/ruoyi", config.getBucketUrl("ruoyi"));
    }

    /**
     * 验证服务端读写用的桶地址一律基于 endpoint 生成，忽略自定义域名。
     *
     * <p>容器化部署时 endpoint 为容器服务名（如 {@code minio:9000}），服务端下载/解析
     * 必须使用该地址，而不能使用仅对外可达的自定义域名。</p>
     */
    @Test
    @DisplayName("基于 endpoint 生成服务端读写桶地址并忽略自定义域名")
    void shouldBuildEndpointBucketUrlIgnoringCustomDomain() {
        OssProperties properties = properties("minio:9000", "oss.example.com", "ruoyi");
        properties.setIsHttps("N");

        OssClientConfig config = OssClientConfig.formProperties(properties);

        assertTrue(config.usePathStyleAccess());
        assertEquals("http://minio:9000/ruoyi", config.getEndpointBucketUrl());
        assertEquals("http://minio:9000/archive", config.getEndpointBucketUrl("archive"));
    }

    /**
     * 验证「绑定桶的自定义域名」仅对**非路径风格**的默认桶成立。
     *
     * <p>路径风格（如 MinIO）下域名不绑定桶，对象地址含桶名（{@code <域名>/<桶>/<键>}），
     * 若按绑定桶签名会漏掉桶名、得到 404 地址，故即便配了域名也必须判为 false。</p>
     */
    @Test
    @DisplayName("判断是否使用绑定桶的自定义域名签名（路径风格须排除）")
    void shouldUseBucketBoundDomainOnlyWhenNotPathStyleAndDefaultBucket() {
        // 路径风格 + 自定义域名：不绑定（否则预签名 URL 会漏掉桶名）
        OssClientConfig pathStyle = OssClientConfig.formProperties(properties("minio:9000", "oss.example.com", "ruoyi"));
        assertTrue(pathStyle.usePathStyleAccess());
        assertFalse(pathStyle.useBucketBoundDomain("ruoyi"));

        // 非路径风格（云厂商 endpoint）+ 自定义域名：默认桶绑定、其它桶不绑定
        OssClientConfig cloudWithDomain = OssClientConfig.formProperties(
            properties("https://oss-cn-hangzhou.aliyuncs.com", "https://cdn.example.com", "ruoyi"));
        assertFalse(cloudWithDomain.usePathStyleAccess());
        assertTrue(cloudWithDomain.useBucketBoundDomain("ruoyi"));
        assertFalse(cloudWithDomain.useBucketBoundDomain("archive"));

        // 未配置自定义域名：一律不绑定
        OssClientConfig withoutDomain = OssClientConfig.formProperties(
            properties("https://oss-cn-hangzhou.aliyuncs.com", null, "ruoyi"));
        assertFalse(withoutDomain.useBucketBoundDomain("ruoyi"));
    }

    /**
     * 验证路径风格下「桶地址」与预签名路径口径一致：都带桶名。
     *
     * <p>这是对上面用例的补充——私有桶改用预签名后，若两者口径不一致，
     * 就会出现「公开地址能开、预签名 404」的隐性回归。</p>
     */
    @Test
    @DisplayName("路径风格下桶地址与预签名口径一致（均带桶名）")
    void shouldKeepBucketInPathUnderPathStyle() {
        OssProperties properties = properties("minio:9000", "http://localhost:9000", "ruoyi");
        properties.setIsHttps("N");

        OssClientConfig config = OssClientConfig.formProperties(properties);

        assertTrue(config.usePathStyleAccess());
        // 对外桶地址：域名 + 桶名
        assertEquals("http://localhost:9000/ruoyi", config.getBucketUrl());
        // 预签名不走「绑定桶」分支，故由 S3Presigner 按路径风格拼出 域名/桶/对象键
        assertFalse(config.useBucketBoundDomain("ruoyi"));
    }

    /**
     * 创建覆盖 URL 构造所需字段的 OSS 属性。
     *
     * @param endpoint endpoint 地址
     * @param domain   自定义域名
     * @param bucket   默认桶
     * @return OSS 属性
     */
    private static OssProperties properties(String endpoint, String domain, String bucket) {
        OssProperties properties = new OssProperties();
        properties.setEndpoint(endpoint);
        properties.setDomainUrl(domain);
        properties.setBucketName(bucket);
        properties.setAccessKey("access-key");
        properties.setSecretKey("secret-key");
        properties.setPrefix("business");
        properties.setIsHttps("Y");
        return properties;
    }
}
