package com.sugar.refinery.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.sugar.refinery.entity.WashSchedule;

import jakarta.persistence.LockModeType;

public interface WashScheduleRepository extends JpaRepository<WashSchedule, Long> {

    Optional<WashSchedule> findByScheduleNo(String scheduleNo);

    Optional<WashSchedule> findByRequestId(String requestId);

    List<WashSchedule> findByStatusOrderByIdDesc(String status);

    List<WashSchedule> findAllByOrderByIdDesc();

    List<WashSchedule> findByPanIdOrderByIdDesc(Long panId);

    /** 该罐当前未完成（待执行 / 清洗中）的洗罐安排 */
    @Query("select w from WashSchedule w where w.panId = :panId and w.status in ('待执行', '清洗中')")
    List<WashSchedule> findOpenByPanId(@Param("panId") Long panId);

    /** 全部未完成安排，按罐分组后用于待洗清单 */
    @Query("select w from WashSchedule w where w.status in ('待执行', '清洗中') order by w.id desc")
    List<WashSchedule> findOpenAll();

    /** 行锁（SELECT ... FOR UPDATE）：开始 / 重试 / 完成 / 取消时串行化同一安排的并发操作 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from WashSchedule w where w.id = :id")
    Optional<WashSchedule> lockById(@Param("id") Long id);
}
