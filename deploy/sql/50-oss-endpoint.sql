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

-- 对外访问地址（自定义域名）：endpoint 的 `minio:9000` 只在容器网络内可达，浏览器解析不了，
-- 会导致图片/文档在线预览失败（后端读图正常、前端却加载不出来）。
-- 这里给出**宿主机可达**的地址（宿主机 9000 映射到平台 MinIO），后端返回给前端的 URL 即以此为准。
-- 若需从其它机器 / 公网访问，请把下面的地址改成对应可达的域名或 IP（改完需重启 ruoyi-admin 让其重载 OSS 配置）。
update sys_oss_config set domain_url = 'http://localhost:9000' where config_key in ('minio', 'image');