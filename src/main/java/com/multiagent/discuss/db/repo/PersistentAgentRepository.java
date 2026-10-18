package com.multiagent.discuss.db.repo;

import com.multiagent.discuss.db.entity.PersistentAgentEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PersistentAgentRepository extends JpaRepository<PersistentAgentEntity, Long> {

    List<PersistentAgentEntity> findAllByOwnerUserIdOrderByCreatedAtAsc(Long ownerUserId);

    Optional<PersistentAgentEntity> findByIdAndOwnerUserId(Long id, Long ownerUserId);

    /** 加入会话时按 uuid 反查（uuid 全局唯一，但仍带 owner 收口：越权 uuid 只会查不到）。 */
    Optional<PersistentAgentEntity> findByAgentUuidAndOwnerUserId(String agentUuid, Long ownerUserId);

    /**
     * 按 uuid 反查，**不带 owner**。
     *
     * <p>只给「调用方已经自己做过归属校验」的路径用：知识库灌回
     * （{@code PersistentAgentDocService.loadPages} / {@code counts}）都是
     * {@code PersistentAgentService.joinSession} 在 {@code requireOwned} 之后调进来的。
     * ⚠️ 新调用方务必确认这一点，别把没有校验的入口接到这里。
     */
    Optional<PersistentAgentEntity> findByAgentUuid(String agentUuid);

    boolean existsByAgentUuid(String agentUuid);
}