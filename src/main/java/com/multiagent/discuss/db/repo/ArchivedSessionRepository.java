package com.multiagent.discuss.db.repo;

import com.multiagent.discuss.db.entity.ArchivedSessionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ArchivedSessionRepository extends JpaRepository<ArchivedSessionEntity, Long> {

    List<ArchivedSessionEntity> findAllByOwnerUserIdOrderByCreatedAtDesc(Long ownerUserId);

    Optional<ArchivedSessionEntity> findByIdAndOwnerUserId(Long id, Long ownerUserId);
}
