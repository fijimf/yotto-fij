package com.yotto.basketball.service;

import com.yotto.basketball.entity.Conference;
import com.yotto.basketball.repository.ConferenceRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional
public class ConferenceService {

    private final ConferenceRepository conferenceRepository;

    public ConferenceService(ConferenceRepository conferenceRepository) {
        this.conferenceRepository = conferenceRepository;
    }

    public Conference create(Conference conference) {
        if (conferenceRepository.existsByName(conference.getName())) {
            throw new IllegalArgumentException("Conference with name '" + conference.getName() + "' already exists");
        }
        return conferenceRepository.save(conference);
    }

    @Transactional(readOnly = true)
    public Conference findById(Long id) {
        return conferenceRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Conference not found with id: " + id));
    }

    @Transactional(readOnly = true)
    public Conference findByName(String name) {
        return conferenceRepository.findByName(name)
                .orElseThrow(() -> new EntityNotFoundException("Conference not found with name: " + name));
    }

    @Transactional(readOnly = true)
    public List<Conference> findAll() {
        return conferenceRepository.findAll();
    }

    public Conference update(Long id, Conference conference) {
        Conference existing = findById(id);
        existing.setName(conference.getName());
        existing.setAbbreviation(conference.getAbbreviation());
        existing.setDivision(conference.getDivision());
        return conferenceRepository.save(existing);
    }

    public void delete(Long id) {
        if (!conferenceRepository.existsById(id)) {
            throw new EntityNotFoundException("Conference not found with id: " + id);
        }
        conferenceRepository.deleteById(id);
    }
}
