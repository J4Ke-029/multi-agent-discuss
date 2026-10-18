package com.multiagent.discuss.db.repo;

import com.multiagent.discuss.db.entity.PersistentAgentDocEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PersistentAgentDocRepository extends JpaRepository<PersistentAgentDocEntity, Long> {

    /**
     * 知识列表。
     *
     * <p>⚠️ {@code id ASC} 这个次键**是必须的**，不是装饰：{@code sort_order} 允许撞车
     * （两个标签页同时结束会话时，各自事务里读到同一个 {@code MAX(sort_order)}），
     * 只按 {@code sort_order} 排会让列表顺序不确定。导师那边
     * （{@code MentorPaperRepository.findAllByMentorIdOrderBySortOrderAsc}）就没有这个次键 ——
     * 那是个既有隐患，别照抄。
     */
    List<PersistentAgentDocEntity> findAllByPersistentAgentIdOrderBySortOrderAscIdAsc(Long persistentAgentId);

    Optional<PersistentAgentDocEntity> findByIdAndPersistentAgentId(Long id, Long persistentAgentId);

    /**
     * 下一个位次，用 {@code MAX + 1} 而**不是** {@code countBy}。
     *
     * <p>导师那边用 {@code countByMentorId}：删掉一篇后计数留空洞，新论文会拿到**已存在**的位次。
     * 新表没有历史包袱，直接做对。
     */
    @Query("select coalesce(max(d.sortOrder), -1) + 1 from PersistentAgentDocEntity d "
            + "where d.persistentAgentId = :persistentAgentId")
    int nextSortOrder(@Param("persistentAgentId") Long persistentAgentId);

    /**
     * 一次查回多个常驻的「N 个文件 · M 页」，避免列表接口对每个 agent 各查一次（N+1）。
     *
     * <p>M 取 {@code sum(page_count)} 而不是数 {@code persistent_agent_doc_page} 的行数：
     * 两个数是同一件事，前者少一次 join。
     */
    @Query("select d.persistentAgentId as agentId, count(d) as docCount, "
            + "coalesce(sum(d.pageCount), 0) as pageCount "
            + "from PersistentAgentDocEntity d where d.persistentAgentId in :agentIds "
            + "group by d.persistentAgentId")
    List<DocCount> countByAgentIds(@Param("agentIds") Collection<Long> agentIds);

    /** {@link #countByAgentIds} 的投影。字段名必须与 as 别名一致。 */
    interface DocCount {
        Long getAgentId();

        long getDocCount();

        long getPageCount();
    }
}
