package com.yotto.basketball.repository;

import com.yotto.basketball.entity.StatCalcWatermark;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface StatCalcWatermarkRepository extends JpaRepository<StatCalcWatermark, Long> {

    @Query("SELECT w FROM StatCalcWatermark w WHERE w.season.id = :seasonId")
    Optional<StatCalcWatermark> findBySeasonId(@Param("seasonId") Long seasonId);
}
