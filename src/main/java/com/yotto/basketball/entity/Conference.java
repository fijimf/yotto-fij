package com.yotto.basketball.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Entity
@Table(name = "conferences")
public class Conference {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank
    @Column(unique = true)
    private String name;

    private String abbreviation;

    private String division;

    @OneToMany(mappedBy = "conference", cascade = CascadeType.ALL)
    private List<ConferenceMembership> memberships = new ArrayList<>();

    public Conference() {
    }

    public Conference(Long id, String name, String abbreviation, String division, List<ConferenceMembership> memberships) {
        this.id = id;
        this.name = name;
        this.abbreviation = abbreviation;
        this.division = division;
        this.memberships = memberships != null ? memberships : new ArrayList<>();
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

    public String getAbbreviation() {
        return abbreviation;
    }

    public void setAbbreviation(String abbreviation) {
        this.abbreviation = abbreviation;
    }

    public String getDivision() {
        return division;
    }

    public void setDivision(String division) {
        this.division = division;
    }

    public List<ConferenceMembership> getMemberships() {
        return memberships;
    }

    public void setMemberships(List<ConferenceMembership> memberships) {
        this.memberships = memberships;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Conference that = (Conference) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "Conference{" +
                "id=" + id +
                ", name='" + name + '\'' +
                ", abbreviation='" + abbreviation + '\'' +
                ", division='" + division + '\'' +
                '}';
    }
}
