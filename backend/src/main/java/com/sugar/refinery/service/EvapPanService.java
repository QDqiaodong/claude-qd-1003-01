package com.sugar.refinery.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sugar.refinery.common.BizException;
import com.sugar.refinery.common.Dict;
import com.sugar.refinery.entity.EvapPan;
import com.sugar.refinery.repository.EvapPanRepository;

/**
 * 蒸发 / 结晶罐业务。
 *
 * <p>核心规则：① 罐型只能是蒸发罐或结晶罐，容积必须大于 0；
 * ② 洗罐中的罐不允许进新料（不允许直接转运行，必须先洗完）；
 * ③ 距上次洗罐超过 72 小时不允许继续运行，必须先洗罐。</p>
 */
@Service
public class EvapPanService {

    private final EvapPanRepository panRepository;

    public EvapPanService(EvapPanRepository panRepository) {
        this.panRepository = panRepository;
    }

    public List<EvapPan> list(String panType, String status) {
        if (panType != null && !panType.isBlank()) {
            return panRepository.findByPanType(panType);
        }
        if (status != null && !status.isBlank()) {
            return panRepository.findByStatus(status);
        }
        return panRepository.findAll();
    }

    public EvapPan get(Long id) {
        return panRepository.findById(id)
                .orElseThrow(() -> new BizException("罐体不存在：" + id));
    }

    @Transactional
    public EvapPan create(EvapPan input) {
        if (input.getPanCode() == null || input.getPanCode().isBlank()) {
            throw new BizException("罐号不能为空");
        }
        panRepository.findByPanCode(input.getPanCode().trim()).ifPresent(p -> {
            throw new BizException("罐号已存在：" + input.getPanCode());
        });
        if (!Dict.isValidPanType(input.getPanType())) {
            throw new BizException("罐型不合法：" + input.getPanType());
        }
        if (input.getCapacityM3() == null || input.getCapacityM3().signum() <= 0) {
            throw new BizException("容积必须大于 0");
        }
        EvapPan p = new EvapPan();
        p.setPanCode(input.getPanCode().trim());
        p.setPanName(input.getPanName() == null ? input.getPanCode() : input.getPanName());
        p.setPanType(input.getPanType());
        p.setCapacityM3(input.getCapacityM3());
        p.setStatus(input.getStatus() == null || input.getStatus().isBlank() ? "停用" : input.getStatus());
        if (!Dict.isValidPanStatus(p.getStatus())) {
            throw new BizException("罐体状态不合法：" + p.getStatus());
        }
        p.setLastWashAt(LocalDateTime.now());
        return panRepository.save(p);
    }

    /** 洗罐闭环：记录本次洗罐时间 */
    @Transactional
    public EvapPan wash(Long id) {
        EvapPan p = get(id);
        if ("停用".equals(p.getStatus())) {
            throw new BizException("停用中的罐不能直接洗罐，请先转为洗罐状态");
        }
        p.setLastWashAt(LocalDateTime.now());
        return panRepository.save(p);
    }

    @Transactional
    public EvapPan transfer(Long id, String target) {
        EvapPan p = get(id);
        if (!Dict.isValidPanStatus(target)) {
            throw new BizException("罐体状态不合法：" + target);
        }
        if (!Dict.canTransferPan(p.getStatus(), target)) {
            throw new BizException("不允许从「" + p.getStatus() + "」转为「" + target + "」");
        }
        // 规则③：超 72 小时未洗的罐不允许继续运行
        if ("运行".equals(target) && p.isWashDue()) {
            throw new BizException("距上次洗罐已 " + p.getHoursSinceWash()
                    + " 小时，超过 72 小时必须先洗罐");
        }
        p.setStatus(target);
        return panRepository.save(p);
    }

    /** 在运罐体总容积（立方米） */
    public BigDecimal runningCapacity() {
        BigDecimal sum = BigDecimal.ZERO;
        for (EvapPan p : panRepository.findByStatus("运行")) {
            sum = sum.add(p.getCapacityM3());
        }
        return sum;
    }
}
