package com.yotto.basketball.service;

import com.yotto.basketball.entity.Team;
import com.yotto.basketball.repository.TeamRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional
public class TeamService {

    private final TeamRepository teamRepository;

    public TeamService(TeamRepository teamRepository) {
        this.teamRepository = teamRepository;
    }

    public Team create(Team team) {
        return teamRepository.save(team);
    }

    @Transactional(readOnly = true)
    public Team findById(Long id) {
        return teamRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Team not found with id: " + id));
    }

    @Transactional(readOnly = true)
    public List<Team> findAll() {
        return teamRepository.findAll();
    }

    @Transactional(readOnly = true)
    public List<Team> searchByName(String name) {
        return teamRepository.findByNameContainingIgnoreCase(name);
    }

    @Transactional(readOnly = true)
    public List<Team> findByState(String state) {
        return teamRepository.findByState(state);
    }

    public Team update(Long id, Team team) {
        Team existing = findById(id);
        existing.setName(team.getName());
        existing.setNickname(team.getNickname());
        existing.setMascot(team.getMascot());
        existing.setCity(team.getCity());
        existing.setState(team.getState());
        return teamRepository.save(existing);
    }

    public void delete(Long id) {
        if (!teamRepository.existsById(id)) {
            throw new EntityNotFoundException("Team not found with id: " + id);
        }
        teamRepository.deleteById(id);
    }
}
