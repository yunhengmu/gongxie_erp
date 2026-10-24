package org.dromara.common.mybatis.incrementer;

import com.baomidou.mybatisplus.core.toolkit.Sequence;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RollbackTolerantSequence} 单元测试。
 *
 * <p>覆盖三条关键路径：墙钟正常前进、时钟回拨（父类原生实现会抛异常的区间）、
 * 以及同一逻辑毫秒内序列号用尽后的「向未来借 1 毫秒」。</p>
 *
 * @author BizBuddy
 */
@DisplayName("RollbackTolerantSequence 单元测试")
class RollbackTolerantSequenceTest {

    /**
     * 单个逻辑毫秒可发放的最大序列号数（与雪花 sequenceBits=12 一致）
     */
    private static final int SEQUENCE_PER_MILLIS = 4096;

    /**
     * 可控时钟的序列：以原子变量模拟墙钟，便于注入回拨场景。
     */
    private static final class FakeClockSequence extends RollbackTolerantSequence {

        private final AtomicLong clock;

        private FakeClockSequence(long initialMillis) {
            // workerId / datacenterId 固定，避免用例依赖本机网卡
            super(1L, 1L);
            this.clock = new AtomicLong(initialMillis);
        }

        @Override
        protected long currentTimeMillis() {
            return clock.get();
        }

        private void setClock(long millis) {
            clock.set(millis);
        }
    }

    @Test
    @DisplayName("墙钟正常前进：ID 严格递增且不重复")
    void shouldGenerateIncreasingUniqueIdsWhenClockAdvances() {
        FakeClockSequence sequence = new FakeClockSequence(1_700_000_000_000L);
        Set<Long> ids = new HashSet<>();
        long previous = -1L;

        for (int i = 0; i < 20_000; i++) {
            // 每 10 个 ID 让墙钟前进 1 毫秒
            if (i % 10 == 0) {
                sequence.setClock(1_700_000_000_000L + i / 10);
            }
            long id = sequence.nextId();
            assertTrue(id > previous, "ID 应严格递增，index=" + i);
            assertTrue(ids.add(id), "ID 不应重复：" + id);
            previous = id;
        }
        assertEquals(20_000, ids.size());
    }

    @Test
    @DisplayName("时钟回拨：不抛异常、ID 不重复、逻辑时间戳不倒退")
    void shouldTolerateClockRollback() {
        FakeClockSequence sequence = new FakeClockSequence(1_700_000_000_000L);
        for (int i = 0; i < 100; i++) {
            sequence.nextId();
        }
        long baseline = Sequence.parseIdTimestamp(sequence.nextId());

        // 回拨 465 毫秒（正是生产实测到的回拨量），父类原生实现会在此抛 RuntimeException
        sequence.setClock(1_700_000_000_000L - 465);

        Set<Long> ids = new HashSet<>();
        long previousTimestamp = baseline;
        // 父类原生实现在此处会抛「Clock moved backwards. Refusing to generate id for 465 milliseconds」，
        // 本实现应正常发号：任何异常都会直接判定用例失败
        for (int i = 0; i < 10_000; i++) {
            long id = sequence.nextId();
            long timestamp = Sequence.parseIdTimestamp(id);
            assertTrue(timestamp >= previousTimestamp, "逻辑时间戳不应倒退，index=" + i);
            assertTrue(ids.add(id), "回拨期间 ID 不应重复：" + id);
            previousTimestamp = timestamp;
        }
        assertEquals(10_000, ids.size());
    }

    @Test
    @DisplayName("同一逻辑毫秒序列号用尽：向未来借毫秒，不阻塞且 ID 仍唯一递增")
    void shouldBorrowNextMillisWhenSequenceExhausted() {
        long frozen = 1_700_000_000_000L;
        FakeClockSequence sequence = new FakeClockSequence(frozen);
        Set<Long> ids = new HashSet<>();
        long previous = -1L;
        int total = SEQUENCE_PER_MILLIS * 3;

        for (int i = 0; i < total; i++) {
            long id = sequence.nextId();
            assertTrue(id > previous, "ID 应严格递增，index=" + i);
            assertTrue(ids.add(id), "ID 不应重复：" + id);
            previous = id;
        }

        assertEquals(total, ids.size());
        // 墙钟冻结时，3*4096 个 ID 只会向前借几个逻辑毫秒（首个毫秒的起始序列号是随机的 1~2）
        long borrowed = Sequence.parseIdTimestamp(previous) - frozen;
        assertTrue(borrowed >= 0 && borrowed <= 4, "借用毫秒数应有限，实际=" + borrowed);
    }

    @Test
    @DisplayName("墙钟反复来回摆动：10 万个 ID 全部唯一")
    void shouldKeepUniqueIdsAcrossRepeatedClockSwings() {
        long base = 1_700_000_000_000L;
        FakeClockSequence sequence = new FakeClockSequence(base);
        Set<Long> ids = new HashSet<>();

        for (int i = 0; i < 100_000; i++) {
            if (i % 1_000 == 0) {
                // 在 ±800 毫秒区间内来回摆动，覆盖前进与回拨两个方向
                sequence.setClock(i % 2_000 == 0 ? base - 800 : base + 800);
            }
            assertTrue(ids.add(sequence.nextId()), "ID 不应重复，index=" + i);
        }
        assertEquals(100_000, ids.size());
    }

}