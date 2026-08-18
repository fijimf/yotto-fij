package com.yotto.basketball.repository;

import com.yotto.basketball.entity.RatingTuningRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RatingTuningRunRepository extends JpaRepository<RatingTuningRun, Long> {

    List<RatingTuningRun> findByStatus(RatingTuningRun.Status status);

    List<RatingTuningRun> findTop10ByOrderByStartedAtDesc();
}
