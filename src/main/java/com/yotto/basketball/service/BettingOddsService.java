package com.yotto.basketball.service;

import com.yotto.basketball.entity.BettingOdds;
import com.yotto.basketball.entity.Game;
import com.yotto.basketball.repository.BettingOddsRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@Transactional
public class BettingOddsService {

    private final BettingOddsRepository bettingOddsRepository;
    private final GameService gameService;

    public BettingOddsService(BettingOddsRepository bettingOddsRepository, GameService gameService) {
        this.bettingOddsRepository = bettingOddsRepository;
        this.gameService = gameService;
    }

    public BettingOdds create(BettingOdds odds, Long gameId) {
        Game game = gameService.findById(gameId);
        odds.setGame(game);
        odds.setLastUpdated(LocalDateTime.now());

        if (odds.getOpeningSpread() == null) {
            odds.setOpeningSpread(odds.getSpread());
        }
        if (odds.getOpeningOverUnder() == null) {
            odds.setOpeningOverUnder(odds.getOverUnder());
        }

        return bettingOddsRepository.save(odds);
    }

    @Transactional(readOnly = true)
    public BettingOdds findById(Long id) {
        return bettingOddsRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Betting odds not found with id: " + id));
    }

    @Transactional(readOnly = true)
    public BettingOdds findByGameId(Long gameId) {
        return bettingOddsRepository.findByGameId(gameId)
                .orElseThrow(() -> new EntityNotFoundException("Betting odds not found for game id: " + gameId));
    }

    @Transactional(readOnly = true)
    public List<BettingOdds> findAll() {
        return bettingOddsRepository.findAll();
    }

    @Transactional(readOnly = true)
    public List<BettingOdds> findBySeason(Long seasonId) {
        return bettingOddsRepository.findBySeasonId(seasonId);
    }

    public BettingOdds update(Long id, BettingOdds odds) {
        BettingOdds existing = findById(id);
        existing.setSpread(odds.getSpread());
        existing.setOverUnder(odds.getOverUnder());
        existing.setHomeMoneyline(odds.getHomeMoneyline());
        existing.setAwayMoneyline(odds.getAwayMoneyline());
        existing.setSource(odds.getSource());
        existing.setLastUpdated(LocalDateTime.now());
        return bettingOddsRepository.save(existing);
    }

    public void delete(Long id) {
        if (!bettingOddsRepository.existsById(id)) {
            throw new EntityNotFoundException("Betting odds not found with id: " + id);
        }
        bettingOddsRepository.deleteById(id);
    }
}
