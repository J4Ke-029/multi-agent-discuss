package com.multiagent.discuss.db.repo;

import com.multiagent.discuss.db.entity.SkillEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SkillRepository extends JpaRepository<SkillEntity, Long> {
    List<SkillEntity> findByOwnerUserIdOrderByNameAsc(Long ownerUserId);
    Optional<SkillEntity> findByOwnerUserIdAndSkillUuid(Long ownerUserId, String skillUuid);
    Optional<SkillEntity> findByOwnerUserIdAndNameIgnoreCase(Long ownerUserId, String name);
}
