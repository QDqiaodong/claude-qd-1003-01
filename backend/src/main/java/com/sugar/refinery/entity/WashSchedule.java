package com.sugar.refinery.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.Getter;
import lombok.Setter;

/**
 * 洗罐安排（蒸发罐 / 结晶罐洗罐排程单）。
 *
 * <p>值班员为到期的罐安排洗罐窗口，安排按下述闭环流转：
 * 待执行 → 清洗中 → 已完成；待执行可以取消（已取消）。</p>
 *
 * <p>关键约束：</p>
 * <ul>
 *   <li>同一罐同时只允许一条未完成安排（待执行 / 清洗中）——由
 *       {@code active_pan_key} 生成列 + 唯一索引在库层兜底；</li>
 *   <li>清洗中不能重复开始、不能改换罐型（罐型在排程时快照到 pan_type）；</li>
 *   <li>清洗失败不新增状态：仍停在「清洗中」，只写失败原因与末次尝试时间，
 *       罐停在「洗罐」态且 last_wash_at 不动，可安全重试；</li>
 *   <li>完成时同事务写完成时间、刷新罐 last_wash_at 并恢复运行资格。</li>
 * </ul>
 */
@Entity
@Table(name = "wash_schedule")
@Getter
@Setter
public class WashSchedule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 洗罐安排号，唯一 */
    @Column(name = "schedule_no", nullable = false, unique = true, length = 32)
    private String scheduleNo;

    /** 幂等键：一次「安排洗罐」意图只落一条（重复点击 / 超时重试共用同一键） */
    @Column(name = "request_id", nullable = false, unique = true, length = 36)
    private String requestId;

    /** 目标罐 */
    @Column(name = "pan_id", nullable = false)
    private Long panId;

    /** 罐号快照 */
    @Column(name = "pan_code", nullable = false, length = 24)
    private String panCode;

    /** 罐名快照 */
    @Column(name = "pan_name", nullable = false, length = 64)
    private String panName;

    /** 罐型快照：清洗中不允许改换罐型 */
    @Column(name = "pan_type", nullable = false, length = 16)
    private String panType;

    /** 排程的洗罐窗口开始时间 */
    @Column(name = "window_start", nullable = false)
    private LocalDateTime windowStart;

    /** 排程的洗罐窗口结束时间（必须晚于开始） */
    @Column(name = "window_end", nullable = false)
    private LocalDateTime windowEnd;

    /** 待执行 / 清洗中 / 已完成 / 已取消 */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    /** 最近一次开始清洗的时间（失败重试不覆盖首次值，便于区分重试场景） */
    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    /** 末次尝试时间：开始 / 每次重试都会刷新 */
    @Column(name = "last_attempt_at")
    private LocalDateTime lastAttemptAt;

    /** 清洗失败原因；非空且状态仍是清洗中，表示等待重试 */
    @Column(name = "failure_reason", length = 255)
    private String failureReason;

    /** 清洗失败次数（含首次失败与历次重试失败） */
    @Column(name = "fail_count", nullable = false)
    private Integer failCount = 0;

    /**
     * 未完成安排的活动键：未完成（待执行 / 清洗中）时等于 panId，
     * 完成 / 取消后为 NULL。库里对该列建唯一索引，从库层保证
     * 「同一罐不能同时存在两个未完成的洗罐安排」。
     */
    @Column(name = "active_pan_key", insertable = false, updatable = false)
    private Long activePanKey;

    /** 是否清洗失败（停在清洗中等重试），派生字段 */
    @Transient
    public boolean isWashFailed() {
        return "清洗中".equals(status) && failureReason != null && !failureReason.isBlank();
    }
}
