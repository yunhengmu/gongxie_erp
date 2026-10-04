package org.dromara.ai.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.dromara.ai.domain.AiTool;
import org.dromara.ai.domain.bo.AiToolBo;
import org.dromara.ai.domain.vo.AiToolTagVo;
import org.dromara.ai.domain.vo.AiToolVo;
import org.dromara.ai.mapper.AiAgentToolMapper;
import org.dromara.ai.mapper.AiToolMapper;
import org.dromara.ai.service.IAiMcpServerService;
import org.dromara.ai.service.IAiToolTagService;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.MapstructUtils;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AiToolServiceImpl} 单元测试：查询填充标签、编码校验、绑定保护与删除清理。
 */
@DisplayName("业务工具注册服务")
class AiToolServiceImplTest {

    private final AiToolMapper baseMapper = mock(AiToolMapper.class);
    private final AiAgentToolMapper agentToolMapper = mock(AiAgentToolMapper.class);
    private final IAiMcpServerService mcpServerService = mock(IAiMcpServerService.class);
    private final IAiToolTagService toolTagService = mock(IAiToolTagService.class);
    private final AiToolServiceImpl service =
        new AiToolServiceImpl(baseMapper, agentToolMapper, mcpServerService, toolTagService);

    private MockedStatic<MapstructUtils> mapstruct;

    @BeforeAll
    static void initContext() {
        AiServiceTestContext.initialize();
    }

    @BeforeEach
    void setUp() {
        mapstruct = mockStatic(MapstructUtils.class);
        mapstruct.when(() -> MapstructUtils.convert(any(AiToolBo.class), eq(AiTool.class)))
            .thenAnswer(inv -> AiServiceTestContext.copy(inv.getArgument(0), AiTool.class));
    }

    @AfterEach
    void tearDown() {
        mapstruct.close();
    }

    @Test
    @DisplayName("按主键查询时回填标签")
    void shouldFillTagsWhenQueryById() {
        AiToolVo vo = new AiToolVo();
        vo.setId(1L);
        when(baseMapper.selectVoById(1L)).thenReturn(vo);
        AiToolTagVo tag = new AiToolTagVo();
        tag.setId(7L);
        when(toolTagService.tagMapByToolIds(List.of(1L))).thenReturn(Map.of(1L, List.of(tag)));

        AiToolVo result = service.queryById(1L);

        assertSame(vo, result);
        assertEquals(List.of(tag), result.getTags());
    }

    @Test
    @DisplayName("按主键查询为空时返回空")
    void shouldReturnNullWhenQueryByIdMissing() {
        when(baseMapper.selectVoById(1L)).thenReturn(null);
        assertNull(service.queryById(1L));
    }

    @Test
    @DisplayName("按标签筛选无命中时直接返回空页")
    void shouldReturnEmptyPageWhenTagFilterMisses() {
        AiToolBo bo = new AiToolBo();
        bo.setTagIds(List.of(9L));
        when(toolTagService.toolIdsByTagIds(List.of(9L))).thenReturn(List.of());

        PageResult<AiToolVo> result = service.queryPageList(bo, new PageQuery(10, 1));

        assertEquals(0L, result.getTotal());
        assertTrue(result.getRows().isEmpty());
        verify(baseMapper, never()).selectVoPage(any(), any());
    }

    @Test
    @DisplayName("分页查询回填标签与无标签筛选")
    void shouldFillTagsAndHandleNoTag() {
        AiToolBo bo = new AiToolBo();
        bo.setNoTag(Boolean.TRUE);
        when(toolTagService.allTaggedToolIds()).thenReturn(List.of());
        AiToolVo vo = new AiToolVo();
        vo.setId(1L);
        Page<AiToolVo> page = new Page<>(1, 10);
        page.setRecords(List.of(vo));
        page.setTotal(1);
        when(baseMapper.selectVoPage(any(), any())).thenReturn(page);
        AiToolTagVo tag = new AiToolTagVo();
        tag.setId(7L);
        when(toolTagService.tagMapByToolIds(List.of(1L))).thenReturn(Map.of(1L, List.of(tag)));

        PageResult<AiToolVo> result = service.queryPageList(bo, new PageQuery(10, 1));

        assertEquals(1L, result.getTotal());
        assertEquals(List.of(tag), result.getRows().iterator().next().getTags());
    }

    @Test
    @DisplayName("查询启用工具选项")
    void shouldQueryOptions() {
        AiToolVo vo = new AiToolVo();
        when(baseMapper.selectVoList(any())).thenReturn(List.of(vo));
        assertEquals(1, service.queryOptions().size());
    }

    @Test
    @DisplayName("新增工具成功时设置标签并重整出口")
    void shouldInsertSuccessfully() {
        AiToolBo bo = new AiToolBo();
        bo.setToolCode("Query_Order_1");
        bo.setTagIds(List.of(9L));
        when(baseMapper.selectCount(any())).thenReturn(0L);
        when(baseMapper.insert(any(AiTool.class))).thenReturn(1);

        assertTrue(service.insertByBo(bo));

        verify(toolTagService).applyToolTags(any(), eq(List.of(9L)));
        verify(mcpServerService).reconcile();
    }

    @Test
    @DisplayName("新增时工具编码非法抛出异常")
    void shouldRejectInvalidCodeOnInsert() {
        AiToolBo bo = new AiToolBo();
        bo.setToolCode("1bad");

        assertThrows(ServiceException.class, () -> service.insertByBo(bo));
        verify(baseMapper, never()).insert(any(AiTool.class));
    }

    @Test
    @DisplayName("新增时工具编码重复抛出异常")
    void shouldRejectDuplicateCodeOnInsert() {
        AiToolBo bo = new AiToolBo();
        bo.setToolCode("query_order");
        when(baseMapper.selectCount(any())).thenReturn(1L);

        ServiceException ex = assertThrows(ServiceException.class, () -> service.insertByBo(bo));
        assertEquals("工具编码已存在：query_order", ex.getMessage());
    }

    @Test
    @DisplayName("修改不存在的工具抛出异常")
    void shouldRejectUpdateWhenMissing() {
        AiToolBo bo = new AiToolBo();
        bo.setId(1L);
        when(baseMapper.selectById(1L)).thenReturn(null);

        assertThrows(ServiceException.class, () -> service.updateByBo(bo));
    }

    @Test
    @DisplayName("修改工具编码时抛出异常")
    void shouldRejectCodeChangeOnUpdate() {
        AiTool existing = new AiTool();
        existing.setId(1L);
        existing.setToolCode("query_order");
        when(baseMapper.selectById(1L)).thenReturn(existing);
        AiToolBo bo = new AiToolBo();
        bo.setId(1L);
        bo.setToolCode("query_stock");

        ServiceException ex = assertThrows(ServiceException.class, () -> service.updateByBo(bo));
        assertEquals("工具编码不允许修改（已被智能体绑定）", ex.getMessage());
    }

    @Test
    @DisplayName("修改工具成功且标签为空集合时清空")
    void shouldUpdateSuccessfully() {
        AiTool existing = new AiTool();
        existing.setId(1L);
        existing.setToolCode("query_order");
        when(baseMapper.selectById(1L)).thenReturn(existing);
        when(baseMapper.selectCount(any())).thenReturn(0L);
        when(baseMapper.updateById(any(AiTool.class))).thenReturn(1);
        AiToolBo bo = new AiToolBo();
        bo.setId(1L);
        bo.setToolCode("query_order");
        bo.setTagIds(List.of());

        assertTrue(service.updateByBo(bo));

        verify(toolTagService).applyToolTags(eq(1L), eq(List.of()));
        verify(mcpServerService).reconcile();
    }

    @Test
    @DisplayName("删除时未选择主键抛出异常")
    void shouldRejectEmptyDeleteIds() {
        assertThrows(ServiceException.class, () -> service.deleteWithValidByIds(List.of(), Boolean.TRUE));
    }

    @Test
    @DisplayName("删除时主键数量不一致抛出异常")
    void shouldRejectDeleteSizeMismatch() {
        when(baseMapper.selectByIds(anyCollection())).thenReturn(List.of());

        assertThrows(ServiceException.class, () -> service.deleteWithValidByIds(List.of(1L), Boolean.TRUE));
    }

    @Test
    @DisplayName("删除被智能体绑定的工具抛出异常")
    void shouldRejectDeleteWhenBound() {
        AiTool tool = new AiTool();
        tool.setToolCode("query_order");
        when(baseMapper.selectByIds(anyCollection())).thenReturn(List.of(tool));
        when(agentToolMapper.selectCount(any())).thenReturn(1L);

        assertThrows(ServiceException.class, () -> service.deleteWithValidByIds(List.of(1L), Boolean.TRUE));
    }

    @Test
    @DisplayName("删除工具成功时清理标签并重整出口")
    void shouldDeleteSuccessfully() {
        AiTool tool = new AiTool();
        tool.setToolCode("query_order");
        when(baseMapper.selectByIds(anyCollection())).thenReturn(List.of(tool));
        when(agentToolMapper.selectCount(any())).thenReturn(0L);
        when(baseMapper.deleteByIds(anyCollection())).thenReturn(1);
        Collection<Long> ids = List.of(1L);

        assertTrue(service.deleteWithValidByIds(ids, Boolean.TRUE));

        verify(toolTagService).removeByToolIds(ids);
        verify(mcpServerService).reconcile();
    }

    @Test
    @DisplayName("按编码查询实体与存在性判断")
    void shouldQueryEntityAndExists() {
        assertNull(service.queryEntity("  "));
        AiTool tool = new AiTool();
        when(baseMapper.selectOne(any())).thenReturn(tool);
        assertSame(tool, service.queryEntity("query_order"));
        assertTrue(service.exists("query_order"));
    }

    @Test
    @DisplayName("按编码批量查询启用工具：空集合直接返回空")
    void shouldQueryEnabledByCodes() {
        assertTrue(service.queryEnabledByCodes(List.of()).isEmpty());
        AiTool tool = new AiTool();
        when(baseMapper.selectList(any())).thenReturn(List.of(tool));
        assertEquals(1, service.queryEnabledByCodes(List.of("query_order")).size());
        assertFalse(service.queryEnabledByCodes(List.of("query_order")).isEmpty());
    }

}