package com.yotto.basketball.repository;

import com.yotto.basketball.entity.MlTrainingRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MlTrainingRunRepository extends JpaRepository<MlTrainingRun, Long> {

    List<MlTrainingRun> findByStatus(MlTrainingRun.Status status);

    List<MlTrainingRun> findTop5ByOrderByStartedAtDesc();

    boolean existsByStatus(MlTrainingRun.Status status);
}
