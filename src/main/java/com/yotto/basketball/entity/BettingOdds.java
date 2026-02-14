package com.yotto.basketball.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;

@Entity
@Table(name = "betting_odds")
public class BettingOdds {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "game_id", unique = true)
    private Game game;

    @Column(precision = 5, scale = 1)
    private BigDecimal spread;

    @Column(precision = 5, scale = 1)
    private BigDecimal overUnder;

    private Integer homeMoneyline;

    private Integer awayMoneyline;

    @Column(precision = 5, scale = 1)
    private BigDecimal openingSpread;

    @Column(precision = 5, scale = 1)
    private BigDecimal openingOverUnder;

    private LocalDateTime lastUpdated;

    private String source;

    public BettingOdds() {
    }

    public BettingOdds(Long id, Game game, BigDecimal spread, BigDecimal overUnder,
                       Integer homeMoneyline, Integer awayMoneyline, BigDecimal openingSpread,
                       BigDecimal openingOverUnder, LocalDateTime lastUpdated, String source) {
        this.id = id;
        this.game = game;
        this.spread = spread;
        this.overUnder = overUnder;
        this.homeMoneyline = homeMoneyline;
        this.awayMoneyline = awayMoneyline;
        this.openingSpread = openingSpread;
        this.openingOverUnder = openingOverUnder;
        this.lastUpdated = lastUpdated;
        this.source = source;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Game getGame() {
        return game;
    }

    public void setGame(Game game) {
        this.game = game;
    }

    public BigDecimal getSpread() {
        return spread;
    }

    public void setSpread(BigDecimal spread) {
        this.spread = spread;
    }

    public BigDecimal getOverUnder() {
        return overUnder;
    }

    public void setOverUnder(BigDecimal overUnder) {
        this.overUnder = overUnder;
    }

    public Integer getHomeMoneyline() {
        return homeMoneyline;
    }

    public void setHomeMoneyline(Integer homeMoneyline) {
        this.homeMoneyline = homeMoneyline;
    }

    public Integer getAwayMoneyline() {
        return awayMoneyline;
    }

    public void setAwayMoneyline(Integer awayMoneyline) {
        this.awayMoneyline = awayMoneyline;
    }

    public BigDecimal getOpeningSpread() {
        return openingSpread;
    }

    public void setOpeningSpread(BigDecimal openingSpread) {
        this.openingSpread = openingSpread;
    }

    public BigDecimal getOpeningOverUnder() {
        return openingOverUnder;
    }

    public void setOpeningOverUnder(BigDecimal openingOverUnder) {
        this.openingOverUnder = openingOverUnder;
    }

    public LocalDateTime getLastUpdated() {
        return lastUpdated;
    }

    public void setLastUpdated(LocalDateTime lastUpdated) {
        this.lastUpdated = lastUpdated;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        BettingOdds that = (BettingOdds) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "BettingOdds{" +
                "id=" + id +
                ", spread=" + spread +
                ", overUnder=" + overUnder +
                ", homeMoneyline=" + homeMoneyline +
                ", awayMoneyline=" + awayMoneyline +
                ", source='" + source + '\'' +
                '}';
    }
}
