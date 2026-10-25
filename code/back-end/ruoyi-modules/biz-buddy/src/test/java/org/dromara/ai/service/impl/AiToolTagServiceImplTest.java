package org.dromara.ai.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.dromara.ai.domain.AiTool;
import org.dromara.ai.domain.AiToolTag;
import org.dromara.ai.domain.AiToolTagRel;
import org.dromara.ai.domain.bo.AiToolTagBo;
import org.dromara.ai.domain.vo.AiToolTagVo;
import org.dromara.ai.mapper.AiToolMapper;
import org.dromara.ai.mapper.AiToolTagMapper;
import org.dromara.ai.mapper.AiToolTagRelMapper;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.MapstructUtils;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * {@link AiToolTagServiceImpl} 单元测试：标签分页/选项、唯一性校验、关联的先删后插与批量追加语义。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("业务工具标签 Service")
class AiToolTagServiceImplTest {

    @Mock
    private AiToolTagMapper baseMapper;

    @Mock
    private AiToolTagRelMapper relMapper;

    @Mock
    private AiToolMapper toolMapper;

    @InjectMocks
    private AiToolTagServiceImpl service;

    @BeforeAll
    static void initContext() {
        AiServiceTestContext.initialize();
    }

    /**
     * 构造标签关联行。
     */
    private AiToolTagRel rel(Long toolId, Long tagId) {
        AiToolTagRel rel = new AiToolTagRel();
        rel.setToolId(toolId);
        rel.setTagId(tagId);
        return rel;
    }

    /**
     * 构造标签视图对象。
     */
    private AiToolTagVo vo(Long id, String name) {
        AiToolTagVo vo = new AiToolTagVo();
        vo.setId(id);
        vo.setTagName(name);
        return vo;
    }

    /**
     * 构造标签实体。
     */
    private AiToolTag tag(Long id, String name) {
        AiToolTag tag = new AiToolTag();
        tag.setId(id);
        tag.setTagName(name);
        return tag;
    }

    @Test
    @DisplayName("分页查询：填充使用该标签的工具数")
    void shouldQueryPageListWithUseCount() {
        AiToolTagVo vo = vo(1L, "a");
        Page<AiToolTagVo> page = new Page<>(1, 10);
        page.setRecords(new ArrayList<>(List.of(vo)));
        page.setTotal(1L);
        when(baseMapper.selectVoPage(any(), any())).thenReturn(page);
        when(relMapper.selectList(any())).thenReturn(List.of(rel(9L, 1L), rel(8L, 1L)));

        PageResult<AiToolTagVo> result = service.queryPageList(new AiToolTagBo(), new PageQuery(1, 10));

        assertEquals(1L, result.getTotal());
        assertEquals(1, result.getRows().size());
        assertEquals(2L, vo.getUseCount());
    }

    @Test
    @DisplayName("选项查询：空列表不触发计数查询")
    void shouldQueryOptionsWithoutUseCountWhenEmpty() {
        when(baseMapper.selectVoList(any())).thenReturn(List.of());

        List<AiToolTagVo> result = service.queryOptions();

        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("新增标签：去空格后校验唯一并插入")
    void shouldInsertTag() {
        AiToolTagBo bo = new AiToolTagBo();
        bo.setTagName("  demo ");
        AiToolTag entity = new AiToolTag();
        try (MockedStatic<MapstructUtils> ms = mockStatic(MapstructUtils.class)) {
            ms.when(() -> MapstructUtils.convert(bo, AiToolTag.class)).thenReturn(entity);
            when(baseMapper.selectCount(any())).thenReturn(0L);
            when(baseMapper.insert(any(AiToolTag.class))).thenReturn(1);

            assertTrue(service.insertByBo(bo));
        }
        assertEquals("demo", entity.getTagName());
    }

    @Test
    @DisplayName("新增标签：名称为空抛异常")
    void shouldRejectBlankTagNameOnInsert() {
        AiToolTagBo bo = new AiToolTagBo();
        bo.setTagName("   ");
        try (MockedStatic<MapstructUtils> ms = mockStatic(MapstructUtils.class)) {
            ms.when(() -> MapstructUtils.convert(bo, AiToolTag.class)).thenReturn(new AiToolTag());

            assertThrows(ServiceException.class, () -> service.insertByBo(bo));
        }
    }

    @Test
    @DisplayName("新增标签：名称重复抛异常")
    void shouldRejectDuplicateTagNameOnInsert() {
        AiToolTagBo bo = new AiToolTagBo();
        bo.setTagName("dup");
        try (MockedStatic<MapstructUtils> ms = mockStatic(MapstructUtils.class)) {
            ms.when(() -> MapstructUtils.convert(bo, AiToolTag.class)).thenReturn(new AiToolTag());
            when(baseMapper.selectCount(any())).thenReturn(1L);

            assertThrows(ServiceException.class, () -> service.insertByBo(bo));
        }
    }

    @Test
    @DisplayName("修改标签：存在则更新")
    void shouldUpdateTag() {
        AiToolTagBo bo = new AiToolTagBo();
        bo.setId(1L);
        bo.setTagName("x");
        when(baseMapper.selectById(1L)).thenReturn(tag(1L, "old"));
        AiToolTag update = new AiToolTag();
        try (MockedStatic<MapstructUtils> ms = mockStatic(MapstructUtils.class)) {
            ms.when(() -> MapstructUtils.convert(bo, AiToolTag.class)).thenReturn(update);
            when(baseMapper.selectCount(any())).thenReturn(0L);
            when(baseMapper.updateById(any(AiToolTag.class))).thenReturn(1);

            assertTrue(service.updateByBo(bo));
        }
    }

    @Test
    @DisplayName("修改标签：不存在抛异常")
    void shouldRejectUpdateWhenNotExists() {
        AiToolTagBo bo = new AiToolTagBo();
        bo.setId(2L);
        bo.setTagName("x");
        when(baseMapper.selectById(2L)).thenReturn(null);

        assertThrows(ServiceException.class, () -> service.updateByBo(bo));
    }

    @Test
    @DisplayName("删除标签：空集合与数量不匹配均抛异常")
    void shouldValidateBeforeDelete() {
        assertThrows(ServiceException.class, () -> service.deleteWithValidByIds(List.of()));

        when(baseMapper.selectByIds(any())).thenReturn(List.of(tag(1L, "a")));
        assertThrows(ServiceException.class, () -> service.deleteWithValidByIds(List.of(1L, 2L)));
    }

    @Test
    @DisplayName("删除标签：级联解除关联后删除")
    void shouldDeleteTagWithRelCascade() {
        when(baseMapper.selectByIds(any())).thenReturn(List.of(tag(1L, "a"), tag(2L, "b")));
        when(baseMapper.deleteByIds(any())).thenReturn(2);

        assertTrue(service.deleteWithValidByIds(List.of(1L, 2L)));
        verify(relMapper).delete(any());
    }

    @Test
    @DisplayName("按工具查标签：空入参返回空列表，正常去重")
    void shouldQueryTagIdsOfTool() {
        assertEquals(List.of(), service.tagIdsOfTool(null));

        when(relMapper.selectList(any())).thenReturn(List.of(rel(1L, 7L), rel(1L, 7L), rel(1L, 8L)));
        assertEquals(List.of(7L, 8L), service.tagIdsOfTool(1L));
    }

    @Test
    @DisplayName("按工具批量查标签：空入参返回空 Map，无关联返回空 Map")
    void shouldQueryTagMapByToolIds() {
        assertTrue(service.tagMapByToolIds(List.of()).isEmpty());
        assertTrue(service.tagMapByToolIds(null).isEmpty());

        when(relMapper.selectList(any())).thenReturn(List.of());
        assertTrue(service.tagMapByToolIds(List.of(1L)).isEmpty());
    }

    @Test
    @DisplayName("按工具批量查标签：按标签聚合，缺失标签跳过")
    void shouldBuildTagMapByToolIds() {
        when(relMapper.selectList(any())).thenReturn(List.of(rel(1L, 7L), rel(1L, 8L), rel(2L, 7L)));
        when(baseMapper.selectVoByIds(any())).thenReturn(List.of(vo(7L, "t7")));

        Map<Long, List<AiToolTagVo>> result = service.tagMapByToolIds(List.of(1L, 2L));

        assertEquals(2, result.size());
        assertEquals(1, result.get(1L).size());
        assertEquals(1, result.get(2L).size());
    }

    @Test
    @DisplayName("按标签反查工具：空入参与正常查询")
    void shouldQueryToolIdsByTagIds() {
        assertEquals(List.of(), service.toolIdsByTagIds(List.of()));
        when(relMapper.selectList(any())).thenReturn(List.of(rel(1L, 7L), rel(1L, 7L), rel(2L, 7L)));
        assertEquals(List.of(1L, 2L), service.toolIdsByTagIds(List.of(7L)));
    }

    @Test
    @DisplayName("查询全部已打标工具主键：去重")
    void shouldQueryAllTaggedToolIds() {
        when(relMapper.selectList(any())).thenReturn(List.of(rel(1L, 7L), rel(1L, 8L), rel(2L, 8L)));
        assertEquals(List.of(1L, 2L), service.allTaggedToolIds());
    }

    @Test
    @DisplayName("设置工具标签：工具为空直接返回")
    void shouldSkipApplyWhenToolIdNull() {
        service.applyToolTags(null, List.of(1L));
        verifyNoInteractions(relMapper);
    }

    @Test
    @DisplayName("设置工具标签：清空标签仅先删不插入")
    void shouldClearTagsWhenTagIdsEmpty() {
        service.applyToolTags(1L, List.of());
        verify(relMapper).delete(any());
        verify(relMapper, never()).insert(any(AiToolTagRel.class));
    }

    @Test
    @DisplayName("设置工具标签：标签不存在抛异常")
    void shouldRejectApplyWhenTagMissing() {
        when(baseMapper.selectByIds(any())).thenReturn(List.of(tag(1L, "a")));
        assertThrows(ServiceException.class, () -> service.applyToolTags(9L, List.of(1L, 2L)));
    }

    @Test
    @DisplayName("设置工具标签：先删后插")
    void shouldApplyToolTags() {
        when(baseMapper.selectByIds(any())).thenReturn(List.of(tag(1L, "a"), tag(2L, "b")));

        service.applyToolTags(9L, List.of(1L, 2L, 2L));

        verify(relMapper).delete(any());
        verify(relMapper, times(2)).insert(any(AiToolTagRel.class));
    }

    @Test
    @DisplayName("批量打标：工具/标签为空均抛异常")
    void shouldValidateApplyTagsToTools() {
        assertThrows(ServiceException.class, () -> service.applyTagsToTools(List.of(), List.of(1L)));
        assertThrows(ServiceException.class, () -> service.applyTagsToTools(List.of(1L), List.of()));
    }

    @Test
    @DisplayName("批量打标：工具不存在抛异常")
    void shouldRejectApplyTagsToToolsWhenToolMissing() {
        when(toolMapper.selectByIds(any())).thenReturn(List.of(new AiTool()));
        assertThrows(ServiceException.class, () -> service.applyTagsToTools(List.of(1L, 2L), List.of(7L)));
    }

    @Test
    @DisplayName("批量打标：仅追加缺失关联")
    void shouldAppendOnlyMissingTags() {
        when(toolMapper.selectByIds(any())).thenReturn(List.of(new AiTool(), new AiTool()));
        when(baseMapper.selectByIds(any())).thenReturn(List.of(tag(7L, "t7")));
        when(relMapper.selectList(any())).thenReturn(List.of(rel(1L, 7L)));

        int inserted = service.applyTagsToTools(List.of(1L, 2L), List.of(7L));

        assertEquals(1, inserted);
        verify(relMapper, times(1)).insert(any(AiToolTagRel.class));
    }

    @Test
    @DisplayName("批量移除标签：工具为空抛异常，正常返回删除行数")
    void shouldRemoveTagsFromTools() {
        assertThrows(ServiceException.class, () -> service.removeTagsFromTools(List.of(), List.of(1L)));

        when(toolMapper.selectByIds(any())).thenReturn(List.of(new AiTool()));
        when(relMapper.delete(any())).thenReturn(3);
        assertEquals(3, service.removeTagsFromTools(List.of(1L), List.of(7L)));
    }

    @Test
    @DisplayName("按工具删除关联：空入参跳过，正常删除")
    void shouldRemoveByToolIds() {
        service.removeByToolIds(null);
        service.removeByToolIds(List.of());
        verifyNoInteractions(relMapper);

        service.removeByToolIds(List.of(1L));
        verify(relMapper).delete(any());
    }

}