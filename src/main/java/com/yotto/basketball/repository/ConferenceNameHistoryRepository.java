package com.yotto.basketball.repository;

import com.yotto.basketball.entity.ConferenceNameHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ConferenceNameHistoryRepository extends JpaRepository<ConferenceNameHistory, Long> {

    List<ConferenceNameHistory> findByConferenceIdOrderByLastSeasonYearAsc(Long conferenceId);
}
