package com.yotto.basketball.repository;

import com.yotto.basketball.entity.ScrapeBatch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ScrapeBatchRepository extends JpaRepository<ScrapeBatch, Long> {

    List<ScrapeBatch> findBySeasonYearOrderByStartedAtDesc(Integer seasonYear);

    List<ScrapeBatch> findByStatus(ScrapeBatch.ScrapeStatus status);

    List<ScrapeBatch> findTop20ByOrderByStartedAtDesc();
}
