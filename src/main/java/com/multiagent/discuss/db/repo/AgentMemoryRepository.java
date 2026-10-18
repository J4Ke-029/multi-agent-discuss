package com.multiagent.discuss.db.repo;

import com.multiagent.discuss.db.entity.AgentMemoryEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AgentMemoryRepository extends JpaRepository<AgentMemoryEntity, Long> {

    Optional<AgentMemoryEntity> findByTargetTypeAndTargetUuid(String targetType, String targetUuid);

    /**
     * 删除某个目标的心得。target_uuid 是多态引用、建不了外键，
     * 删常驻智能体 / 导师时必须显式调它，否则留下孤儿行。
     */
    void deleteAllByTargetTypeAndTargetUuid(String targetType, String targetUuid);
}
