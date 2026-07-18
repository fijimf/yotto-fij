package com.yotto.basketball.news;

import java.time.LocalDateTime;

/** Lightweight view of a recent article for the SimHash dedup window scan. */
public record SimhashCandidate(Long id, Long simhash, Double staticScore,
                               LocalDateTime publishedAt, Long duplicateOfId) {
}
