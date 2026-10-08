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
}
