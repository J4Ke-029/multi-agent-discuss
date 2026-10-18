package com.multiagent.discuss.db.repo;

import com.multiagent.discuss.db.entity.AgentMemoryPinEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AgentMemoryPinRepository extends JpaRepository<AgentMemoryPinEntity, Long> {

    /** 按时间正序：注入 prompt 时钉子的先后顺序稳定可复现。 */
    List<AgentMemoryPinEntity> findAllByTargetTypeAndTargetUuidOrderByCreatedAtAsc(String targetType, String targetUuid);

    /** 拔钉子用：按 owner 限定，避免拿别人的 pinId 摘掉别人的钉子。 */
    Optional<AgentMemoryPinEntity> findByIdAndOwnerUserId(Long id, Long ownerUserId);

    void deleteAllByTargetTypeAndTargetUuid(String targetType, String targetUuid);
}
