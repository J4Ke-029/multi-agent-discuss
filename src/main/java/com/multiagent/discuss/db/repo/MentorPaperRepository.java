package com.multiagent.discuss.db.repo;

import com.multiagent.discuss.db.entity.MentorPaperEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MentorPaperRepository extends JpaRepository<MentorPaperEntity, Long> {

    List<MentorPaperEntity> findAllByMentorIdOrderBySortOrderAsc(Long mentorId);

    Optional<MentorPaperEntity> findByIdAndMentorId(Long id, Long mentorId);

    long countByMentorId(Long mentorId);

    void deleteAllByMentorId(Long mentorId);
}
