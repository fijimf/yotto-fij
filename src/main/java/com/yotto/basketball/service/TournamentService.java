package com.yotto.basketball.service;

import com.yotto.basketball.entity.Season;
import com.yotto.basketball.entity.Tournament;
import com.yotto.basketball.repository.TournamentRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional
public class TournamentService {

    private final TournamentRepository tournamentRepository;
    private final SeasonService seasonService;

    public TournamentService(TournamentRepository tournamentRepository, SeasonService seasonService) {
        this.tournamentRepository = tournamentRepository;
        this.seasonService = seasonService;
    }

    public Tournament create(Tournament tournament, Long seasonId) {
        Season season = seasonService.findById(seasonId);
        tournament.setSeason(season);
        return tournamentRepository.save(tournament);
    }

    @Transactional(readOnly = true)
    public Tournament findById(Long id) {
        return tournamentRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Tournament not found with id: " + id));
    }

    @Transactional(readOnly = true)
    public List<Tournament> findAll() {
        return tournamentRepository.findAll();
    }

    @Transactional(readOnly = true)
    public List<Tournament> findBySeason(Long seasonId) {
        return tournamentRepository.findBySeasonId(seasonId);
    }

    @Transactional(readOnly = true)
    public List<Tournament> findByType(Tournament.TournamentType type) {
        return tournamentRepository.findByType(type);
    }

    public Tournament update(Long id, Tournament tournament) {
        Tournament existing = findById(id);
        existing.setName(tournament.getName());
        existing.setLocation(tournament.getLocation());
        existing.setStartDate(tournament.getStartDate());
        existing.setEndDate(tournament.getEndDate());
        existing.setType(tournament.getType());
        return tournamentRepository.save(existing);
    }

    public void delete(Long id) {
        if (!tournamentRepository.existsById(id)) {
            throw new EntityNotFoundException("Tournament not found with id: " + id);
        }
        tournamentRepository.deleteById(id);
    }
}
