package com.yotto.basketball.repository;

import com.yotto.basketball.entity.Team;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TeamRepository extends JpaRepository<Team, Long> {

    Optional<Team> findByNameAndCity(String name, String city);

    List<Team> findByState(String state);

    List<Team> findByNameContainingIgnoreCase(String name);
}
