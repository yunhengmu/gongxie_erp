package org.dromara.ai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.dromara.ai.domain.AiKnowledgeBase;
import org.dromara.ai.domain.AiModelConfig;
import org.dromara.ai.domain.bo.AiKnowledgeBaseBo;
import org.dromara.ai.domain.vo.AiKnowledgeBaseVo;
import org.dromara.ai.mapper.AiChunkMapper;
import org.dromara.ai.mapper.AiDocumentMapper;
import org.dromara.ai.mapper.AiKnowledgeBaseMapper;
import org.dromara.ai.mapper.AiModelConfigMapper;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.MapstructUtils;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.system.api.UserService;
import org.dromara.system.api.domain.UserDTO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * {@link AiKnowledgeBaseServiceImpl} 单元测试：覆盖向量模型校验、编码规范化、可见范围与删除保护。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AI 知识库 Service 单元测试")
class AiKnowledgeBaseServiceImplTest {

    @Mock
    private AiKnowledgeBaseMapper baseMapper;

    @Mock
    private AiModelConfigMapper modelConfigMapper;

    @Mock
    private AiDocumentMapper documentMapper;

    @Mock
    private AiChunkMapper chunkMapper;

    @Mock
    private UserService userService;

    @InjectMocks
    private AiKnowledgeBaseServiceImpl knowledgeBaseService;

    /**
     * 复用共享的最小 Spring 容器，供 MapstructUtils 静态初始化使用。
     */
    @BeforeAll
    static void initStaticContext() {
        AiServiceTestContext.initialize();
    }

    /**
     * 验证查询详情补齐向量模型名称。
     */
    @Test
    @DisplayName("查询详情补齐模型名称")
    void shouldFillModelNameOnQueryById() {
        AiKnowledgeBaseVo vo = new AiKnowledgeBaseVo();
        vo.setId(1L);
        vo.setEmbeddingModelId(5L);
        when(baseMapper.selectVoById(1L)).thenReturn(vo);
        AiModelConfig model = new AiModelConfig();
        model.setId(5L);
        model.setConfigName("嵌入式");
        when(modelConfigMapper.selectByIds(any())).thenReturn(List.of(model));

        AiKnowledgeBaseVo result = knowledgeBaseService.queryById(1L);

        assertEquals("嵌入式", result.getEmbeddingModelName());
    }

    /**
     * 验证查询不存在的详情返回 null。
     */
    @Test
    @DisplayName("查询不存在返回空")
    void shouldReturnNullWhenQueryByIdMissing() {
        when(baseMapper.selectVoById(1L)).thenReturn(null);
        assertNull(knowledgeBaseService.queryById(1L));
    }

    /**
     * 验证分页查询返回列表。
     */
    @Test
    @DisplayName("分页查询返回列表")
    void shouldQueryPageList() {
        AiKnowledgeBaseVo vo = new AiKnowledgeBaseVo();
        vo.setId(1L);
        Page<AiKnowledgeBaseVo> page = new Page<>(1, 10, 1);
        page.setRecords(List.of(vo));
        when(baseMapper.selectVoPage(any(), any())).thenReturn(page);

        PageResult<AiKnowledgeBaseVo> result = knowledgeBaseService.queryPageList(new AiKnowledgeBaseBo(), new PageQuery());

        assertEquals(1, result.getRows().size());
    }

    /**
     * 验证查询启用选项。
     */
    @Test
    @DisplayName("查询启用选项")
    void shouldQueryOptions() {
        when(baseMapper.selectVoList(any())).thenReturn(List.of(new AiKnowledgeBaseVo()));
        assertEquals(1, knowledgeBaseService.queryOptions().size());
    }

    /**
     * 验证按用户可见范围查询选项（含部门解析）。
     */
    @Test
    @DisplayName("按用户可见范围查询选项")
    void shouldQueryVisibleOptions() {
        UserDTO user = new UserDTO();
        user.setDeptId(3L);
        when(userService.selectById(7L)).thenReturn(user);
        when(baseMapper.selectVoList(any())).thenReturn(List.of(new AiKnowledgeBaseVo()));

        assertEquals(1, knowledgeBaseService.queryVisibleOptions(7L).size());
    }

    /**
     * 验证过滤可见知识库主键：空集合、无用户、正常过滤。
     */
    @Test
    @DisplayName("过滤可见知识库主键")
    void shouldFilterVisibleKbIds() {
        assertTrue(knowledgeBaseService.filterVisibleKbIds(List.of(), 1L).isEmpty());
        assertTrue(knowledgeBaseService.filterVisibleKbIds(List.of(1L), null).isEmpty());

        when(userService.selectById(7L)).thenReturn(null);
        AiKnowledgeBase kb1 = new AiKnowledgeBase();
        kb1.setId(1L);
        AiKnowledgeBase kb2 = new AiKnowledgeBase();
        kb2.setId(2L);
        when(baseMapper.selectList(any())).thenReturn(List.of(kb1, kb2));

        assertEquals(List.of(1L, 2L), knowledgeBaseService.filterVisibleKbIds(List.of(1L, 2L, 3L), 7L));
    }

    /**
     * 验证过滤可检索知识库主键：与「仅可见」口径的区别是额外限定「启用」状态。
     */
    @Test
    @DisplayName("过滤可检索知识库主键（可见且启用）")
    void shouldFilterRetrievableKbIds() {
        assertTrue(knowledgeBaseService.filterRetrievableKbIds(List.of(), 1L).isEmpty());
        assertTrue(knowledgeBaseService.filterRetrievableKbIds(List.of(1L), null).isEmpty());

        when(userService.selectById(7L)).thenReturn(null);
        AiKnowledgeBase kb1 = new AiKnowledgeBase();
        kb1.setId(1L);
        AiKnowledgeBase kb2 = new AiKnowledgeBase();
        kb2.setId(2L);
        when(baseMapper.selectList(any())).thenReturn(List.of(kb1, kb2));

        assertEquals(List.of(1L, 2L), knowledgeBaseService.filterRetrievableKbIds(List.of(1L, 2L, 3L), 7L));

        // 检索链路必须把「停用」库一并排除：查询条件里应出现 status 限定
        ArgumentCaptor<LambdaQueryWrapper<AiKnowledgeBase>> captor =
            ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(baseMapper).selectList(captor.capture());
        assertTrue(captor.getValue().getSqlSegment().contains("status"), "可检索过滤应限定启用状态");
    }

    /**
     * 验证「仅可见」口径**不含**启用状态条件：绑定校验等场景保持原行为，不受本次收敛影响。
     */
    @Test
    @DisplayName("过滤可见知识库主键不限定启用状态")
    void shouldFilterVisibleKbIdsWithoutStatusCondition() {
        when(userService.selectById(7L)).thenReturn(null);
        when(baseMapper.selectList(any())).thenReturn(List.of());

        knowledgeBaseService.filterVisibleKbIds(List.of(1L), 7L);

        ArgumentCaptor<LambdaQueryWrapper<AiKnowledgeBase>> captor =
            ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(baseMapper).selectList(captor.capture());
        assertFalse(captor.getValue().getSqlSegment().contains("status"), "仅可见口径不应限定启用状态");
    }

    /**
     * 验证查询公开启用知识库主键。
     */
    @Test
    @DisplayName("查询公开启用知识库主键")
    void shouldListPublicEnabledKbIds() {
        AiKnowledgeBase kb1 = new AiKnowledgeBase();
        kb1.setId(1L);
        AiKnowledgeBase kb2 = new AiKnowledgeBase();
        kb2.setId(2L);
        when(baseMapper.selectList(any())).thenReturn(List.of(kb1, kb2));

        assertEquals(List.of(1L, 2L), knowledgeBaseService.listPublicEnabledKbIds());
    }

    /**
     * 验证可见性校验：空输入放行、无法识别用户拒绝、全部可见放行。
     */
    @Test
    @DisplayName("可见性校验放行分支")
    void shouldPassValidateVisible() {
        assertDoesNotThrow(() -> knowledgeBaseService.validateVisible(List.of(), 1L, "使用"));

        assertThrows(ServiceException.class, () -> knowledgeBaseService.validateVisible(List.of(1L), null, "使用"));

        AiKnowledgeBase kb1 = new AiKnowledgeBase();
        kb1.setId(1L);
        AiKnowledgeBase kb2 = new AiKnowledgeBase();
        kb2.setId(2L);
        when(userService.selectById(7L)).thenReturn(null);
        when(baseMapper.selectList(any())).thenReturn(List.of(kb1, kb2));
        assertDoesNotThrow(() -> knowledgeBaseService.validateVisible(List.of(1L, 2L), 7L, "使用"));
    }

    /**
     * 验证越权知识库被拒绝并列出名称。
     */
    @Test
    @DisplayName("越权知识库被拒绝")
    void shouldRejectDeniedKb() {
        AiKnowledgeBase kb1 = new AiKnowledgeBase();
        kb1.setId(1L);
        when(userService.selectById(7L)).thenReturn(null);
        when(baseMapper.selectList(any())).thenReturn(List.of(kb1));
        AiKnowledgeBase denied = new AiKnowledgeBase();
        denied.setKbName("敏感库");
        when(baseMapper.selectById(2L)).thenReturn(denied);

        ServiceException ex = assertThrows(ServiceException.class,
            () -> knowledgeBaseService.validateVisible(List.of(1L, 2L), 7L, "使用"));
        assertTrue(ex.getMessage().contains("敏感库"));
    }

    /**
     * 验证越权校验命中已删除知识库时以主键占位展示。
     */
    @Test
    @DisplayName("越权校验占位已删除知识库")
    void shouldUseIdPlaceholderWhenDeniedKbMissing() {
        when(userService.selectById(7L)).thenReturn(null);
        when(baseMapper.selectList(any())).thenReturn(List.of());
        when(baseMapper.selectById(3L)).thenReturn(null);

        ServiceException ex = assertThrows(ServiceException.class,
            () -> knowledgeBaseService.validateVisible(List.of(3L), 7L, "使用"));
        assertTrue(ex.getMessage().contains("#3"));
    }

    /**
     * 验证新增成功：编码规范化、集合命名、维度解析与默认值填充。
     */
    @Test
    @DisplayName("新增成功填充默认值")
    void shouldInsertSuccessfully() {
        AiKnowledgeBaseBo bo = new AiKnowledgeBaseBo();
        AiKnowledgeBase add = new AiKnowledgeBase();
        add.setKbCode(" Kb_1 ");
        add.setEmbeddingModelId(5L);
        when(modelConfigMapper.selectById(5L)).thenReturn(embeddingModel(5L, 1024));
        when(baseMapper.selectCount(any())).thenReturn(0L);
        when(baseMapper.insert(any(AiKnowledgeBase.class))).thenAnswer(inv -> {
            ((AiKnowledgeBase) inv.getArgument(0)).setId(100L);
            return 1;
        });

        try (MockedStatic<MapstructUtils> ms = mockStatic(MapstructUtils.class)) {
            ms.when(() -> MapstructUtils.convert(bo, AiKnowledgeBase.class)).thenReturn(add);
            assertTrue(knowledgeBaseService.insertByBo(bo));
        }

        assertEquals(100L, bo.getId());
        assertEquals("kb_1", add.getKbCode());
        assertEquals("kb_kb_1", add.getCollectionName());
        assertEquals(1024, add.getDimension());
        assertEquals(500, add.getChunkSize());
        assertEquals(50, add.getChunkOverlap());
        assertEquals(5, add.getRetrievalTopK());
        assertEquals(0, new BigDecimal("0.500").compareTo(add.getSimilarityThreshold()));
        assertEquals("0", add.getStatus());
        assertEquals("0", add.getVisibleScope());
    }

    /**
     * 验证编码非法时抛业务异常。
     */
    @Test
    @DisplayName("编码非法抛异常")
    void shouldRejectInvalidCode() {
        AiKnowledgeBase add = new AiKnowledgeBase();
        add.setKbCode("1bad");
        add.setEmbeddingModelId(5L);
        when(modelConfigMapper.selectById(5L)).thenReturn(embeddingModel(5L, 1024));

        try (MockedStatic<MapstructUtils> ms = mockStatic(MapstructUtils.class)) {
            ms.when(() -> MapstructUtils.convert(any(AiKnowledgeBaseBo.class), eq(AiKnowledgeBase.class))).thenReturn(add);
            assertThrows(ServiceException.class, () -> knowledgeBaseService.insertByBo(new AiKnowledgeBaseBo()));
        }
    }

    /**
     * 验证所选模型不是向量模型时抛业务异常。
     */
    @Test
    @DisplayName("非向量模型被拒绝")
    void shouldRejectNonEmbeddingModel() {
        AiKnowledgeBase add = new AiKnowledgeBase();
        add.setKbCode("kb_1");
        add.setEmbeddingModelId(5L);
        AiModelConfig model = embeddingModel(5L, 1024);
        model.setModelType("chat");
        when(modelConfigMapper.selectById(5L)).thenReturn(model);

        try (MockedStatic<MapstructUtils> ms = mockStatic(MapstructUtils.class)) {
            ms.when(() -> MapstructUtils.convert(any(AiKnowledgeBaseBo.class), eq(AiKnowledgeBase.class))).thenReturn(add);
            ServiceException ex = assertThrows(ServiceException.class,
                () -> knowledgeBaseService.insertByBo(new AiKnowledgeBaseBo()));
            assertTrue(ex.getMessage().contains("不是向量模型"));
        }
    }

    /**
     * 验证向量模型不存在时抛业务异常。
     */
    @Test
    @DisplayName("向量模型不存在抛异常")
    void shouldRejectMissingModel() {
        AiKnowledgeBase add = new AiKnowledgeBase();
        add.setKbCode("kb_1");
        add.setEmbeddingModelId(5L);
        when(modelConfigMapper.selectById(5L)).thenReturn(null);
        try (MockedStatic<MapstructUtils> ms = mockStatic(MapstructUtils.class)) {
            ms.when(() -> MapstructUtils.convert(any(AiKnowledgeBaseBo.class), eq(AiKnowledgeBase.class))).thenReturn(add);
            assertThrows(ServiceException.class, () -> knowledgeBaseService.insertByBo(new AiKnowledgeBaseBo()));
        }
    }

    /**
     * 验证向量模型未配置维度时抛业务异常。
     */
    @Test
    @DisplayName("向量模型未配置维度抛异常")
    void shouldRejectMissingDimension() {
        AiKnowledgeBase add = new AiKnowledgeBase();
        add.setKbCode("kb_1");
        add.setEmbeddingModelId(5L);
        when(modelConfigMapper.selectById(5L)).thenReturn(embeddingModel(5L, null));
        try (MockedStatic<MapstructUtils> ms = mockStatic(MapstructUtils.class)) {
            ms.when(() -> MapstructUtils.convert(any(AiKnowledgeBaseBo.class), eq(AiKnowledgeBase.class))).thenReturn(add);
            ServiceException ex = assertThrows(ServiceException.class,
                () -> knowledgeBaseService.insertByBo(new AiKnowledgeBaseBo()));
            assertTrue(ex.getMessage().contains("未配置向量维度"));
        }
    }

    /**
     * 验证表单维度与模型维度不一致时抛业务异常。
     */
    @Test
    @DisplayName("维度不一致抛异常")
    void shouldRejectDimensionMismatch() {
        AiKnowledgeBase add = new AiKnowledgeBase();
        add.setKbCode("kb_1");
        add.setEmbeddingModelId(5L);
        add.setDimension(768);
        when(modelConfigMapper.selectById(5L)).thenReturn(embeddingModel(5L, 1024));

        try (MockedStatic<MapstructUtils> ms = mockStatic(MapstructUtils.class)) {
            ms.when(() -> MapstructUtils.convert(any(AiKnowledgeBaseBo.class), eq(AiKnowledgeBase.class))).thenReturn(add);
            ServiceException ex = assertThrows(ServiceException.class,
                () -> knowledgeBaseService.insertByBo(new AiKnowledgeBaseBo()));
            assertTrue(ex.getMessage().contains("不一致"));
        }
    }

    /**
     * 验证切片重叠不小于切片长度时抛业务异常。
     */
    @Test
    @DisplayName("切片重叠非法抛异常")
    void shouldRejectInvalidChunkOverlap() {
        AiKnowledgeBase add = new AiKnowledgeBase();
        add.setKbCode("kb_1");
        add.setEmbeddingModelId(5L);
        add.setChunkSize(100);
        add.setChunkOverlap(100);
        when(modelConfigMapper.selectById(5L)).thenReturn(embeddingModel(5L, 1024));

        try (MockedStatic<MapstructUtils> ms = mockStatic(MapstructUtils.class)) {
            ms.when(() -> MapstructUtils.convert(any(AiKnowledgeBaseBo.class), eq(AiKnowledgeBase.class))).thenReturn(add);
            ServiceException ex = assertThrows(ServiceException.class,
                () -> knowledgeBaseService.insertByBo(new AiKnowledgeBaseBo()));
            assertTrue(ex.getMessage().contains("重叠"));
        }
    }

    /**
     * 验证「本部门」范围在未指定部门时自动取当前登录部门。
     */
    @Test
    @DisplayName("本部门范围自动落归属部门")
    void shouldFillOwnerDeptForDeptScope() {
        AiKnowledgeBase add = new AiKnowledgeBase();
        add.setKbCode("kb_1");
        add.setEmbeddingModelId(5L);
        add.setVisibleScope("1");
        when(modelConfigMapper.selectById(5L)).thenReturn(embeddingModel(5L, 1024));
        when(baseMapper.selectCount(any())).thenReturn(0L);
        when(baseMapper.insert(any(AiKnowledgeBase.class))).thenReturn(1);

        try (MockedStatic<MapstructUtils> ms = mockStatic(MapstructUtils.class);
             MockedStatic<LoginHelper> login = mockStatic(LoginHelper.class)) {
            ms.when(() -> MapstructUtils.convert(any(AiKnowledgeBaseBo.class), eq(AiKnowledgeBase.class))).thenReturn(add);
            login.when(LoginHelper::getDeptId).thenReturn(9L);

            assertTrue(knowledgeBaseService.insertByBo(new AiKnowledgeBaseBo()));
        }
        assertEquals(9L, add.getOwnerDeptId());
    }

    /**
     * 验证修改目标不存在时抛业务异常。
     */
    @Test
    @DisplayName("修改目标不存在抛异常")
    void shouldThrowWhenUpdateMissing() {
        AiKnowledgeBaseBo bo = new AiKnowledgeBaseBo();
        bo.setId(1L);
        when(baseMapper.selectById(1L)).thenReturn(null);
        assertThrows(ServiceException.class, () -> knowledgeBaseService.updateByBo(bo));
    }

    /**
     * 验证修改成功（无切片数据）。
     */
    @Test
    @DisplayName("修改成功")
    void shouldUpdateSuccessfully() {
        AiKnowledgeBaseBo bo = new AiKnowledgeBaseBo();
        bo.setId(1L);
        AiKnowledgeBase exists = new AiKnowledgeBase();
        exists.setId(1L);
        exists.setKbCode("kb_1");
        exists.setEmbeddingModelId(5L);
        when(baseMapper.selectById(1L)).thenReturn(exists);
        AiKnowledgeBase update = new AiKnowledgeBase();
        update.setKbCode("kb_1");
        update.setEmbeddingModelId(5L);
        when(modelConfigMapper.selectById(5L)).thenReturn(embeddingModel(5L, 1024));
        when(chunkMapper.selectCount(any())).thenReturn(0L);
        when(baseMapper.selectCount(any())).thenReturn(0L);
        when(baseMapper.updateById(any(AiKnowledgeBase.class))).thenReturn(1);

        try (MockedStatic<MapstructUtils> ms = mockStatic(MapstructUtils.class)) {
            ms.when(() -> MapstructUtils.convert(bo, AiKnowledgeBase.class)).thenReturn(update);
            assertTrue(knowledgeBaseService.updateByBo(bo));
        }
    }

    /**
     * 验证已产生切片时不允许修改编码。
     */
    @Test
    @DisplayName("已切片不允许改编码")
    void shouldRejectCodeChangeWhenIndexed() {
        AiKnowledgeBaseBo bo = new AiKnowledgeBaseBo();
        bo.setId(1L);
        AiKnowledgeBase exists = new AiKnowledgeBase();
        exists.setId(1L);
        exists.setKbCode("kb_1");
        exists.setEmbeddingModelId(5L);
        when(baseMapper.selectById(1L)).thenReturn(exists);
        AiKnowledgeBase update = new AiKnowledgeBase();
        update.setKbCode("kb_2");
        update.setEmbeddingModelId(5L);
        when(modelConfigMapper.selectById(5L)).thenReturn(embeddingModel(5L, 1024));
        when(chunkMapper.selectCount(any())).thenReturn(1L);

        try (MockedStatic<MapstructUtils> ms = mockStatic(MapstructUtils.class)) {
            ms.when(() -> MapstructUtils.convert(bo, AiKnowledgeBase.class)).thenReturn(update);
            ServiceException ex = assertThrows(ServiceException.class, () -> knowledgeBaseService.updateByBo(bo));
            assertTrue(ex.getMessage().contains("编码"));
        }
    }

    /**
     * 验证已产生切片时不允许更换向量模型。
     */
    @Test
    @DisplayName("已切片不允许换向量模型")
    void shouldRejectModelChangeWhenIndexed() {
        AiKnowledgeBaseBo bo = new AiKnowledgeBaseBo();
        bo.setId(1L);
        AiKnowledgeBase exists = new AiKnowledgeBase();
        exists.setId(1L);
        exists.setKbCode("kb_1");
        exists.setEmbeddingModelId(5L);
        when(baseMapper.selectById(1L)).thenReturn(exists);
        AiKnowledgeBase update = new AiKnowledgeBase();
        update.setKbCode("kb_1");
        update.setEmbeddingModelId(6L);
        when(modelConfigMapper.selectById(6L)).thenReturn(embeddingModel(6L, 1024));
        when(chunkMapper.selectCount(any())).thenReturn(1L);

        try (MockedStatic<MapstructUtils> ms = mockStatic(MapstructUtils.class)) {
            ms.when(() -> MapstructUtils.convert(bo, AiKnowledgeBase.class)).thenReturn(update);
            ServiceException ex = assertThrows(ServiceException.class, () -> knowledgeBaseService.updateByBo(bo));
            assertTrue(ex.getMessage().contains("向量模型"));
        }
    }

    /**
     * 验证删除时部分主键不存在抛业务异常。
     */
    @Test
    @DisplayName("删除主键缺失抛异常")
    void shouldRejectDeleteWhenIdMismatch() {
        when(baseMapper.selectByIds(any())).thenReturn(List.of(new AiKnowledgeBase()));
        assertThrows(ServiceException.class,
            () -> knowledgeBaseService.deleteWithValidByIds(List.of(1L, 2L), true));
    }

    /**
     * 验证存在文档时不允许删除知识库。
     */
    @Test
    @DisplayName("存在文档不允许删除")
    void shouldRejectDeleteWhenDocExists() {
        when(baseMapper.selectByIds(any())).thenReturn(List.of(new AiKnowledgeBase()));
        when(documentMapper.selectCount(any())).thenReturn(1L);
        ServiceException ex = assertThrows(ServiceException.class,
            () -> knowledgeBaseService.deleteWithValidByIds(List.of(1L), true));
        assertTrue(ex.getMessage().contains("文档"));
    }

    /**
     * 验证跳过校验时直接删除。
     */
    @Test
    @DisplayName("跳过校验直接删除")
    void shouldDeleteWithoutValidation() {
        when(baseMapper.deleteByIds(any())).thenReturn(1);
        assertTrue(knowledgeBaseService.deleteWithValidByIds(List.of(1L), false));
        verify(documentMapper, never()).selectCount(any());
    }

    /**
     * 验证获取启用知识库：不存在、停用分别抛异常，正常返回。
     */
    @Test
    @DisplayName("获取启用知识库")
    void shouldGetEnabledKnowledgeBase() {
        when(baseMapper.selectById(1L)).thenReturn(null);
        assertThrows(ServiceException.class, () -> knowledgeBaseService.getEnabledKnowledgeBase(1L));

        AiKnowledgeBase disabled = new AiKnowledgeBase();
        disabled.setKbName("停用库");
        disabled.setStatus("1");
        when(baseMapper.selectById(2L)).thenReturn(disabled);
        assertThrows(ServiceException.class, () -> knowledgeBaseService.getEnabledKnowledgeBase(2L));

        AiKnowledgeBase normal = new AiKnowledgeBase();
        normal.setStatus("0");
        when(baseMapper.selectById(3L)).thenReturn(normal);
        assertSame(normal, knowledgeBaseService.getEnabledKnowledgeBase(3L));
    }

    /**
     * 验证查询实体：空主键返回 null，否则回查。
     */
    @Test
    @DisplayName("查询实体")
    void shouldQueryEntity() {
        assertNull(knowledgeBaseService.queryEntity(null));
        AiKnowledgeBase entity = new AiKnowledgeBase();
        when(baseMapper.selectById(1L)).thenReturn(entity);
        assertSame(entity, knowledgeBaseService.queryEntity(1L));
    }

    private AiModelConfig embeddingModel(Long id, Integer dimension) {
        AiModelConfig model = new AiModelConfig();
        model.setId(id);
        model.setConfigName("嵌入式" + id);
        model.setModelType("embedding");
        model.setStatus("0");
        model.setDimension(dimension);
        return model;
    }

}