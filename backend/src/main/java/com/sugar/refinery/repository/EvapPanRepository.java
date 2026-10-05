package com.sugar.refinery.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.sugar.refinery.entity.EvapPan;

import jakarta.persistence.LockModeType;

public interface EvapPanRepository extends JpaRepository<EvapPan, Long> {

    Optional<EvapPan> findByPanCode(String panCode);

    List<EvapPan> findByPanType(String panType);

    List<EvapPan> findByStatus(String status);

    /** 行锁（SELECT ... FOR UPDATE）：安排 / 开始 / 完成洗罐时锁住罐行，串行化并发操作 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from EvapPan p where p.id = :id")
    Optional<EvapPan> lockById(@Param("id") Long id);
}
