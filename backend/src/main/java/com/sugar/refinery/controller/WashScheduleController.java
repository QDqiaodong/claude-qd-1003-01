package com.sugar.refinery.controller;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.sugar.refinery.common.ApiResponse;
import com.sugar.refinery.dto.PanOverview;
import com.sugar.refinery.entity.WashSchedule;
import com.sugar.refinery.service.WashScheduleService;

/** 洗罐排程接口：待洗清单 → 安排窗口 → 待执行 → 清洗中 → 已完成 的闭环 */
@RestController
@RequestMapping("/api/wash-schedules")
public class WashScheduleController {

    private final WashScheduleService washService;

    public WashScheduleController(WashScheduleService washService) {
        this.washService = washService;
    }

    /** 排程请求：requestId 是幂等键，重复点击 / 超时重试必须带同一个 */
    public record ScheduleRequest(Long panId, String panType,
                                  LocalDateTime windowStart,
                                  LocalDateTime windowEnd,
                                  String requestId) {
    }

    /** 完成回执：failed=true 表示本次清洗验收失败（安排停在清洗中，可安全重试） */
    public record CompleteRequest(Boolean failed, String reason) {
    }

    /** 待洗清单：罐型 / 状态 / 距上次洗罐时长 / 窗口 / 运行资格一次派齐 */
    @GetMapping("/overview")
    public ApiResponse<List<PanOverview>> overview(@RequestParam(required = false) String panType,
                                                   @RequestParam(required = false) String status) {
        return ApiResponse.success(washService.overview(panType, status));
    }

    @GetMapping
    public ApiResponse<List<WashSchedule>> list(@RequestParam(required = false) String status) {
        return ApiResponse.success(washService.list(status));
    }

    @GetMapping("/{id}")
    public ApiResponse<WashSchedule> get(@PathVariable Long id) {
        return ApiResponse.success(washService.get(id));
    }

    /** 安排洗罐窗口 */
    @PostMapping
    public ApiResponse<WashSchedule> schedule(@RequestBody ScheduleRequest req) {
        try {
            return ApiResponse.success("洗罐窗口已安排",
                    washService.schedule(req.panId(), req.panType(), req.windowStart(),
                            req.windowEnd(), req.requestId()));
        } catch (DataIntegrityViolationException e) {
            // 并发下同一罐的第二条未完成安排撞上 active_pan_key 唯一索引：
            // 本事务已整体回滚（不留半成品），若同 requestId 已落库则幂等返回
            Optional<WashSchedule> existing = req.requestId() == null ? Optional.empty()
                    : washService.findByRequestId(req.requestId());
            if (existing.isPresent()) {
                return ApiResponse.success("洗罐窗口已安排（重复请求已合并）", existing.get());
            }
            throw e;
        }
    }

    @PostMapping("/{id}/start")
    public ApiResponse<WashSchedule> start(@PathVariable Long id) {
        return ApiResponse.success("清洗已开始", washService.start(id));
    }

    /** 清洗失败后重试（仍停在清洗中，只刷新末次尝试时间） */
    @PostMapping("/{id}/retry")
    public ApiResponse<WashSchedule> retry(@PathVariable Long id) {
        return ApiResponse.success("已重新开始清洗，可再次回执", washService.retry(id));
    }

    /** 完成 / 失败回执；成功时同事务写完成时间并恢复罐的运行资格 */
    @PostMapping("/{id}/complete")
    public ApiResponse<WashSchedule> complete(@PathVariable Long id, @RequestBody(required = false) CompleteRequest req) {
        boolean failed = req != null && Boolean.TRUE.equals(req.failed());
        String reason = req == null ? null : req.reason();
        WashSchedule result = washService.complete(id, failed, reason);
        String message = failed
                ? "本次清洗未通过，安排停在清洗中，可安全重试"
                : "洗罐完成，完成时间已记录，罐体已恢复运行资格";
        return ApiResponse.success(message, result);
    }

    @PostMapping("/{id}/cancel")
    public ApiResponse<WashSchedule> cancel(@PathVariable Long id) {
        return ApiResponse.success("洗罐安排已取消", washService.cancel(id));
    }
}
