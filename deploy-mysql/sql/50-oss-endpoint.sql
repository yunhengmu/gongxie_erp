-- ==========================================================================================
-- 容器化部署专用：把平台文件存储（OSS/S3）的端点从 127.0.0.1 改为容器服务名。
--
-- 背景：RuoYi-Vue-Plus 的 OSS 坐标存在数据库表 sys_oss_config（不是 yaml），
--       框架种子写的是 127.0.0.1:9000（对应本地开发机的 MinIO）。容器里 127.0.0.1 是容器自身，
--       上传会报 `S3StorageException: Connection refused: /127.0.0.1:9000`。
--
-- 本文件由 compose 额外挂进 docker-entrypoint-initdb.d（序号 50，最后执行），
-- 只在数据卷为空的首次初始化时运行，不会影响非容器环境的种子数据。
-- ==========================================================================================

update sys_oss_config set endpoint = 'minio:9000' where config_key in ('minio', 'image');

-- 对外访问地址（返回给前端的 URL）：endpoint 的 `minio:9000` 只在容器网络内可达，浏览器解析不了，
-- 会导致图片 / 文档在线预览失败（后端读图与解析正常、前端却加载不出来）。
-- 这里给出**宿主机可达**的地址：本栈平台 MinIO 映射在宿主机 **9002**（`MINIO_API_PORT`，与 PG 栈的 9000 错开），
-- 后端返回给前端的 URL 即以此为准。若改了 `MINIO_API_PORT` 或需从其它机器 / 公网访问，
-- 请同步把下面的端口 / 主机改成实际可达值（改完需重启 ruoyi-admin 让其重载 OSS 配置）。
--
-- 注意：这是**私有桶方案的硬前置**。下面把桶权限改为私有后，所有文件访问都要靠后端下发的
-- 预签名 URL，而预签名 URL 默认按该 domain_url 生成，故它必须是浏览器可达的地址；
-- 容器内的服务端下载 / 文档解析不受影响——后端另用 endpoint（minio:9000）内部签名。
update sys_oss_config set domain_url = 'http://localhost:9002' where config_key in ('minio', 'image');

-- 桶权限：私有（0）。配合 minio-init 不再开放匿名读，匿名直访对象一律 403，
-- 前端展示 / 下载统一走后端下发的预签名 URL（读取时现算，不落库）。
update sys_oss_config set access_policy = '0' where config_key in ('minio', 'image');