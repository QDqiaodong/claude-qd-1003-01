package com.sugar.refinery.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.sugar.refinery.entity.EvapPan;
import com.sugar.refinery.entity.WashSchedule;

/**
 * 待洗清单行：罐型 / 当前状态 / 距上次洗罐时长 / 洗罐窗口 / 运行资格 /
 * 未完成安排（含失败可否重试）全部由后端在读取时一次性派生。
 *
 * <p>页面刷新后所有字段都来自同一次查询，前端不做任何规则拼接，
 * 保证「罐型、状态、时长、窗口、运行资格」永远一致。</p>
 */
public record PanOverview(
        Long id,
        String panCode,
        String panName,
        String panType,
        BigDecimal capacityM3,
        String status,
        LocalDateTime lastWashAt,
        /** 距上次洗罐小时数；从未洗过为 null */
        Long hoursSinceWash,
        /** 距上次洗罐超过 72 小时：需先洗罐 */
        boolean washDue,
        /**
         * 运行资格：只有「运行」且未超 72 小时未洗的罐有资格。
         * 完成洗罐时随 lastWashAt 同事务恢复。
         */
        boolean runEligible,
        /** 当前未完成的洗罐安排（待执行 / 清洗中，可能为 null） */
        WashSchedule activeSchedule
) {
    public static PanOverview of(EvapPan p, WashSchedule active) {
        Long hours = p.getHoursSinceWash();
        boolean due = p.isWashDue();
        boolean eligible = "运行".equals(p.getStatus()) && !due;
        return new PanOverview(p.getId(), p.getPanCode(), p.getPanName(), p.getPanType(),
                p.getCapacityM3(), p.getStatus(), p.getLastWashAt(), hours, due, eligible, active);
    }
}
