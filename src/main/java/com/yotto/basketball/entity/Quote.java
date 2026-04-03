package com.yotto.basketball.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Objects;

@Entity
@Table(name = "quotes")
public class Quote {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank
    @Column(columnDefinition = "text", nullable = false)
    private String quoteText;

    @NotBlank
    @Column(length = 500, nullable = false)
    private String attribution;

    @NotNull
    @Column(nullable = false)
    private Boolean active = true;

    public Quote() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getQuoteText() { return quoteText; }
    public void setQuoteText(String quoteText) { this.quoteText = quoteText; }

    public String getAttribution() { return attribution; }
    public void setAttribution(String attribution) { this.attribution = attribution; }

    public Boolean getActive() { return active; }
    public void setActive(Boolean active) { this.active = active; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Quote quote = (Quote) o;
        return Objects.equals(id, quote.id);
    }

    @Override
    public int hashCode() { return Objects.hash(id); }
}
