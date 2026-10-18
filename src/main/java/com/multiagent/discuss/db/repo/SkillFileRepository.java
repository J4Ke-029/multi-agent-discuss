package com.multiagent.discuss.db.repo;

import com.multiagent.discuss.db.entity.SkillFileEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface SkillFileRepository extends JpaRepository<SkillFileEntity, Long> {
    List<SkillFileEntity> findBySkillIdOrderByPathAsc(Long skillId);
    @Query("""
            select f from SkillFileEntity f
            where f.skillId = :skillId
              and lower(f.path) not like 'scripts/%'
              and lower(f.path) not like '%/scripts/%'
              and (lower(f.path) like '%.md' or lower(f.path) like '%.txt'
                or lower(f.path) like '%.json' or lower(f.path) like '%.yaml'
                or lower(f.path) like '%.yml')
            order by f.path asc
            """)
    List<SkillFileEntity> findInjectableTextBySkillId(@Param("skillId") Long skillId);
    void deleteBySkillId(Long skillId);
}
