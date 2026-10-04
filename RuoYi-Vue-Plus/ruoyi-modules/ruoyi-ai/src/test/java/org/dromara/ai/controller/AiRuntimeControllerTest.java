package org.dromara.ai.controller;

import org.dromara.ai.core.agent.AiRuntimeConfigStore;
import org.dromara.ai.domain.bo.AiCompactionConfigBo;
import org.dromara.ai.domain.bo.AiPlanConfigBo;
import org.dromara.ai.test.AiTestSupport;
import org.dromara.common.core.domain.R;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AiRuntimeController} 单元测试：压缩 / 计划配置的读写转发。
 */
@DisplayName("AI 运行期策略 Controller")
class AiRuntimeControllerTest {

    private final AiRuntimeConfigStore store = mock(AiRuntimeConfigStore.class);

    private final AiRuntimeController controller = new AiRuntimeController(store);

    @BeforeAll
    static void beforeAll() {
        AiTestSupport.initialize();
    }

    @Test
    @DisplayName("查询上下文压缩策略返回配置")
    void shouldGetCompaction() {
        AiCompactionConfigBo bo = new AiCompactionConfigBo();
        when(store.compaction()).thenReturn(bo);
        R<AiCompactionConfigBo> r = controller.getCompaction();
        assertTrue(R.isSuccess(r));
        assertSame(bo, r.getData());
    }

    @Test
    @DisplayName("保存上下文压缩策略转发到配置中心")
    void shouldUpdateCompaction() {
        AiCompactionConfigBo bo = new AiCompactionConfigBo();
        R<Void> r = controller.updateCompaction(bo);
        assertTrue(R.isSuccess(r));
        verify(store).updateCompaction(bo);
    }

    @Test
    @DisplayName("查询计划模式配置返回配置")
    void shouldGetPlan() {
        AiPlanConfigBo bo = new AiPlanConfigBo();
        when(store.plan()).thenReturn(bo);
        R<AiPlanConfigBo> r = controller.getPlan();
        assertTrue(R.isSuccess(r));
        assertSame(bo, r.getData());
    }

    @Test
    @DisplayName("保存计划模式配置转发到配置中心")
    void shouldUpdatePlan() {
        AiPlanConfigBo bo = new AiPlanConfigBo();
        R<Void> r = controller.updatePlan(bo);
        assertTrue(R.isSuccess(r));
        verify(store).updatePlan(bo);
    }

}