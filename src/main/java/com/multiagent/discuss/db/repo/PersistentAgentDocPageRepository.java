package com.multiagent.discuss.db.repo;

import com.multiagent.discuss.db.entity.PersistentAgentDocPageEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PersistentAgentDocPageRepository extends JpaRepository<PersistentAgentDocPageEntity, Long> {

    /** 顺序与导师侧一致：按文件（已按 sort_order 排好）再按页序展开。 */
    List<PersistentAgentDocPageEntity> findAllByDocIdOrderByPageIndexAsc(Long docId);

    void deleteAllByDocId(Long docId);
}
