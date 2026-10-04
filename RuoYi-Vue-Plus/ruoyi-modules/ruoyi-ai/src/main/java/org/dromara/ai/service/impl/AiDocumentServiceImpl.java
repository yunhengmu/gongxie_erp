package org.dromara.ai.service.impl;

import cn.hutool.http.HttpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.ai.domain.AiChunk;
import org.dromara.ai.domain.AiDocument;
import org.dromara.ai.domain.AiKnowledgeBase;
import org.dromara.ai.domain.bo.AiDocumentBo;
import org.dromara.ai.domain.vo.AiChunkVo;
import org.dromara.ai.domain.vo.AiDocumentVo;
import org.dromara.ai.mapper.AiChunkMapper;
import org.dromara.ai.mapper.AiDocumentMapper;
import org.dromara.ai.service.IAiDocumentService;
import org.dromara.ai.service.IAiKnowledgeBaseService;
import org.dromara.ai.service.IAiVectorService;
import org.dromara.ai.util.AiDocumentParser;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.MapstructUtils;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.system.api.OssService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * AI 知识库文档 Service 业务层处理
 *
 * @author BizBuddy
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class AiDocumentServiceImpl implements IAiDocumentService {

    /**
     * 解析状态：待解析
     */
    private static final String STATUS_PENDING = "0";

    /**
     * 解析状态：解析中
     */
    private static final String STATUS_PARSING = "1";

    /**
     * 解析状态：已解析
     */
    private static final String STATUS_PARSED = "2";

    /**
     * 解析状态：解析失败
     */
    private static final String STATUS_FAILED = "3";

    /**
     * 向量化状态：待向量化（切片重建后回到该状态）
     */
    private static final String STATUS_VECTOR_PENDING = "0";

    /**
     * 预览文本返回上限（字符）
     */
    private static final int PREVIEW_MAX_LENGTH = 200_000;

    /**
     * 默认切片长度
     */
    private static final int DEFAULT_CHUNK_SIZE = 500;

    private final AiDocumentMapper baseMapper;

    private final AiChunkMapper chunkMapper;

    private final IAiKnowledgeBaseService knowledgeBaseService;

    private final IAiVectorService aiVectorService;

    private final OssService ossService;

    @Override
    public PageResult<AiDocumentVo> queryPageList(AiDocumentBo bo, PageQuery pageQuery) {
        LambdaQueryWrapper<AiDocument> lqw = Wrappers.lambdaQuery();
        lqw.eq(bo.getKbId() != null, AiDocument::getKbId, bo.getKbId());
        lqw.like(StringUtils.isNotBlank(bo.getDocName()), AiDocument::getDocName, bo.getDocName());
        lqw.eq(StringUtils.isNotBlank(bo.getParseStatus()), AiDocument::getParseStatus, bo.getParseStatus());
        lqw.orderByDesc(AiDocument::getCreateTime);
        Page<AiDocumentVo> result = baseMapper.selectVoPage(pageQuery.build(), lqw);
        return PageResult.build(result.getRecords(), result.getTotal());
    }

    @Override
    public AiDocumentVo queryById(Long id) {
        return baseMapper.selectVoById(id);
    }

    @Override
    public Boolean insertByBo(AiDocumentBo bo) {
        // 知识库必须存在且启用
        knowledgeBaseService.getEnabledKnowledgeBase(bo.getKbId());
        AiDocument add = MapstructUtils.convert(bo, AiDocument.class);
        add.setFileSuffix(normalizeSuffix(add.getFileSuffix()));
        if (!AiDocumentParser.supported(add.getFileSuffix())) {
            throw new ServiceException("暂不支持解析 ." + add.getFileSuffix()
                + " 格式，请上传 PDF / docx / xlsx / pptx / 文本类文件");
        }
        add.setFileUrl(resolveUrl(add.getOssId()));
        add.setParseStatus(STATUS_PENDING);
        add.setCharCount(0);
        add.setChunkCount(0);
        LambdaQueryWrapper<AiDocument> lqw = Wrappers.lambdaQuery();
        lqw.eq(AiDocument::getKbId, add.getKbId());
        lqw.eq(AiDocument::getDocName, add.getDocName());
        if (baseMapper.selectCount(lqw) > 0) {
            throw new ServiceException("该知识库下已存在同名文档：" + add.getDocName());
        }
        boolean flag = baseMapper.insert(add) > 0;
        if (flag) {
            bo.setId(add.getId());
        }
        return flag;
    }

    @Override
    public AiDocumentVo parse(Long id) {
        AiDocument document = baseMapper.selectById(id);
        if (document == null) {
            throw new ServiceException("文档不存在或已被删除");
        }
        if (STATUS_PARSING.equals(document.getParseStatus())) {
            throw new ServiceException("文档正在解析中，请稍后再试");
        }
        AiKnowledgeBase knowledgeBase = knowledgeBaseService.getEnabledKnowledgeBase(document.getKbId());
        // 切片会随解析重建，需要同步清理旧向量与向量化状态
        List<AiChunk> oldChunks = listChunks(id);
        markStatus(id, STATUS_PARSING, "");
        try {
            byte[] bytes = download(document);
            String text = AiDocumentParser.extract(bytes, document.getFileSuffix());
            if (StringUtils.isBlank(text)) {
                throw new ServiceException("未从文档中解析出文本内容，可能是扫描件或空文档");
            }
            List<String> pieces = split(text, knowledgeBase.getChunkSize(), knowledgeBase.getChunkOverlap());
            rebuildChunks(document, pieces);
            cleanVectors(knowledgeBase, oldChunks);
            AiDocument update = new AiDocument();
            update.setId(id);
            update.setContent(text);
            update.setCharCount(text.length());
            update.setChunkCount(pieces.size());
            update.setParseStatus(STATUS_PARSED);
            update.setErrorMsg("");
            update.setVectorStatus(STATUS_VECTOR_PENDING);
            update.setVectorErrorMsg("");
            baseMapper.updateById(update);
        } catch (Exception e) {
            markStatus(id, STATUS_FAILED, abbreviate(e.getMessage()));
            if (e instanceof ServiceException serviceException) {
                throw serviceException;
            }
            throw new ServiceException("文档解析失败：" + e.getMessage());
        }
        return queryById(id);
    }

    @Override
    public Boolean deleteWithValidByIds(Collection<Long> ids, Boolean isValid) {
        if (isValid) {
            List<AiDocument> list = baseMapper.selectByIds(ids);
            if (list.size() != ids.size()) {
                throw new ServiceException("您没有删除权限!");
            }
        }
        LambdaQueryWrapper<AiChunk> chunkLqw = Wrappers.lambdaQuery();
        chunkLqw.in(AiChunk::getDocId, ids);
        List<AiChunk> chunks = chunkMapper.selectList(chunkLqw);
        // 先清向量库，再删业务数据；按知识库分组清理，避免跨库集合取错
        for (AiDocument document : baseMapper.selectByIds(ids)) {
            AiKnowledgeBase knowledgeBase = knowledgeBaseService.queryEntity(document.getKbId());
            cleanVectors(knowledgeBase, chunks.stream()
                .filter(chunk -> chunk.getDocId().equals(document.getId()))
                .toList());
        }
        chunkMapper.delete(chunkLqw);
        return baseMapper.deleteByIds(ids) > 0;
    }

    @Override
    public PageResult<AiChunkVo> queryChunkPage(Long docId, PageQuery pageQuery) {
        LambdaQueryWrapper<AiChunk> lqw = Wrappers.lambdaQuery();
        lqw.eq(AiChunk::getDocId, docId);
        lqw.orderByAsc(AiChunk::getChunkIndex);
        Page<AiChunkVo> result = chunkMapper.selectVoPage(pageQuery.build(), lqw);
        return PageResult.build(result.getRecords(), result.getTotal());
    }

    @Override
    public String queryContent(Long docId) {
        AiDocument document = baseMapper.selectById(docId);
        if (document == null) {
            throw new ServiceException("文档不存在或已被删除");
        }
        if (StringUtils.isBlank(document.getContent())) {
            throw new ServiceException("文档尚未解析，请先执行解析");
        }
        String content = document.getContent();
        if (content.length() > PREVIEW_MAX_LENGTH) {
            return content.substring(0, PREVIEW_MAX_LENGTH) + "\n\n…（内容过长，仅展示前 " + PREVIEW_MAX_LENGTH + " 个字符）";
        }
        return content;
    }

    /**
     * 查询文档全部切片（按序号升序）。
     *
     * @param docId 文档主键
     * @return 切片列表
     */
    private List<AiChunk> listChunks(Long docId) {
        LambdaQueryWrapper<AiChunk> lqw = Wrappers.lambdaQuery();
        lqw.eq(AiChunk::getDocId, docId);
        lqw.orderByAsc(AiChunk::getChunkIndex);
        return chunkMapper.selectList(lqw);
    }

    /**
     * 清理切片在向量库中的向量（尽力而为，失败不阻断解析与删除）。
     *
     * @param knowledgeBase 知识库
     * @param chunks        切片列表
     */
    private void cleanVectors(AiKnowledgeBase knowledgeBase, List<AiChunk> chunks) {
        if (knowledgeBase == null || chunks == null || chunks.isEmpty()) {
            return;
        }
        List<String> vectorIds = chunks.stream()
            .map(AiChunk::getVectorId)
            .filter(StringUtils::isNotBlank)
            .toList();
        if (vectorIds.isEmpty()) {
            return;
        }
        try {
            aiVectorService.removeVectors(knowledgeBase.getId(), vectorIds);
        } catch (Exception e) {
            log.warn("清理向量失败，kbId={}, count={}, cause={}", knowledgeBase.getId(), vectorIds.size(), e.getMessage());
        }
    }

    /**
     * 重建文档切片。
     *
     * @param document 文档
     * @param pieces   切片文本
     */
    private void rebuildChunks(AiDocument document, List<String> pieces) {
        LambdaQueryWrapper<AiChunk> lqw = Wrappers.lambdaQuery();
        lqw.eq(AiChunk::getDocId, document.getId());
        chunkMapper.delete(lqw);
        if (pieces.isEmpty()) {
            return;
        }
        List<AiChunk> chunks = new ArrayList<>(pieces.size());
        for (int index = 0; index < pieces.size(); index++) {
            String content = pieces.get(index);
            AiChunk chunk = new AiChunk();
            chunk.setKbId(document.getKbId());
            chunk.setDocId(document.getId());
            chunk.setChunkIndex(index);
            chunk.setContent(content);
            chunk.setCharCount(content.length());
            chunks.add(chunk);
        }
        chunkMapper.insertBatch(chunks);
    }

    /**
     * 按知识库切分策略切分文本，尽量在换行处断开。
     *
     * @param text        全文
     * @param chunkSize   切片长度
     * @param chunkOverlap 切片重叠长度
     * @return 切片列表
     */
    private List<String> split(String text, Integer chunkSize, Integer chunkOverlap) {
        int size = chunkSize == null || chunkSize <= 0 ? DEFAULT_CHUNK_SIZE : chunkSize;
        int overlap = chunkOverlap == null || chunkOverlap < 0 ? 0 : chunkOverlap;
        if (overlap >= size) {
            overlap = size / 5;
        }
        int step = size - overlap;
        int length = text.length();
        List<String> pieces = new ArrayList<>();
        int start = 0;
        while (start < length) {
            int end = Math.min(start + size, length);
            if (end < length) {
                int newline = text.lastIndexOf('\n', end);
                if (newline > start + size / 2) {
                    end = newline + 1;
                }
            }
            String piece = text.substring(start, end).trim();
            if (!piece.isEmpty()) {
                pieces.add(piece);
            }
            if (end >= length) {
                break;
            }
            start += step;
        }
        return pieces;
    }

    /**
     * 下载文档字节。
     *
     * @param document 文档
     * @return 文件字节
     */
    private byte[] download(AiDocument document) {
        String url = resolveUrl(document.getOssId());
        byte[] bytes;
        try {
            bytes = HttpUtil.downloadBytes(url);
        } catch (Exception e) {
            throw new ServiceException("文件下载失败：" + e.getMessage());
        }
        if (bytes == null || bytes.length == 0) {
            throw new ServiceException("文件下载失败，请检查对象存储配置与文件是否仍然存在");
        }
        return bytes;
    }

    /**
     * 解析 OSS 文件访问地址。
     *
     * @param ossId OSS 主键
     * @return 访问地址
     */
    private String resolveUrl(Long ossId) {
        if (ossId == null) {
            throw new ServiceException("文件未上传，请重新上传后再提交");
        }
        String urls = ossService.selectUrlByIds(String.valueOf(ossId));
        if (StringUtils.isBlank(urls)) {
            throw new ServiceException("文件不存在或已被删除，请重新上传");
        }
        int index = urls.indexOf(',');
        return index > 0 ? urls.substring(0, index) : urls;
    }

    /**
     * 更新解析状态。
     *
     * @param id       文档主键
     * @param status   解析状态
     * @param errorMsg 失败原因
     */
    private void markStatus(Long id, String status, String errorMsg) {
        AiDocument update = new AiDocument();
        update.setId(id);
        update.setParseStatus(status);
        update.setErrorMsg(errorMsg);
        baseMapper.updateById(update);
    }

    /**
     * 规范化文件后缀。
     *
     * @param suffix 原始后缀
     * @return 小写且不含点的后缀
     */
    private String normalizeSuffix(String suffix) {
        String normalized = StringUtils.trim(suffix).toLowerCase(Locale.ROOT);
        return normalized.startsWith(".") ? normalized.substring(1) : normalized;
    }

    /**
     * 截断过长错误信息。
     *
     * @param message 原始信息
     * @return 截断后的信息
     */
    private String abbreviate(String message) {
        if (message == null) {
            return "解析失败";
        }
        String trimmed = message.trim();
        return trimmed.length() > 900 ? trimmed.substring(0, 900) : trimmed;
    }

}