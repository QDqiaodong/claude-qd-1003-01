package com.sugar.refinery.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.sugar.refinery.entity.EvapPan;

public interface EvapPanRepository extends JpaRepository<EvapPan, Long> {

    Optional<EvapPan> findByPanCode(String panCode);

    List<EvapPan> findByPanType(String panType);

    List<EvapPan> findByStatus(String status);
}
