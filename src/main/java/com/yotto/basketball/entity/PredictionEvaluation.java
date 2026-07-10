package com.yotto.basketball.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * One model's pre-game prediction for one FINAL game, with its error vs. the actual
 * result. Predictions are recomputed retroactively from rating snapshots dated strictly
 * before the game date (leakage-free), so rows for any model — including a freshly
 * trained ML model — can be backfilled over full past seasons.
 *
 * <p>{@code modelType} values: MASSEY (spread only), MASSEY_TOTALS (total only),
 * BRADLEY_TERRY and BRADLEY_TERRY_W (win probability only), ML (all three, tagged with
 * {@code modelVersion}), BOOK (closing-line benchmark: spread/total from betting odds,
 * win probability implied by de-vigged moneylines).
 */
@Entity
@Table(name = "prediction_evaluations",
       uniqueConstraints = @UniqueConstraint(columnNames = {"game_id", "model_type"}))
public class PredictionEvaluation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "game_id")
    private Game game;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "season_id")
    private Season season;

    @NotNull
    @Column(name = "model_type", length = 20)
    private String modelType;

    @NotNull
    private LocalDate gameDate;

    private Double predictedSpread;

    private Double predictedTotal;

    private Double predictedHomeWinProb;

    @NotNull
    private Integer actualMargin;

    @NotNull
    private Integer actualTotal;

    @NotNull
    private Boolean homeWon;

    private Double spreadError;

    private Double totalError;

    @Column(name = "model_version", length = 40)
    private String modelVersion;

    @NotNull
    private LocalDateTime evaluatedAt;

    public PredictionEvaluation() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Game getGame() { return game; }
    public void setGame(Game game) { this.game = game; }

    public Season getSeason() { return season; }
    public void setSeason(Season season) { this.season = season; }

    public String getModelType() { return modelType; }
    public void setModelType(String modelType) { this.modelType = modelType; }

    public LocalDate getGameDate() { return gameDate; }
    public void setGameDate(LocalDate gameDate) { this.gameDate = gameDate; }

    public Double getPredictedSpread() { return predictedSpread; }
    public void setPredictedSpread(Double predictedSpread) { this.predictedSpread = predictedSpread; }

    public Double getPredictedTotal() { return predictedTotal; }
    public void setPredictedTotal(Double predictedTotal) { this.predictedTotal = predictedTotal; }

    public Double getPredictedHomeWinProb() { return predictedHomeWinProb; }
    public void setPredictedHomeWinProb(Double predictedHomeWinProb) { this.predictedHomeWinProb = predictedHomeWinProb; }

    public Integer getActualMargin() { return actualMargin; }
    public void setActualMargin(Integer actualMargin) { this.actualMargin = actualMargin; }

    public Integer getActualTotal() { return actualTotal; }
    public void setActualTotal(Integer actualTotal) { this.actualTotal = actualTotal; }

    public Boolean getHomeWon() { return homeWon; }
    public void setHomeWon(Boolean homeWon) { this.homeWon = homeWon; }

    public Double getSpreadError() { return spreadError; }
    public void setSpreadError(Double spreadError) { this.spreadError = spreadError; }

    public Double getTotalError() { return totalError; }
    public void setTotalError(Double totalError) { this.totalError = totalError; }

    public String getModelVersion() { return modelVersion; }
    public void setModelVersion(String modelVersion) { this.modelVersion = modelVersion; }

    public LocalDateTime getEvaluatedAt() { return evaluatedAt; }
    public void setEvaluatedAt(LocalDateTime evaluatedAt) { this.evaluatedAt = evaluatedAt; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PredictionEvaluation that = (PredictionEvaluation) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() { return Objects.hash(id); }
}
