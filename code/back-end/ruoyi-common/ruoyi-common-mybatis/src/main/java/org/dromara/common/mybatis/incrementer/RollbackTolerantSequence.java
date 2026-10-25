package org.dromara.common.mybatis.incrementer;

import com.baomidou.mybatisplus.core.toolkit.Sequence;
import lombok.extern.slf4j.Slf4j;

import java.net.InetAddress;

/**
 * 容忍时钟回拨的雪花序列（MyBatis-Plus {@link Sequence} 的加固实现）。
 *
 * <p><b>问题背景</b>：父类 {@code nextId()} 只容忍 ≤5ms 的时钟回拨，超出即抛
 * {@code Clock moved backwards. Refusing to generate id for N milliseconds}，导致插入直接失败；
 * 而其时间源是 {@code SystemClock.now()}（即 {@code System.currentTimeMillis()}），
 * 因此在虚拟机时钟被整体步进（Hyper-V / WSL 隐式时间同步、NTP 校正、宿主机休眠唤醒）时会命中，
 * 表现为「偶发一次插入报错、重试即成功」。</p>
 *
 * <p><b>加固口径（逻辑时钟续推）</b>：把「毫秒时间戳」当作<em>只增不减</em>的逻辑时钟——</p>
 * <ul>
 *   <li>墙钟前进：逻辑时钟跟随（与原生行为完全一致）；</li>
 *   <li>墙钟回拨：逻辑时钟<em>原地不动</em>，继续在同一逻辑毫秒内自增序列号（每毫秒可发 4096 个）；</li>
 *   <li>同一逻辑毫秒用尽：向前借 1 毫秒（覆盖 {@link #tilNextMillis(long)}），<em>不阻塞等待</em>真实时钟追平。</li>
 * </ul>
 *
 * <p>由于 {@link #timeGen()} 恒定返回不减的时间戳，父类的回拨分支<em>永不进入</em>：既不抛异常也不阻塞。
 * 64 位布局与 workerId / datacenterId 的推导规则完全沿用父类，故既有 ID 语义与集群唯一性口径不变。</p>
 *
 * <p><b>取舍</b>：回拨期间发放的 ID，其时间戳可能<em>短暂超前</em>墙钟（超前量等于累计回拨量，墙钟追上后自行消解），
 * 此时 {@link Sequence#parseIdTimestamp(long)} 反解出的时间会偏大。</p>
 *
 * @author BizBuddy
 */
@Slf4j
public class RollbackTolerantSequence extends Sequence {

    /**
     * 回拨告警的最小间隔（毫秒），避免回拨持续期间刷日志
     */
    private static final long WARN_INTERVAL_MS = 10_000L;

    /**
     * 逻辑时钟（等于上一次发放的时间戳）：只增不减
     */
    private long logicalTimestamp = -1L;

    /**
     * 上次回拨告警的墙钟毫秒（0 表示尚未告警）
     */
    private long lastWarnAt = 0L;

    /**
     * 按网卡地址推导 workerId / datacenterId（与平台既有口径一致）。
     *
     * @param inetAddress 本机地址
     */
    public RollbackTolerantSequence(InetAddress inetAddress) {
        super(inetAddress);
    }

    /**
     * 显式指定 workerId / datacenterId（供单元测试与特殊部署使用）。
     *
     * @param workerId     机器标识（0~31）
     * @param datacenterId 数据中心标识（0~31）
     */
    public RollbackTolerantSequence(long workerId, long datacenterId) {
        super(workerId, datacenterId);
    }

    /**
     * 取单调不回退的毫秒时间戳：墙钟前进则跟随，回拨则维持逻辑时钟不动。
     *
     * @return 本轮的逻辑毫秒时间戳
     */
    @Override
    protected long timeGen() {
        long now = currentTimeMillis();
        if (now > logicalTimestamp) {
            logicalTimestamp = now;
        } else if (now < logicalTimestamp) {
            warnRollback(now);
        }
        return logicalTimestamp;
    }

    /**
     * 同一逻辑毫秒内序列号用尽时，向前借 1 毫秒（不等待真实时钟，避免父类 {@code while} 自旋与阻塞）。
     *
     * @param lastTimestamp 上一次发放的时间戳
     * @return 新的逻辑毫秒时间戳
     */
    @Override
    protected long tilNextMillis(long lastTimestamp) {
        long next = Math.max(timeGen(), lastTimestamp + 1);
        logicalTimestamp = Math.max(logicalTimestamp, next);
        return next;
    }

    /**
     * 取当前墙钟毫秒。
     *
     * <p>抽为方法是为了让单元测试可以注入「时钟回拨」这一确定性场景（生产实现即系统时钟）。</p>
     *
     * @return 当前墙钟毫秒
     */
    protected long currentTimeMillis() {
        return System.currentTimeMillis();
    }

    /**
     * 记录时钟回拨告警（按 {@link #WARN_INTERVAL_MS} 限频）。
     *
     * @param now 当前墙钟毫秒
     */
    private void warnRollback(long now) {
        if (lastWarnAt != 0L && now - lastWarnAt < WARN_INTERVAL_MS) {
            return;
        }
        lastWarnAt = now;
        log.warn("检测到时钟回拨 {} ms：逻辑时钟维持 {} 不后退，继续发号（不阻塞、不失败）",
            logicalTimestamp - now, logicalTimestamp);
    }

}