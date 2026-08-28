package com.yotto.basketball.news;

import java.time.LocalDateTime;

/** Lightweight view of a recent article for the title-similarity dedup scan. */
public record TitleCandidate(Long id, String title, Double staticScore,
                             LocalDateTime publishedAt, Long duplicateOfId) {
}
