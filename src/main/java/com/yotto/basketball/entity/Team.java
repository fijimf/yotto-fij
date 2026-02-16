package com.yotto.basketball.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Entity
@Table(name = "teams")
public class Team {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "espn_id", unique = true)
    private String espnId;

    @NotBlank
    private String name;

    private String nickname;

    private String mascot;

    private String abbreviation;

    private String slug;

    private String color;

    @Column(name = "alternate_color")
    private String alternateColor;

    @Column(columnDefinition = "boolean default true")
    private Boolean active;

    @Column(name = "logo_url")
    private String logoUrl;

    @OneToMany(mappedBy = "team", cascade = CascadeType.ALL)
    private List<ConferenceMembership> conferenceMemberships = new ArrayList<>();

    @OneToMany(mappedBy = "homeTeam")
    private List<Game> homeGames = new ArrayList<>();

    @OneToMany(mappedBy = "awayTeam")
    private List<Game> awayGames = new ArrayList<>();

    public Team() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getEspnId() {
        return espnId;
    }

    public void setEspnId(String espnId) {
        this.espnId = espnId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getNickname() {
        return nickname;
    }

    public void setNickname(String nickname) {
        this.nickname = nickname;
    }

    public String getMascot() {
        return mascot;
    }

    public void setMascot(String mascot) {
        this.mascot = mascot;
    }

    public String getAbbreviation() {
        return abbreviation;
    }

    public void setAbbreviation(String abbreviation) {
        this.abbreviation = abbreviation;
    }

    public String getSlug() {
        return slug;
    }

    public void setSlug(String slug) {
        this.slug = slug;
    }

    public String getColor() {
        return color;
    }

    public void setColor(String color) {
        this.color = color;
    }

    public String getAlternateColor() {
        return alternateColor;
    }

    public void setAlternateColor(String alternateColor) {
        this.alternateColor = alternateColor;
    }

    public Boolean getActive() {
        return active;
    }

    public void setActive(Boolean active) {
        this.active = active;
    }

    public String getLogoUrl() {
        return logoUrl;
    }

    public void setLogoUrl(String logoUrl) {
        this.logoUrl = logoUrl;
    }

    public List<ConferenceMembership> getConferenceMemberships() {
        return conferenceMemberships;
    }

    public void setConferenceMemberships(List<ConferenceMembership> conferenceMemberships) {
        this.conferenceMemberships = conferenceMemberships;
    }

    public List<Game> getHomeGames() {
        return homeGames;
    }

    public void setHomeGames(List<Game> homeGames) {
        this.homeGames = homeGames;
    }

    public List<Game> getAwayGames() {
        return awayGames;
    }

    public void setAwayGames(List<Game> awayGames) {
        this.awayGames = awayGames;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Team team = (Team) o;
        return Objects.equals(id, team.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "Team{" +
                "id=" + id +
                ", espnId='" + espnId + '\'' +
                ", name='" + name + '\'' +
                ", nickname='" + nickname + '\'' +
                ", abbreviation='" + abbreviation + '\'' +
                '}';
    }
}
