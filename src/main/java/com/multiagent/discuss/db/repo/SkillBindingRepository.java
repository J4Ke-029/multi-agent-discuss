package com.multiagent.discuss.db.repo;

import com.multiagent.discuss.db.entity.SkillBindingEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SkillBindingRepository extends JpaRepository<SkillBindingEntity, Long> {
    List<SkillBindingEntity> findByOwnerUserIdAndTargetTypeAndTargetUuidOrderByIdAsc(
            Long ownerUserId, String targetType, String targetUuid);
    void deleteByOwnerUserIdAndTargetTypeAndTargetUuid(Long ownerUserId, String targetType, String targetUuid);
    void deleteBySkillId(Long skillId);
    long countBySkillId(Long skillId);
    List<SkillBindingEntity> findBySkillIdOrderByIdAsc(Long skillId);
}
