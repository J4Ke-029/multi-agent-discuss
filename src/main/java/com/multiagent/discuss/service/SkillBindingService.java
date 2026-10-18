package com.multiagent.discuss.service;

import com.multiagent.discuss.db.entity.SkillBindingEntity;
import com.multiagent.discuss.db.entity.SkillEntity;
import com.multiagent.discuss.db.entity.UserEntity;
import com.multiagent.discuss.db.repo.MentorRepository;
import com.multiagent.discuss.db.repo.PersistentAgentRepository;
import com.multiagent.discuss.db.repo.SkillBindingRepository;
import com.multiagent.discuss.db.repo.SkillRepository;
import com.multiagent.discuss.domain.DomainAgentConfig;
import com.multiagent.discuss.domain.Session;
import com.multiagent.discuss.knowledge.InMemorySessionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class SkillBindingService {
    private static final int MAX_SKILLS = 5;

    private final SkillRepository skillRepository;
    private final SkillBindingRepository bindingRepository;
    private final PersistentAgentRepository persistentAgentRepository;
    private final MentorRepository mentorRepository;
    private final InMemorySessionRepository sessions;

    public SkillBindingService(SkillRepository skillRepository,
                               SkillBindingRepository bindingRepository,
                               PersistentAgentRepository persistentAgentRepository,
                               MentorRepository mentorRepository,
                               InMemorySessionRepository sessions) {
        this.skillRepository = skillRepository;
        this.bindingRepository = bindingRepository;
        this.persistentAgentRepository = persistentAgentRepository;
        this.mentorRepository = mentorRepository;
        this.sessions = sessions;
    }

    @Transactional
    public List<SkillRef> replace(UserEntity owner, TargetType targetType,
                                  String targetUuid, String sessionId,
                                  List<String> requestedSkillUuids) {
        if (owner == null || owner.getId() == null) throw new IllegalArgumentException("必须登录后才能绑定 Skill");
        List<String> skillUuids = requestedSkillUuids == null
                ? List.of() : new ArrayList<>(new LinkedHashSet<>(requestedSkillUuids));
        if (skillUuids.size() > MAX_SKILLS) {
            throw new IllegalArgumentException("每个智能体最多绑定 5 个 Skill");
        }
        if (targetType == null) throw new IllegalArgumentException("缺少智能体类型");

        if (targetType == TargetType.TEMPORARY) {
            if (sessionId == null || sessionId.isBlank()) throw new IllegalArgumentException("临时智能体缺少 sessionId");
            Session session = sessions.requireSession(sessionId);
            if (!owner.getId().equals(session.getOwnerUserId())) throw new IllegalArgumentException("会话不存在或无权限");
            DomainAgentConfig agent = session.getAgents().get(targetUuid);
            if (agent == null) throw new IllegalArgumentException("智能体不存在");
            List<SkillEntity> skills = requireOwnedSkills(owner.getId(), skillUuids);
            agent.setSkillUuids(skills.stream().map(SkillEntity::getSkillUuid).toList());
            return skills.stream().map(SkillBindingService::ref).toList();
        }

        requireOwnedTarget(owner.getId(), targetType, targetUuid);
        if (isActiveInAnySession(owner.getId(), targetType, targetUuid)) {
            throw new IllegalStateException("该智能体正在活跃会话中，结束或移出后可修改");
        }
        List<SkillEntity> skills = requireOwnedSkills(owner.getId(), skillUuids);
        List<SkillBindingEntity> existing = bindingRepository
                .findByOwnerUserIdAndTargetTypeAndTargetUuidOrderByIdAsc(
                        owner.getId(), targetType.name(), targetUuid);
        Map<Long, SkillBindingEntity> existingBySkillId = new LinkedHashMap<>();
        existing.forEach(binding -> existingBySkillId.put(binding.getSkillId(), binding));
        Set<Long> requestedSkillIds = skills.stream()
                .map(SkillEntity::getId).collect(Collectors.toSet());
        List<SkillBindingEntity> removed = existing.stream()
                .filter(binding -> !requestedSkillIds.contains(binding.getSkillId())).toList();
        if (!removed.isEmpty()) {
            bindingRepository.deleteAllInBatch(removed);
            bindingRepository.flush();
        }
        for (SkillEntity skill : skills) {
            if (existingBySkillId.containsKey(skill.getId())) continue;
            SkillBindingEntity binding = new SkillBindingEntity();
            binding.setOwnerUserId(owner.getId());
            binding.setSkillId(skill.getId());
            binding.setTargetType(targetType.name());
            binding.setTargetUuid(targetUuid);
            bindingRepository.save(binding);
        }
        if (sessionId != null && !sessionId.isBlank()) {
            Session session = sessions.requireSession(sessionId);
            if (owner.getId().equals(session.getOwnerUserId())) {
                session.getAgents().values().stream()
                        .filter(agent -> targetType == TargetType.PERSISTENT
                                ? targetUuid.equals(agent.getAgentUuid())
                                : agent.isMentor() && targetUuid.equals(agent.getMentorUuid()))
                        .forEach(agent -> agent.setSkillUuids(skills.stream()
                                .map(SkillEntity::getSkillUuid).toList()));
            }
        }
        return skills.stream().map(SkillBindingService::ref).toList();
    }

    @Transactional(readOnly = true)
    public List<SkillEntity> boundSkills(Long ownerUserId, TargetType targetType, String targetUuid) {
        if (ownerUserId == null || targetType == null || targetUuid == null) return List.of();
        List<Long> ids = bindingRepository
                .findByOwnerUserIdAndTargetTypeAndTargetUuidOrderByIdAsc(ownerUserId, targetType.name(), targetUuid)
                .stream().map(SkillBindingEntity::getSkillId).toList();
        if (ids.isEmpty()) return List.of();
        var byId = new java.util.HashMap<Long, SkillEntity>();
        skillRepository.findAllById(ids).forEach(skill -> {
            if (ownerUserId.equals(skill.getOwnerUserId())) byId.put(skill.getId(), skill);
        });
        return ids.stream().map(byId::get).filter(java.util.Objects::nonNull).toList();
    }

    @Transactional(readOnly = true)
    public List<SkillRef> current(UserEntity owner, TargetType targetType, String targetUuid, String sessionId) {
        if (targetType == TargetType.TEMPORARY) {
            Session session = sessions.requireSession(sessionId);
            if (!owner.getId().equals(session.getOwnerUserId())) throw new IllegalArgumentException("会话不存在或无权限");
            DomainAgentConfig agent = session.getAgents().get(targetUuid);
            if (agent == null) throw new IllegalArgumentException("智能体不存在");
            return requireOwnedSkills(owner.getId(), agent.getSkillUuids()).stream().map(SkillBindingService::ref).toList();
        }
        requireOwnedTarget(owner.getId(), targetType, targetUuid);
        return boundSkills(owner.getId(), targetType, targetUuid).stream().map(SkillBindingService::ref).toList();
    }

    public void removeDeletedSkillFromTemporaryAgents(Long ownerUserId, String skillUuid) {
        for (Session session : sessions.findAll()) {
            if (!ownerUserId.equals(session.getOwnerUserId())) continue;
            for (DomainAgentConfig agent : session.getAgents().values()) {
                if (agent.getSkillUuids().contains(skillUuid)) {
                    agent.setSkillUuids(agent.getSkillUuids().stream()
                            .filter(uuid -> !skillUuid.equals(uuid)).toList());
                }
            }
        }
    }

    @Transactional
    public void deleteBindingsForTarget(Long ownerUserId, TargetType targetType, String targetUuid) {
        bindingRepository.deleteByOwnerUserIdAndTargetTypeAndTargetUuid(
                ownerUserId, targetType.name(), targetUuid);
    }

    public boolean isActiveInAnySession(Long ownerUserId, TargetType targetType, String targetUuid) {
        if (ownerUserId == null || targetType == null || targetUuid == null) return false;
        return sessions.findAll().stream()
                .filter(session -> ownerUserId.equals(session.getOwnerUserId()))
                .flatMap(session -> session.getAgents().values().stream())
                .anyMatch(agent -> targetType == TargetType.PERSISTENT
                        ? targetUuid.equals(agent.getAgentUuid())
                        : targetType == TargetType.MENTOR
                            && agent.isMentor()
                            && targetUuid.equals(agent.getMentorUuid()));
    }

    @Transactional(readOnly = true)
    public List<BindingOverview> overview(UserEntity owner) {
        if (owner == null || owner.getId() == null) throw new IllegalArgumentException("必须登录后才能查看 Skill 绑定");
        List<BindingOverview> result = new ArrayList<>();
        persistentAgentRepository.findAllByOwnerUserIdOrderByCreatedAtAsc(owner.getId()).forEach(agent ->
                result.add(bindingOverview(owner.getId(), TargetType.PERSISTENT, agent.getAgentUuid())));
        mentorRepository.findAllByOwnerUserIdOrderByCreatedAtDesc(owner.getId()).forEach(mentor ->
                result.add(bindingOverview(owner.getId(), TargetType.MENTOR, mentor.getMentorUuid())));
        return List.copyOf(result);
    }

    private BindingOverview bindingOverview(Long ownerUserId, TargetType type, String targetUuid) {
        List<SkillRef> skills = boundSkills(ownerUserId, type, targetUuid).stream().map(SkillBindingService::ref).toList();
        return new BindingOverview(type, targetUuid, skills,
                isActiveInAnySession(ownerUserId, type, targetUuid));
    }

    @Transactional(readOnly = true)
    public DeleteImpact deleteImpact(UserEntity owner, String skillUuid) {
        SkillEntity skill = skillRepository.findByOwnerUserIdAndSkillUuid(owner.getId(), skillUuid)
                .orElseThrow(() -> new IllegalArgumentException("Skill 不存在或无权限"));
        Map<String, AffectedAgent> affected = new LinkedHashMap<>();
        for (SkillBindingEntity binding : bindingRepository.findBySkillIdOrderByIdAsc(skill.getId())) {
            if (!owner.getId().equals(binding.getOwnerUserId())) continue;
            TargetType type = TargetType.valueOf(binding.getTargetType());
            String name = switch (type) {
                case PERSISTENT -> persistentAgentRepository
                        .findByAgentUuidAndOwnerUserId(binding.getTargetUuid(), owner.getId())
                        .map(agent -> agent.getCustomName() == null || agent.getCustomName().isBlank()
                                ? agent.getAgentName() : agent.getCustomName()).orElse("已删除的常驻智能体");
                case MENTOR -> mentorRepository.findByMentorUuidAndOwnerUserId(binding.getTargetUuid(), owner.getId())
                        .map(com.multiagent.discuss.db.entity.MentorEntity::getName).orElse("已删除的导师");
                case TEMPORARY -> "临时智能体";
            };
            affected.put(type + ":" + binding.getTargetUuid(),
                    new AffectedAgent(type, binding.getTargetUuid(), name));
        }
        for (Session session : sessions.findAll()) {
            if (!owner.getId().equals(session.getOwnerUserId())) continue;
            session.getAgents().values().stream()
                    .filter(agent -> agent.getSkillUuids().contains(skillUuid))
                    .forEach(agent -> {
                        TargetType type = agent.isMentor() && agent.getMentorUuid() != null
                                ? TargetType.MENTOR
                                : agent.getAgentUuid() != null ? TargetType.PERSISTENT : TargetType.TEMPORARY;
                        String uuid = type == TargetType.MENTOR ? agent.getMentorUuid()
                                : type == TargetType.PERSISTENT ? agent.getAgentUuid() : agent.getId();
                        affected.putIfAbsent(type + ":" + uuid,
                                new AffectedAgent(type, uuid, agent.getName()));
                    });
        }
        return new DeleteImpact(affected.size(), List.copyOf(affected.values()));
    }

    private List<SkillEntity> requireOwnedSkills(Long ownerId, List<String> skillUuids) {
        List<SkillEntity> result = new ArrayList<>();
        for (String uuid : skillUuids) {
            result.add(skillRepository.findByOwnerUserIdAndSkillUuid(ownerId, uuid)
                    .orElseThrow(() -> new IllegalArgumentException("Skill 不存在或无权限: " + uuid)));
        }
        return result;
    }

    private void requireOwnedTarget(Long ownerId, TargetType type, String uuid) {
        boolean exists = switch (type) {
            case PERSISTENT -> persistentAgentRepository.findByAgentUuidAndOwnerUserId(uuid, ownerId).isPresent();
            case MENTOR -> mentorRepository.findByMentorUuidAndOwnerUserId(uuid, ownerId).isPresent();
            case TEMPORARY -> false;
        };
        if (!exists) throw new IllegalArgumentException("智能体不存在或无权限");
    }

    private static SkillRef ref(SkillEntity skill) {
        return new SkillRef(skill.getSkillUuid(), skill.getName(), skill.getDescription());
    }

    public enum TargetType { TEMPORARY, PERSISTENT, MENTOR }
    public record SkillRef(String skillUuid, String name, String description) {}
    public record BindingOverview(TargetType targetType, String targetUuid,
                                  List<SkillRef> skills, boolean activeInSession) {}
    public record AffectedAgent(TargetType targetType, String targetUuid, String name) {}
    public record DeleteImpact(int bindingCount, List<AffectedAgent> agents) {}
}
