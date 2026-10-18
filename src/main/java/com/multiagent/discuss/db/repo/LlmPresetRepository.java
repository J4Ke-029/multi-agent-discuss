package com.multiagent.discuss.db.repo;

import com.multiagent.discuss.db.entity.LlmPresetEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface LlmPresetRepository extends JpaRepository<LlmPresetEntity, Long> {

    List<LlmPresetEntity> findAllByOwnerUserIdOrderByUpdatedAtDesc(Long ownerUserId);

    Optional<LlmPresetEntity> findByIdAndOwnerUserId(Long id, Long ownerUserId);

    boolean existsByOwnerUserIdAndLabel(Long ownerUserId, String label);
}