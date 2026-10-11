package org.dromara.system.api;

import org.dromara.system.api.domain.OssDTO;

import java.util.List;

/**
 * 通用 OSS服务
 *
 * @author Lion Li
 */
public interface OssService {

    /**
     * 通过ossId查询对应的url
     *
     * @param ossIds ossId串逗号分隔
     * @return url串逗号分隔
     */
    String selectUrlByIds(String ossIds);

    /**
     * 通过ossId查询服务端读写用的地址（忽略对外域名，公开桶按访问端点生成，供容器内服务端下载/解析使用）
     *
     * @param ossIds ossId串逗号分隔
     * @return url串逗号分隔
     */
    String selectInternalUrlByIds(String ossIds);

    /**
     * 通过ossId查询列表
     *
     * @param ossIds ossId串逗号分隔
     * @return 列表
     */
    List<OssDTO> selectByIds(String ossIds);

    /**
     * 获取当前默认对象存储配置的**对外访问域名**（{@code sys_oss_config.domain_url}）。
     *
     * <p>用途：平台健康自检判定「前端附件预览 / 下载所用的地址来源是否已配置」——未配置时前端会加载失败，
     * 而后端读写（走访问端点 endpoint）仍正常。取的是对象存储客户端缓存的当前配置，与
     * {@link #selectUrlByIds(String)} 生成对外地址时同源。</p>
     *
     * @return 对外域名；未配置或对象存储尚未初始化时返回 null
     */
    String selectDomainUrl();

}
