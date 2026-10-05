package com.sugar.refinery.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sugar.refinery.common.BizException;
import com.sugar.refinery.common.Dict;
import com.sugar.refinery.dto.PanOverview;
import com.sugar.refinery.entity.EvapPan;
import com.sugar.refinery.entity.WashSchedule;
import com.sugar.refinery.repository.EvapPanRepository;
import com.sugar.refinery.repository.WashScheduleRepository;

/**
 * 蒸发 / 结晶罐洗罐排程业务（洗罐闭环）。
 *
 * <p>核心规则：</p>
 * <ol>
 *   <li>距上次洗罐超过 {@link Dict#PAN_WASH_DUE_HOURS} 小时的罐标为「需先洗罐」；
 *       停用中的罐不能直接进入洗罐——允许先排窗口，但只能在排程闭环里「开始清洗」，
 *       不能走手工状态流转直接转洗罐；</li>
 *   <li>同一罐同时只允许一条未完成安排（待执行 / 清洗中）：业务层先查，
 *       active_pan_key 生成列 + 唯一索引在库层兜底；</li>
 *   <li>状态机：待执行 → 清洗中 → 已完成；待执行可以取消，清洗中不能重复开始、
 *       不能改换罐型（罐型排程时快照）；</li>
 *   <li>清洗失败不新增状态：安排仍停在「清洗中」，只写失败原因 / 末次尝试时间，
 *       罐停在「洗罐」、last_wash_at 不动，可安全重试；</li>
 *   <li>完成在同一事务写完成时间 + 刷罐 last_wash_at + 罐回运行（恢复运行资格），
 *       任一步失败整体回滚，不会出现只改状态没有洗罐时间。</li>
 * </ol>
 */
@Service
public class WashScheduleService {

    /** 所有写路径都是单事务，失败即整体回滚，因此任何失败都可安全重试 */
    private static final String SAFE_RETRY = "；本次未落下任何数据，可安全重试";

    private final WashScheduleRepository washRepository;
    private final EvapPanRepository panRepository;

    public WashScheduleService(WashScheduleRepository washRepository,
                               EvapPanRepository panRepository) {
        this.washRepository = washRepository;
        this.panRepository = panRepository;
    }

    // ---------------- 待洗清单 ----------------

    /**
     * 待洗清单：罐型、当前状态、距上次洗罐时长、洗罐窗口、运行资格与未完成安排
     * （含失败可否重试）全部在此一次派生，刷新页面后各处显示天然一致。
     */
    public List<PanOverview> overview(String panType, String status) {
        List<EvapPan> pans = panRepository.findAll();
        var openMap = new java.util.HashMap<Long, WashSchedule>();
        for (WashSchedule w : washRepository.findOpenAll()) {
            openMap.put(w.getPanId(), w);
        }
        return pans.stream()
                .filter(p -> panType == null || panType.isBlank() || panType.equals(p.getPanType()))
                .filter(p -> status == null || status.isBlank() || status.equals(p.getStatus()))
                .map(p -> PanOverview.of(p, openMap.get(p.getId())))
                .toList();
    }

    public List<WashSchedule> list(String status) {
        if (status != null && !status.isBlank()) {
            return washRepository.findByStatusOrderByIdDesc(status);
        }
        return washRepository.findAllByOrderByIdDesc();
    }

    public Optional<WashSchedule> findByRequestId(String requestId) {
        return washRepository.findByRequestId(requestId);
    }

    public WashSchedule get(Long id) {
        return washRepository.findById(id)
                .orElseThrow(() -> new BizException("洗罐安排不存在：" + id));
    }

    // ---------------- 排程 ----------------

    /**
     * 安排洗罐窗口：锁罐 → 校验罐型 / 未完成安排 → 落待执行安排。
     * 停用罐允许先排窗口（进入洗罐只能由随后的「开始清洗」在闭环里完成）。
     * 同一 requestId 只落一条（重复点击 / 超时重试幂等）。
     */
    @Transactional
    public WashSchedule schedule(Long panId, String panType, LocalDateTime windowStart,
                                 LocalDateTime windowEnd, String requestId) {
        if (panId == null) {
            throw new BizException("请选择要安排洗罐的罐");
        }
        if (requestId == null || requestId.isBlank()) {
            throw new BizException("缺少请求标识，请刷新页面后重试");
        }
        if (!Dict.isValidPanType(panType)) {
            throw new BizException("罐型不合法：" + panType);
        }
        if (windowStart == null || windowEnd == null) {
            throw new BizException("请填写洗罐窗口的开始与结束时间");
        }
        if (!windowEnd.isAfter(windowStart)) {
            throw new BizException("洗罐窗口结束时间必须晚于开始时间");
        }

        // 幂等：同一 requestId 只落一条，内容不一致时不静默合并
        Optional<WashSchedule> dup = washRepository.findByRequestId(requestId.trim());
        if (dup.isPresent()) {
            WashSchedule existed = dup.get();
            if (!existed.getPanId().equals(panId)
                    || !existed.getWindowStart().equals(windowStart)
                    || !existed.getWindowEnd().equals(windowEnd)) {
                throw new BizException("该排程请求已受理过（洗罐安排 " + existed.getScheduleNo()
                        + "），但本次内容不一致，请刷新页面核对后再操作");
            }
            return existed;
        }

        EvapPan pan = lockPan(panId);
        if (!pan.getPanType().equals(panType)) {
            throw new BizException("罐型与罐体档案不一致，洗罐排程不能改换罐型" + SAFE_RETRY);
        }
        List<WashSchedule> open = washRepository.findOpenByPanId(panId);
        if (!open.isEmpty()) {
            WashSchedule w = open.get(0);
            throw new BizException(pan.getPanName() + " 已存在未完成的洗罐安排 " + w.getScheduleNo()
                    + "（" + w.getStatus() + "），同一罐不能同时安排两次洗罐" + SAFE_RETRY);
        }

        WashSchedule schedule = new WashSchedule();
        schedule.setScheduleNo(nextScheduleNo());
        schedule.setRequestId(requestId.trim());
        schedule.setPanId(pan.getId());
        schedule.setPanCode(pan.getPanCode());
        schedule.setPanName(pan.getPanName());
        schedule.setPanType(pan.getPanType());
        schedule.setWindowStart(windowStart);
        schedule.setWindowEnd(windowEnd);
        schedule.setStatus("待执行");
        schedule.setFailCount(0);
        schedule.setCreatedAt(LocalDateTime.now());
        // saveAndFlush：让唯一索引冲突在事务内立即抛出并整体回滚，不留半成品
        return washRepository.saveAndFlush(schedule);
    }

    // ---------------- 状态流转 ----------------

    /** 开始清洗：待执行 → 清洗中；同事务把罐置为「洗罐」 */
    @Transactional
    public WashSchedule start(Long id) {
        WashSchedule schedule = lockSchedule(id);
        if ("清洗中".equals(schedule.getStatus())) {
            throw new BizException(schedule.getPanName() + " 的洗罐已在清洗中，不能重复开始"
                    + (schedule.isWashFailed() ? "；上次清洗失败，请直接重试" : ""));
        }
        if (!"待执行".equals(schedule.getStatus())) {
            throw new BizException("只有「待执行」的洗罐安排能开始清洗，当前状态：" + schedule.getStatus() + SAFE_RETRY);
        }
        EvapPan pan = lockPan(schedule.getPanId());
        assertSamePanType(pan, schedule);
        // 停用罐不能「直接」进入洗罐：手工状态流转已被 transfer 拦截，
        // 这里只放行排程闭环自己的开始动作（运行 / 停用 → 洗罐）
        if ("洗罐".equals(pan.getStatus())) {
            throw new BizException(pan.getPanName() + " 已在洗罐态，不能重复开始" + SAFE_RETRY);
        }

        LocalDateTime now = LocalDateTime.now();
        schedule.setStatus("清洗中");
        schedule.setStartedAt(now);
        schedule.setLastAttemptAt(now);
        washRepository.save(schedule);

        pan.setStatus("洗罐");
        panRepository.save(pan);
        return schedule;
    }

    /**
     * 清洗失败后重试：安排仍停在「清洗中」，只刷新末次尝试时间、清掉上次失败标记。
     * 罐保持「洗罐」、last_wash_at 不变，因此重试是安全的。
     */
    @Transactional
    public WashSchedule retry(Long id) {
        WashSchedule schedule = lockSchedule(id);
        if (!"清洗中".equals(schedule.getStatus())) {
            throw new BizException("只有清洗失败、停在「清洗中」的安排能重试，当前状态："
                    + schedule.getStatus() + SAFE_RETRY);
        }
        if (!schedule.isWashFailed()) {
            throw new BizException(schedule.getScheduleNo() + " 已在清洗中，无需重复开始" + SAFE_RETRY);
        }
        EvapPan pan = lockPan(schedule.getPanId());
        assertSamePanType(pan, schedule);
        if (!"洗罐".equals(pan.getStatus())) {
            // 正常路径不会发生：罐被人工挪走说明状态已不一致，拒绝并提示刷新
            throw new BizException(pan.getPanName() + " 当前为「" + pan.getStatus()
                    + "」态，与清洗中安排不一致，请刷新页面核对" + SAFE_RETRY);
        }

        // 只刷新末次尝试时间、清除上次失败标记；不允许只改状态没有洗罐时间
        schedule.setLastAttemptAt(LocalDateTime.now());
        schedule.setFailureReason(null);
        return washRepository.save(schedule);
    }

    /**
     * 完成 / 失败回执：
     * 失败 → 安排停在「清洗中」，记失败原因与次数，罐不动（可安全重试）；
     * 成功 → 同事务写完成时间、刷罐 last_wash_at、罐回运行（恢复运行资格）。
     */
    @Transactional
    public WashSchedule complete(Long id, boolean failed, String reason) {
        WashSchedule schedule = lockSchedule(id);
        if (!"清洗中".equals(schedule.getStatus())) {
            throw new BizException("只有「清洗中」的洗罐安排能完成，当前状态：" + schedule.getStatus() + SAFE_RETRY);
        }
        // 防线：必须真的开始过（有洗罐时间）才允许回执，不能只改状态
        if (schedule.getStartedAt() == null || schedule.getLastAttemptAt() == null) {
            throw new BizException("该安排尚未开始清洗，缺少洗罐时间，不能直接完成" + SAFE_RETRY);
        }
        EvapPan pan = lockPan(schedule.getPanId());
        assertSamePanType(pan, schedule);
        if (!"洗罐".equals(pan.getStatus())) {
            throw new BizException(pan.getPanName() + " 当前为「" + pan.getStatus()
                    + "」态，与清洗中安排不一致，请刷新页面核对" + SAFE_RETRY);
        }

        if (failed) {
            // 失败：已完成的其他记录不受影响；本安排停在清洗中，罐停在洗罐，可安全重试
            schedule.setFailCount((schedule.getFailCount() == null ? 0 : schedule.getFailCount()) + 1);
            schedule.setFailureReason((reason == null || reason.isBlank())
                    ? "清洗验收未通过" : reason.trim());
            return washRepository.save(schedule);
        }

        // 成功：三件事同一事务 —— 安排完成时间 + 罐上次洗罐时间 + 罐恢复运行资格
        LocalDateTime now = LocalDateTime.now();
        schedule.setStatus("已完成");
        schedule.setCompletedAt(now);
        schedule.setFailureReason(null);
        washRepository.save(schedule);

        pan.setLastWashAt(now);
        pan.setStatus("运行");
        panRepository.save(pan);
        return schedule;
    }

    /** 取消：仅待执行可取消；清洗中（含失败）不可取消，只能重试或完成 */
    @Transactional
    public WashSchedule cancel(Long id) {
        WashSchedule schedule = lockSchedule(id);
        if (!Dict.canTransferWash(schedule.getStatus(), "已取消")) {
            throw new BizException("只有「待执行」的洗罐安排能取消，当前状态：" + schedule.getStatus() + SAFE_RETRY);
        }
        schedule.setStatus("已取消");
        schedule.setCancelledAt(LocalDateTime.now());
        return washRepository.save(schedule);
    }

    // ---------------- 内部 ----------------

    private WashSchedule lockSchedule(Long id) {
        return washRepository.lockById(id)
                .orElseThrow(() -> new BizException("洗罐安排不存在：" + id + SAFE_RETRY));
    }

    private EvapPan lockPan(Long id) {
        return panRepository.lockById(id)
                .orElseThrow(() -> new BizException("罐体不存在：" + id + SAFE_RETRY));
    }

    /** 清洗中不能改换罐型：排程快照罐型必须与档案一致 */
    private void assertSamePanType(EvapPan pan, WashSchedule schedule) {
        if (!pan.getPanType().equals(schedule.getPanType())) {
            throw new BizException(pan.getPanName() + " 罐型与排程快照不一致，清洗中不能改换罐型" + SAFE_RETRY);
        }
    }

    private String nextScheduleNo() {
        return "WS-" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
    }
}
