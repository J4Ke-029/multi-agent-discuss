package com.multiagent.discuss.db.repo;

import com.multiagent.discuss.db.entity.MentorEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MentorRepository extends JpaRepository<MentorEntity, Long> {

    List<MentorEntity> findAllByOwnerUserIdOrderByCreatedAtDesc(Long ownerUserId);

    Optional<MentorEntity> findByIdAndOwnerUserId(Long id, Long ownerUserId);

    Optional<MentorEntity> findByMentorUuid(String mentorUuid);

    /** 心得按 uuid 反查归属（同 findByMentorUuid，但带上 owner 收口）。 */
    Optional<MentorEntity> findByMentorUuidAndOwnerUserId(String mentorUuid, Long ownerUserId);
}
