package com.yotto.basketball.repository;

import com.yotto.basketball.entity.NewsAlias;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface NewsAliasRepository extends JpaRepository<NewsAlias, Long> {

    List<NewsAlias> findByEnabledTrue();

    List<NewsAlias> findByKind(NewsAlias.Kind kind);

    List<NewsAlias> findByAliasIgnoreCaseOrderByAlias(String alias);

    List<NewsAlias> findByAliasContainingIgnoreCaseOrderByAlias(String fragment);

    Optional<NewsAlias> findByAliasAndTeamId(String alias, Long teamId);

    Optional<NewsAlias> findByAliasAndConferenceId(String alias, Long conferenceId);

    List<NewsAlias> findByTeamIdOrderByAlias(Long teamId);

    /** BLOCKed (alias, target) pairs — suppress matching AUTO/MANUAL rows at automaton build. */
    @Query("select a from NewsAlias a where a.kind = com.yotto.basketball.entity.NewsAlias.Kind.BLOCK")
    List<NewsAlias> findBlocks();

    void deleteByKindAndTeamIsNotNull(NewsAlias.Kind kind);

    void deleteByKindAndConferenceIsNotNull(NewsAlias.Kind kind);

    @Query("select count(a) from NewsAlias a where a.kind = :kind")
    long countByKind(@Param("kind") NewsAlias.Kind kind);
}
