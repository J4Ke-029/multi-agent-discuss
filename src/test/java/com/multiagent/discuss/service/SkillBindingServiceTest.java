package com.multiagent.discuss.service;

import com.multiagent.discuss.db.entity.UserEntity;
import com.multiagent.discuss.db.entity.PersistentAgentEntity;
import com.multiagent.discuss.db.entity.SkillBindingEntity;
import com.multiagent.discuss.db.entity.SkillEntity;
import com.multiagent.discuss.db.repo.MentorRepository;
import com.multiagent.discuss.db.repo.PersistentAgentRepository;
import com.multiagent.discuss.db.repo.SkillBindingRepository;
import com.multiagent.discuss.db.repo.SkillRepository;
import com.multiagent.discuss.domain.DomainAgentConfig;
import com.multiagent.discuss.domain.Session;
import com.multiagent.discuss.knowledge.InMemorySessionRepository;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SkillBindingServiceTest {

    @Test
    void temporaryAgentCannotBindMoreThanFiveSkills() {
        UserEntity owner = new UserEntity();
        ReflectionTestUtils.setField(owner, "id", 42L);
        Session session = new Session("session-1", null, true, false, 20, 2);
        session.setOwnerUserId(42L);
        session.getAgents().put("agent-1", new DomainAgentConfig(
                "agent-1", "session-1", "Analyst", null, null, null, "persona"));
        InMemorySessionRepository sessions = new InMemorySessionRepository();
        sessions.saveSession(session);
        SkillBindingService service = new SkillBindingService(
                mock(SkillRepository.class), mock(SkillBindingRepository.class),
                mock(PersistentAgentRepository.class), mock(MentorRepository.class), sessions);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                service.replace(owner, SkillBindingService.TargetType.TEMPORARY,
                        "agent-1", "session-1", List.of("1", "2", "3", "4", "5", "6")));

        assertEquals("每个智能体最多绑定 5 个 Skill", error.getMessage());
    }

    @Test
    void persistentAgentInAnyActiveSessionCannotChangeBindings() {
        UserEntity owner = new UserEntity();
        ReflectionTestUtils.setField(owner, "id", 42L);
        PersistentAgentEntity persistent = new PersistentAgentEntity();
        persistent.setOwnerUserId(42L);
        persistent.setAgentUuid("persistent-1");
        PersistentAgentRepository persistentAgents = mock(PersistentAgentRepository.class);
        when(persistentAgents.findByAgentUuidAndOwnerUserId("persistent-1", 42L))
                .thenReturn(java.util.Optional.of(persistent));

        Session session = new Session("session-1", null, true, false, 20, 2);
        session.setOwnerUserId(42L);
        DomainAgentConfig seat = new DomainAgentConfig(
                "seat-1", "session-1", "Analyst", null, null, null, "persona");
        seat.setAgentUuid("persistent-1");
        session.getAgents().put("seat-1", seat);
        InMemorySessionRepository sessions = new InMemorySessionRepository();
        sessions.saveSession(session);
        SkillBindingService service = new SkillBindingService(
                mock(SkillRepository.class), mock(SkillBindingRepository.class),
                persistentAgents, mock(MentorRepository.class), sessions);

        IllegalStateException error = assertThrows(IllegalStateException.class, () ->
                service.replace(owner, SkillBindingService.TargetType.PERSISTENT,
                        "persistent-1", null, List.of()));

        assertEquals("该智能体正在活跃会话中，结束或移出后可修改", error.getMessage());
    }

    @Test
    void addingSecondSkillKeepsExistingBindingAndOnlyInsertsNewBinding() {
        UserEntity owner = owner(42L);
        PersistentAgentRepository persistentAgents = persistentAgentRepository(42L, "persistent-1");
        SkillRepository skills = mock(SkillRepository.class);
        SkillEntity first = skill(1L, "skill-1", 42L);
        SkillEntity second = skill(2L, "skill-2", 42L);
        when(skills.findByOwnerUserIdAndSkillUuid(42L, "skill-1"))
                .thenReturn(java.util.Optional.of(first));
        when(skills.findByOwnerUserIdAndSkillUuid(42L, "skill-2"))
                .thenReturn(java.util.Optional.of(second));
        SkillBindingRepository bindings = mock(SkillBindingRepository.class);
        when(bindings.findByOwnerUserIdAndTargetTypeAndTargetUuidOrderByIdAsc(
                42L, "PERSISTENT", "persistent-1"))
                .thenReturn(List.of(binding(10L, 1L, 42L, "PERSISTENT", "persistent-1")));
        SkillBindingService service = new SkillBindingService(
                skills, bindings, persistentAgents, mock(MentorRepository.class),
                new InMemorySessionRepository());

        service.replace(owner, SkillBindingService.TargetType.PERSISTENT,
                "persistent-1", null, List.of("skill-1", "skill-2"));

        verify(bindings, never()).save(argThat(item -> item.getSkillId().equals(1L)));
        verify(bindings).save(argThat(item -> item.getSkillId().equals(2L)));
    }

    @Test
    void replacingSkillFlushesRemovedBindingBeforeInsertingReplacement() {
        UserEntity owner = owner(42L);
        PersistentAgentRepository persistentAgents = persistentAgentRepository(42L, "persistent-1");
        SkillRepository skills = mock(SkillRepository.class);
        SkillEntity replacement = skill(2L, "skill-2", 42L);
        when(skills.findByOwnerUserIdAndSkillUuid(42L, "skill-2"))
                .thenReturn(java.util.Optional.of(replacement));
        SkillBindingRepository bindings = mock(SkillBindingRepository.class);
        SkillBindingEntity existing = binding(10L, 1L, 42L, "PERSISTENT", "persistent-1");
        when(bindings.findByOwnerUserIdAndTargetTypeAndTargetUuidOrderByIdAsc(
                42L, "PERSISTENT", "persistent-1")).thenReturn(List.of(existing));
        SkillBindingService service = new SkillBindingService(
                skills, bindings, persistentAgents, mock(MentorRepository.class),
                new InMemorySessionRepository());

        service.replace(owner, SkillBindingService.TargetType.PERSISTENT,
                "persistent-1", null, List.of("skill-2"));

        InOrder order = inOrder(bindings);
        order.verify(bindings).deleteAllInBatch(List.of(existing));
        order.verify(bindings).flush();
        order.verify(bindings).save(argThat(item -> item.getSkillId().equals(2L)));
    }

    private static UserEntity owner(Long id) {
        UserEntity owner = new UserEntity();
        ReflectionTestUtils.setField(owner, "id", id);
        return owner;
    }

    private static PersistentAgentRepository persistentAgentRepository(Long ownerId, String agentUuid) {
        PersistentAgentEntity persistent = new PersistentAgentEntity();
        persistent.setOwnerUserId(ownerId);
        persistent.setAgentUuid(agentUuid);
        PersistentAgentRepository repository = mock(PersistentAgentRepository.class);
        when(repository.findByAgentUuidAndOwnerUserId(agentUuid, ownerId))
                .thenReturn(java.util.Optional.of(persistent));
        return repository;
    }

    private static SkillEntity skill(Long id, String uuid, Long ownerId) {
        SkillEntity skill = new SkillEntity();
        ReflectionTestUtils.setField(skill, "id", id);
        skill.setSkillUuid(uuid);
        skill.setOwnerUserId(ownerId);
        skill.setName(uuid);
        skill.setDescription(uuid);
        return skill;
    }

    private static SkillBindingEntity binding(Long id, Long skillId, Long ownerId,
                                              String targetType, String targetUuid) {
        SkillBindingEntity binding = new SkillBindingEntity();
        ReflectionTestUtils.setField(binding, "id", id);
        binding.setSkillId(skillId);
        binding.setOwnerUserId(ownerId);
        binding.setTargetType(targetType);
        binding.setTargetUuid(targetUuid);
        return binding;
    }
}
