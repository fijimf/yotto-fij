package com.yotto.basketball.repository;

import com.yotto.basketball.entity.PowerModelParamSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

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
}
