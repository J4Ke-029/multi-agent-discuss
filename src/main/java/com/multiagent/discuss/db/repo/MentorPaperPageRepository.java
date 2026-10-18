package com.multiagent.discuss.db.repo;

import com.multiagent.discuss.db.entity.MentorPaperPageEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MentorPaperPageRepository extends JpaRepository<MentorPaperPageEntity, Long> {

    List<MentorPaperPageEntity> findAllByPaperIdOrderByPageIndexAsc(Long paperId);

    void deleteAllByPaperId(Long paperId);
}
