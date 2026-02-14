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

    @NotBlank
    private String name;

    private String nickname;

    private String mascot;

    private String city;

    private String state;

    @OneToMany(mappedBy = "team", cascade = CascadeType.ALL)
    private List<ConferenceMembership> conferenceMemberships = new ArrayList<>();

    @OneToMany(mappedBy = "homeTeam")
    private List<Game> homeGames = new ArrayList<>();

    @OneToMany(mappedBy = "awayTeam")
    private List<Game> awayGames = new ArrayList<>();

    public Team() {
    }

    public Team(Long id, String name, String nickname, String mascot, String city, String state) {
        this.id = id;
        this.name = name;
        this.nickname = nickname;
        this.mascot = mascot;
        this.city = city;
        this.state = state;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
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

    public String getCity() {
        return city;
    }

    public void setCity(String city) {
        this.city = city;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
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
                ", name='" + name + '\'' +
                ", nickname='" + nickname + '\'' +
                ", mascot='" + mascot + '\'' +
                ", city='" + city + '\'' +
                ", state='" + state + '\'' +
                '}';
    }
}
