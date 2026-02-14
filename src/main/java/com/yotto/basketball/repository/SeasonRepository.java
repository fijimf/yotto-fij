package com.yotto.basketball.repository;

import com.yotto.basketball.entity.Season;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface SeasonRepository extends JpaRepository<Season, Long> {

    Optional<Season> findByYear(Integer year);

    boolean existsByYear(Integer year);
}
