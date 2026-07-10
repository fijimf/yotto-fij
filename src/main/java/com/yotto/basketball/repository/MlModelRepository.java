package com.yotto.basketball.repository;

import com.yotto.basketball.entity.MlModel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MlModelRepository extends JpaRepository<MlModel, Long> {

    Optional<MlModel> findBySlug(String slug);

    List<MlModel> findByStatusNot(MlModel.Status status);

    Optional<MlModel> findByIsDefaultTrue();

    List<MlModel> findAllByOrderBySlug();

    long countByStatusNot(MlModel.Status status);
}
