package com.yotto.basketball.service;

import com.yotto.basketball.entity.Season;
import com.yotto.basketball.repository.SeasonRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional
public class SeasonService {

    private final SeasonRepository seasonRepository;

    public SeasonService(SeasonRepository seasonRepository) {
        this.seasonRepository = seasonRepository;
    }

    public Season create(Season season) {
        if (seasonRepository.existsByYear(season.getYear())) {
            throw new IllegalArgumentException("Season for year " + season.getYear() + " already exists");
        }
        return seasonRepository.save(season);
    }

    @Transactional(readOnly = true)
    public Season findById(Long id) {
        return seasonRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Season not found with id: " + id));
    }

    @Transactional(readOnly = true)
    public Season findByYear(Integer year) {
        return seasonRepository.findByYear(year)
                .orElseThrow(() -> new EntityNotFoundException("Season not found for year: " + year));
    }

    @Transactional(readOnly = true)
    public List<Season> findAll() {
        return seasonRepository.findAll();
    }

    public Season update(Long id, Season season) {
        Season existing = findById(id);
        existing.setYear(season.getYear());
        existing.setStartDate(season.getStartDate());
        existing.setEndDate(season.getEndDate());
        existing.setDescription(season.getDescription());
        return seasonRepository.save(existing);
    }

    public void delete(Long id) {
        if (!seasonRepository.existsById(id)) {
            throw new EntityNotFoundException("Season not found with id: " + id);
        }
        seasonRepository.deleteById(id);
    }
}
