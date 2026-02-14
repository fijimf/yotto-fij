package com.yotto.basketball.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Entity
@Table(name = "seasons")
public class Season {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotNull
    @Column(unique = true)
    private Integer year;

    private LocalDate startDate;

    private LocalDate endDate;

    private String description;

    @OneToMany(mappedBy = "season", cascade = CascadeType.ALL)
    private List<Game> games = new ArrayList<>();

    @OneToMany(mappedBy = "season", cascade = CascadeType.ALL)
    private List<Tournament> tournaments = new ArrayList<>();

    @OneToMany(mappedBy = "season", cascade = CascadeType.ALL)
    private List<ConferenceMembership> conferenceMemberships = new ArrayList<>();

    public Season() {
    }

    public Season(Long id, Integer year, LocalDate startDate, LocalDate endDate, String description) {
        this.id = id;
        this.year = year;
        this.startDate = startDate;
        this.endDate = endDate;
        this.description = description;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Integer getYear() {
        return year;
    }

    public void setYear(Integer year) {
        this.year = year;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public void setStartDate(LocalDate startDate) {
        this.startDate = startDate;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    public void setEndDate(LocalDate endDate) {
        this.endDate = endDate;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public List<Game> getGames() {
        return games;
    }

    public void setGames(List<Game> games) {
        this.games = games;
    }

    public List<Tournament> getTournaments() {
        return tournaments;
    }

    public void setTournaments(List<Tournament> tournaments) {
        this.tournaments = tournaments;
    }

    public List<ConferenceMembership> getConferenceMemberships() {
        return conferenceMemberships;
    }

    public void setConferenceMemberships(List<ConferenceMembership> conferenceMemberships) {
        this.conferenceMemberships = conferenceMemberships;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Season season = (Season) o;
        return Objects.equals(id, season.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "Season{" +
                "id=" + id +
                ", year=" + year +
                ", startDate=" + startDate +
                ", endDate=" + endDate +
                ", description='" + description + '\'' +
                '}';
    }
}
