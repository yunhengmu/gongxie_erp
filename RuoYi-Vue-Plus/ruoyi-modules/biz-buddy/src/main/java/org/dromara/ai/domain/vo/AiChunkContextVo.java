package org.dromara.ai.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 知识库切片定位视图对象：回答「参考来源」点击后可定位到命中切片及其上下文。
 *
 * <p>来源数据（{@link AiSourceVo}）只带切片序号，不带切片正文；用户点击来源时按
 * 「文档主键 + 切片序号」现查切片，才既能看到原文、又不会把整篇文档塞进消息记录。</p>
 *
 * <p><b>为什么带前后邻居</b>：单条切片通常只有几百字，脱离上下文容易看不懂。
 * 一次返回前后各 N 条，前端在同一次请求里就能渲染出可读的上下文，避免逐条再请求。</p>
 *
 * @author BizBuddy
 */
@Data
public class AiChunkContextVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 命中切片（来源指向的那一条）；文档已重新解析导致序号不存在时为 null
     */
    private AiChunkVo target;

    /**
     * 命中切片之前的相邻切片（按序号升序）
     */
    private List<AiChunkVo> before;

    /**
     * 命中切片之后的相邻切片（按序号升序）
     */
    private List<AiChunkVo> after;

}
