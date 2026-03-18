package com.yotto.basketball.repository;

import com.yotto.basketball.entity.Season;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Optional;

@Repository
public interface SeasonRepository extends JpaRepository<Season, Long> {

    Optional<Season> findByYear(Integer year);

    boolean existsByYear(Integer year);

    Optional<Season> findTopByOrderByYearDesc();

    @Query("SELECT s FROM Season s WHERE s.startDate <= :date AND s.endDate >= :date")
    Optional<Season> findByDate(@Param("date") LocalDate date);
}
