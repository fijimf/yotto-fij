package com.yotto.basketball.repository;

import com.yotto.basketball.entity.Conference;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ConferenceRepository extends JpaRepository<Conference, Long> {

    Optional<Conference> findByName(String name);

    Optional<Conference> findByAbbreviation(String abbreviation);

    boolean existsByName(String name);
}
