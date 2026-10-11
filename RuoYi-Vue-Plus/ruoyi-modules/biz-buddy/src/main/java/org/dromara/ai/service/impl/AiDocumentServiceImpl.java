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

    /**
     * 块之间拼接用的分隔符（与解析器的拼接口径一致，保证切片字符区间与全文下标对齐）
     */
    private static final String BLOCK_SEPARATOR = "\n";

    /**
     * 中文句末标点：其后恒视为断点
     */
    private static final String CJK_SENTENCE_ENDINGS = "。！？；…";

    /**
     * 英文句末标点：仅当后一位不再是词字符时才算断点，
     * 避免把 {@code 4.1}、{@code gitee.com} 这类串误判为句末
     */
    private static final String ASCII_SENTENCE_ENDINGS = ".!?;";

    /**
     * 句内停顿标点：无句末标点时的次级断点
     */
    private static final String CLAUSE_PAUSES = "，、,：:—";

    /**
     * 英文单词回退上限：超过该长度视为非自然串（URL / 长 ID），不再为其缩短切片
     */
    private static final int WORD_LOOKBACK_LIMIT = 32;

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
        result.getRecords().forEach(this::fillFileUrl);
        return PageResult.build(result.getRecords(), result.getTotal());
    }

    @Override
    public AiDocumentVo queryById(Long id) {
        AiDocumentVo vo = baseMapper.selectVoById(id);
        fillFileUrl(vo);
        return vo;
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
        // 仅校验文件存在，不落库：私有桶下预签名 URL 有有效期，file_url 在读取时现算（见 fillFileUrl）
        validateOssFile(add.getOssId());
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
            List<AiDocumentParser.ParsedBlock> blocks = AiDocumentParser.parse(bytes, document.getFileSuffix());
            TextAndRanges parsed = joinBlocks(blocks);
            String text = parsed.text();
            if (StringUtils.isBlank(text)) {
                throw new ServiceException("未从文档中解析出文本内容，可能是扫描件或空文档");
            }
            List<SlicePiece> pieces = splitPieces(text, knowledgeBase.getChunkSize(), knowledgeBase.getChunkOverlap());
            rebuildChunks(document, pieces, parsed.ranges());
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
     * 重建文档切片（写入章节路径 / 页码 / 字符区间等溯源元数据）。
     *
     * @param document 文档
     * @param pieces   切片列表（含字符区间）
     * @param ranges   块区间表（用于按切片起点定位所属块）
     */
    private void rebuildChunks(AiDocument document, List<SlicePiece> pieces, List<BlockRange> ranges) {
        LambdaQueryWrapper<AiChunk> lqw = Wrappers.lambdaQuery();
        lqw.eq(AiChunk::getDocId, document.getId());
        chunkMapper.delete(lqw);
        if (pieces.isEmpty()) {
            return;
        }
        List<AiChunk> chunks = new ArrayList<>(pieces.size());
        for (int index = 0; index < pieces.size(); index++) {
            SlicePiece piece = pieces.get(index);
            BlockRange range = locateBlock(ranges, piece.start());
            AiChunk chunk = new AiChunk();
            chunk.setKbId(document.getKbId());
            chunk.setDocId(document.getId());
            chunk.setChunkIndex(index);
            chunk.setContent(piece.text());
            chunk.setCharCount(piece.text().length());
            chunk.setCharStart(piece.start());
            chunk.setCharEnd(piece.end());
            if (range != null) {
                chunk.setHeadingPath(range.headingPath());
                chunk.setPageNo(range.pageNo());
            }
            chunks.add(chunk);
        }
        chunkMapper.insertBatch(chunks);
    }

    /**
     * 把各块正文按顺序拼成全文，并记录块在全文中的字符区间（含章节路径与页码）。
     *
     * <p>拼接口径与 {@link AiDocumentParser#join(List)} 一致（跳过空白块、以换行连接），
     * 保证切片字符区间与全文下标对齐。</p>
     *
     * @param blocks 文本块
     * @return 全文与块区间表
     */
    static TextAndRanges joinBlocks(List<AiDocumentParser.ParsedBlock> blocks) {
        StringBuilder builder = new StringBuilder();
        List<BlockRange> ranges = new ArrayList<>();
        if (blocks != null) {
            for (AiDocumentParser.ParsedBlock block : blocks) {
                String text = block.text();
                if (text == null || text.isEmpty()) {
                    continue;
                }
                if (builder.length() > 0) {
                    builder.append(BLOCK_SEPARATOR);
                }
                int start = builder.length();
                builder.append(text);
                ranges.add(new BlockRange(start, builder.length(), block.headingPath(), block.pageNo()));
            }
        }
        return new TextAndRanges(builder.toString(), ranges);
    }

    /**
     * 按切片起点定位所属块：落在块区间内取该块；落在块间分隔符时归入其后的块；越界取最后一块。
     *
     * <p>跨块切片按此规则取「起始块」，故其章节路径 / 页码取自起点所在块。</p>
     *
     * @param ranges 块区间表
     * @param pos    切片起点（字符下标）
     * @return 所属块；无块返回 null
     */
    static BlockRange locateBlock(List<BlockRange> ranges, int pos) {
        if (ranges == null || ranges.isEmpty()) {
            return null;
        }
        for (BlockRange range : ranges) {
            if (pos < range.end()) {
                return range;
            }
        }
        return ranges.get(ranges.size() - 1);
    }

    /**
     * 按知识库切分策略切分文本（返回切片正文，保留供直接单测）。
     *
     * @param text         全文
     * @param chunkSize    切片长度
     * @param chunkOverlap 切片重叠长度
     * @return 切片正文列表
     */
    static List<String> split(String text, Integer chunkSize, Integer chunkOverlap) {
        return splitPieces(text, chunkSize, chunkOverlap).stream().map(SlicePiece::text).toList();
    }

    /**
     * 按知识库切分策略切分文本：优先在句末标点处断开，其次句内停顿标点 / 空白 / 英文单词边界，
     * 最后才硬切（硬切也尽量不切断英文单词）。
     *
     * <p>产出保证：全文无遗漏（相邻切片首尾相接或重叠）、单片长度不超过 {@code chunkSize}。
     * 重叠为「尽量接近」而非严格等于 {@code chunkOverlap}：下一片起点会前移到最近的天然边界，
     * 只前移不后移，故不会产生原文缺口。每个切片附带其在全文中的字符区间
     * {@code [start, end)}（正文经 trim，区间为未裁剪的窗口）。包级可见以便直接单测。</p>
     *
     * @param text         全文
     * @param chunkSize    切片长度
     * @param chunkOverlap 切片重叠长度
     * @return 切片列表（含字符区间）
     */
    static List<SlicePiece> splitPieces(String text, Integer chunkSize, Integer chunkOverlap) {
        int size = chunkSize == null || chunkSize <= 0 ? DEFAULT_CHUNK_SIZE : chunkSize;
        int overlap = chunkOverlap == null || chunkOverlap < 0 ? 0 : chunkOverlap;
        if (overlap >= size) {
            // 重叠达到切片长度时推进量为零，会死循环；按五分之一兜底
            overlap = size / 5;
        }
        int length = text.length();
        List<SlicePiece> pieces = new ArrayList<>();
        int start = 0;
        while (start < length) {
            int end = Math.min(start + size, length);
            if (end < length) {
                end = pickBreakPoint(text, start, end);
            }
            String piece = text.substring(start, end).trim();
            if (!piece.isEmpty()) {
                pieces.add(new SlicePiece(piece, start, end));
            }
            if (end >= length) {
                break;
            }
            // 下一片起点：先按配置回退，再前移到最近的天然边界（词汇 / 句子起点），
            // 使切片不从词或句子中间开始；只前移不后移，故不会丢字
            start = alignToNaturalStart(text, Math.max(end - overlap, start + 1), start, overlap);
        }
        return pieces;
    }

    /**
     * 选取切片结束点：句末标点优先，其次停顿标点 / 空白 / 英文单词结束处，最后硬切。
     *
     * <p>断点只取窗口后半段，避免切出过短切片。</p>
     *
     * @param text  全文
     * @param start 切片起点
     * @param limit 窗口上限（{@code start + chunkSize}，严格小于全文长度）
     * @return 结束位置（不含）
     */
    private static int pickBreakPoint(String text, int start, int limit) {
        int floor = start + (limit - start) / 2;
        for (int i = limit - 1; i >= floor; i--) {
            if (isSentenceEnd(text, i)) {
                return i + 1;
            }
        }
        for (int i = limit - 1; i >= floor; i--) {
            if (isSoftBoundary(text, i)) {
                return i + 1;
            }
        }
        return avoidWordSplit(text, start, limit);
    }

    /**
     * 该位置是否为句末断点：换行与中文句末标点恒成立；英文句末标点需后一位不再是词字符。
     *
     * @param text 全文
     * @param i    待判位置
     * @return 是否可视为句末
     */
    private static boolean isSentenceEnd(String text, int i) {
        char c = text.charAt(i);
        if (c == '\n' || c == '\r' || CJK_SENTENCE_ENDINGS.indexOf(c) >= 0) {
            return true;
        }
        return ASCII_SENTENCE_ENDINGS.indexOf(c) >= 0
            && (i + 1 >= text.length() || !isAsciiWordChar(text.charAt(i + 1)));
    }

    /**
     * 次级断点：句内停顿标点、空白，或英文单词结束处（右侧不再是词字符）。
     *
     * @param text 全文
     * @param i    待判位置（保证 {@code i + 1} 在界内）
     * @return 是否可在此处断开
     */
    private static boolean isSoftBoundary(String text, int i) {
        char c = text.charAt(i);
        if (CLAUSE_PAUSES.indexOf(c) >= 0 || Character.isWhitespace(c)) {
            return true;
        }
        return isAsciiWordChar(c) && !isAsciiWordChar(text.charAt(i + 1));
    }

    /**
     * 硬切落在英文单词中间时回退到词首；超长「单词」（URL、长 ID 等非自然串）不做回退。
     *
     * @param text  全文
     * @param start 切片起点
     * @param limit 硬切位置
     * @return 实际结束位置（不含）
     */
    private static int avoidWordSplit(String text, int start, int limit) {
        if (limit >= text.length()
            || !isAsciiWordChar(text.charAt(limit - 1)) || !isAsciiWordChar(text.charAt(limit))) {
            return limit;
        }
        int wordStart = limit - 1;
        while (wordStart > start && isAsciiWordChar(text.charAt(wordStart - 1))) {
            wordStart--;
        }
        return limit - wordStart <= WORD_LOOKBACK_LIMIT && wordStart > start ? wordStart : limit;
    }

    /**
     * 把下一片起点前移到最近的天然边界（句末 / 停顿标点 / 空白之后，或英文单词首）。
     *
     * @param text     全文
     * @param pos      期望起点（已按重叠回退）
     * @param floor    上一片起点（结果必须大于它，保证推进）
     * @param lookback 前移搜索范围（不超过配置的重叠长度，避免重叠被放大过多）
     * @return 实际起点
     */
    private static int alignToNaturalStart(String text, int pos, int floor, int lookback) {
        int lowerBound = Math.max(floor, pos - lookback);
        for (int i = pos; i > lowerBound; i--) {
            if (isNaturalStart(text, i)) {
                return i;
            }
        }
        return pos;
    }

    /**
     * 该位置是否为天然起点：位于句末 / 停顿标点或空白之后，或恰为英文单词首。
     *
     * @param text 全文
     * @param i    待判位置（保证大于 0）
     * @return 是否适合作为一个切片的起点
     */
    private static boolean isNaturalStart(String text, int i) {
        char prev = text.charAt(i - 1);
        if (isSentenceEnd(text, i - 1) || CLAUSE_PAUSES.indexOf(prev) >= 0
            || Character.isWhitespace(prev)) {
            return true;
        }
        return isAsciiWordChar(text.charAt(i)) && !isAsciiWordChar(prev);
    }

    /**
     * ASCII 词字符判定：仅用于识别英文单词边界，中文无需按词切分。
     *
     * @param c 字符
     * @return 是否属于英文单词
     */
    private static boolean isAsciiWordChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
            || c == '_' || c == '\'';
    }

    /**
     * 下载文档字节。
     *
     * @param document 文档
     * @return 文件字节
     */
    private byte[] download(AiDocument document) {
        String url = resolveInternalUrl(document.getOssId());
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
     * 校验 OSS 文件存在（不落库，仅用于新增时的前置校验）。
     *
     * @param ossId OSS 主键
     */
    private void validateOssFile(Long ossId) {
        if (ossId == null) {
            throw new ServiceException("文件未上传，请重新上传后再提交");
        }
        String urls = ossService.selectUrlByIds(String.valueOf(ossId));
        if (StringUtils.isBlank(urls)) {
            throw new ServiceException("文件不存在或已被删除，请重新上传");
        }
    }

    /**
     * 按 OSS 主键现算文件访问地址填充到视图对象。
     * <p>
     * 私有桶下预签名 URL 有有效期，因此 file_url 不落库，列表 / 详情读取时现算。
     * OSS 异常时保留空值（前端据此禁用预览 / 下载），避免整体查询失败。
     *
     * @param vo 文档视图对象
     */
    private void fillFileUrl(AiDocumentVo vo) {
        if (vo == null || vo.getOssId() == null) {
            return;
        }
        try {
            String urls = ossService.selectUrlByIds(String.valueOf(vo.getOssId()));
            if (StringUtils.isNotBlank(urls)) {
                int index = urls.indexOf(',');
                vo.setFileUrl(index > 0 ? urls.substring(0, index) : urls);
            }
        } catch (Exception ignored) {
            // OSS 配置缺失等异常时保留空值，避免列表 / 详情整体失败
        }
    }

    /**
     * 解析 OSS 文件的服务端读写地址（下载/解析源文件用，忽略对外域名）。
     *
     * @param ossId OSS 主键
     * @return 服务端读写地址
     */
    private String resolveInternalUrl(Long ossId) {
        if (ossId == null) {
            throw new ServiceException("文件未上传，请重新上传后再提交");
        }
        String urls = ossService.selectInternalUrlByIds(String.valueOf(ossId));
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

    /**
     * 全文与块区间表。
     *
     * @param text   全文（各块正文按顺序拼接）
     * @param ranges 块区间表（按文档顺序）
     */
    record TextAndRanges(String text, List<BlockRange> ranges) {
    }

    /**
     * 块在全文中的字符区间及其溯源元数据。
     *
     * @param start       起始字符下标（含）
     * @param end         结束字符下标（不含）
     * @param headingPath 章节路径
     * @param pageNo      页码
     */
    record BlockRange(int start, int end, String headingPath, Integer pageNo) {
    }

    /**
     * 切片正文及其在全文中的字符区间。
     *
     * @param text  切片正文（已 trim）
     * @param start 起始字符下标（含，未裁剪的窗口）
     * @param end   结束字符下标（不含，未裁剪的窗口）
     */
    record SlicePiece(String text, int start, int end) {
    }

}