package com.yotto.basketball.repository;

import com.yotto.basketball.entity.PowerModelParamSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface PowerModelParamSnapshotRepository extends JpaRepository<PowerModelParamSnapshot, Long> {

    @Query("SELECT p FROM PowerModelParamSnapshot p " +
           "WHERE p.season.id = :seasonId AND p.modelType = :modelType " +
           "ORDER BY p.snapshotDate ASC")
    List<PowerModelParamSnapshot> findBySeasonAndModel(
            @Param("seasonId") Long seasonId,
            @Param("modelType") String modelType);

    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM PowerModelParamSnapshot p WHERE p.season.id = :seasonId AND p.modelType = :modelType")
    void deleteBySeasonIdAndModelType(
            @Param("seasonId") Long seasonId,
            @Param("modelType") String modelType);

    /** Most recent param value for a season/model/paramName strictly before the given date. */
    @Query(value = "SELECT * FROM power_model_param_snapshots " +
                   "WHERE season_id = :seasonId AND model_type = :modelType " +
                   "  AND param_name = :paramName AND snapshot_date < :beforeDate " +
                   "ORDER BY snapshot_date DESC LIMIT 1",
           nativeQuery = true)
    Optional<PowerModelParamSnapshot> findLatestParamBefore(
            @Param("seasonId") Long seasonId,
            @Param("modelType") String modelType,
            @Param("paramName") String paramName,
            @Param("beforeDate") LocalDate beforeDate);
}
