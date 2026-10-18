package com.multiagent.discuss.service;

import com.multiagent.discuss.db.entity.SkillEntity;
import com.multiagent.discuss.db.entity.SkillFileEntity;
import com.multiagent.discuss.db.entity.UserEntity;
import com.multiagent.discuss.db.repo.SkillRepository;
import com.multiagent.discuss.db.repo.SkillFileRepository;
import com.multiagent.discuss.db.repo.SkillBindingRepository;
import com.multiagent.discuss.domain.DomainAgentConfig;
import com.multiagent.discuss.domain.Session;
import com.multiagent.discuss.knowledge.InMemorySessionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SkillLibraryServiceTest {

    private final InMemorySessionRepository sessions = new InMemorySessionRepository();

    @Mock
    private SkillRepository skillRepository;

    @Mock
    private SkillFileRepository skillFileRepository;

    @Mock
    private SkillBindingRepository skillBindingRepository;

    @Test
    void accountCanCreateAValidSkillFromSkillMarkdown() {
        UserEntity owner = new UserEntity();
        ReflectionTestUtils.setField(owner, "id", 42L);
        when(skillRepository.save(any(SkillEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        SkillLibraryService service = service();
        String markdown = """
                ---
                name: requirements-review
                description: Review requirements for missing decisions.
                ---
                # Requirements review
                Ask focused questions before implementation.
                """;

        SkillLibraryService.SkillView created = service.create(
                owner,
                Map.of("SKILL.md", markdown.getBytes(java.nio.charset.StandardCharsets.UTF_8))
        );

        ArgumentCaptor<SkillEntity> saved = ArgumentCaptor.forClass(SkillEntity.class);
        org.mockito.Mockito.verify(skillRepository).save(saved.capture());
        assertEquals(42L, saved.getValue().getOwnerUserId());
        assertEquals("requirements-review", created.name());
        assertEquals("Review requirements for missing decisions.", created.description());
        assertNotNull(created.skillUuid());
    }

    @Test
    void rejectsPackagePathsThatEscapeTheSkillRoot() {
        UserEntity owner = new UserEntity();
        ReflectionTestUtils.setField(owner, "id", 42L);
        String markdown = "---\nname: safe\ndescription: safe package\n---\n";

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                service().create(owner, Map.of(
                        "SKILL.md", markdown.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        "../outside.txt", "secret".getBytes(java.nio.charset.StandardCharsets.UTF_8)
                )));

        assertEquals("Skill 包含不安全路径: ../outside.txt", error.getMessage());
    }

    @Test
    void overwriteKeepsTheExistingSkillIdentityForBoundAgents() {
        UserEntity owner = new UserEntity();
        ReflectionTestUtils.setField(owner, "id", 42L);
        SkillEntity existing = new SkillEntity();
        existing.setOwnerUserId(42L);
        existing.setSkillUuid("existing-skill-uuid");
        existing.setName("reviewer");
        existing.setDescription("Old description");
        existing.setSkillMd("old");
        existing.setSourceType("WEB");
        when(skillRepository.findByOwnerUserIdAndNameIgnoreCase(42L, "reviewer"))
                .thenReturn(Optional.of(existing));
        when(skillRepository.save(existing)).thenReturn(existing);
        String replacement = "---\nname: reviewer\ndescription: New description\n---\nnew";

        SkillLibraryService.SkillView result = service().importPackage(
                owner,
                Map.of("SKILL.md", replacement.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                SkillLibraryService.ConflictAction.OVERWRITE,
                null,
                "ZIP",
                null
        );

        assertEquals("existing-skill-uuid", result.skillUuid());
        assertEquals("New description", existing.getDescription());
        assertEquals(replacement, existing.getSkillMd());
    }

    @Test
    void storesReferenceAssetsAndScriptsAsInertPackageFiles() {
        UserEntity owner = new UserEntity();
        ReflectionTestUtils.setField(owner, "id", 42L);
        String markdown = "---\nname: package\ndescription: package files\n---\n";
        when(skillRepository.save(any(SkillEntity.class))).thenAnswer(invocation -> {
            SkillEntity entity = invocation.getArgument(0);
            ReflectionTestUtils.setField(entity, "id", 7L);
            return entity;
        });

        service().create(owner, Map.of(
                "SKILL.md", markdown.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                "references/checklist.md", "check".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                "scripts/audit.sh", "exit 1".getBytes(java.nio.charset.StandardCharsets.UTF_8)
        ));

        ArgumentCaptor<SkillFileEntity> files = ArgumentCaptor.forClass(SkillFileEntity.class);
        org.mockito.Mockito.verify(skillFileRepository, org.mockito.Mockito.times(2)).save(files.capture());
        assertEquals(java.util.Set.of("references/checklist.md", "scripts/audit.sh"),
                files.getAllValues().stream().map(SkillFileEntity::getPath).collect(java.util.stream.Collectors.toSet()));
        assertEquals(java.util.Set.of(false),
                files.getAllValues().stream().map(SkillFileEntity::isExecutable).collect(java.util.stream.Collectors.toSet()));
    }

    @Test
    void editingSkillMarkdownKeepsImportedPackageFiles() {
        UserEntity owner = new UserEntity();
        ReflectionTestUtils.setField(owner, "id", 42L);
        SkillEntity existing = new SkillEntity();
        ReflectionTestUtils.setField(existing, "id", 7L);
        existing.setOwnerUserId(42L);
        existing.setSkillUuid("skill-uuid");
        existing.setName("reviewer");
        existing.setDescription("Old");
        existing.setSkillMd("old");
        when(skillRepository.findByOwnerUserIdAndSkillUuid(42L, "skill-uuid"))
                .thenReturn(Optional.of(existing));
        when(skillRepository.findByOwnerUserIdAndNameIgnoreCase(42L, "reviewer"))
                .thenReturn(Optional.of(existing));
        when(skillRepository.save(existing)).thenReturn(existing);
        String edited = "---\nname: reviewer\ndescription: Edited\n---\nnew";

        service().update(
                owner, "skill-uuid", Map.of("SKILL.md", edited.getBytes(java.nio.charset.StandardCharsets.UTF_8)));

        verify(skillFileRepository, never()).deleteBySkillId(7L);
        verify(skillFileRepository, never()).save(any(SkillFileEntity.class));
        assertEquals(edited, existing.getSkillMd());
    }

    @Test
    void parsesYamlBlockDescriptionsAndRejectsNestedMetadata() {
        SkillLibraryService.Metadata valid = SkillLibraryService.parseMetadata("""
                ---
                name: yaml-skill
                description: |
                  First line.
                  Second line.
                ---
                body
                """);
        assertEquals("yaml-skill", valid.name());
        assertEquals("First line.\nSecond line.", valid.description());

        assertThrows(IllegalArgumentException.class, () -> SkillLibraryService.parseMetadata("""
                ---
                metadata:
                  name: hidden
                  description: hidden
                ---
                """));
    }

    @Test
    void editingRejectsSkillMarkdownOver256Kb() {
        UserEntity owner = new UserEntity();
        ReflectionTestUtils.setField(owner, "id", 42L);
        byte[] oversized = new byte[256 * 1024 + 1];
        assertThrows(IllegalArgumentException.class, () ->
                service()
                        .update(owner, "skill-uuid", Map.of("SKILL.md", oversized)));
    }

    @Test
    void rejectsEditingOrDeletingSkillUsedByActiveSeat() {
        UserEntity owner = new UserEntity();
        ReflectionTestUtils.setField(owner, "id", 42L);
        SkillEntity existing = new SkillEntity();
        ReflectionTestUtils.setField(existing, "id", 7L);
        existing.setOwnerUserId(42L);
        existing.setSkillUuid("skill-uuid");
        existing.setName("reviewer");
        when(skillRepository.findByOwnerUserIdAndSkillUuid(42L, "skill-uuid"))
                .thenReturn(Optional.of(existing));

        Session session = new Session("session-1", null, true, false, 20, 3);
        session.setOwnerUserId(42L);
        DomainAgentConfig agent = new DomainAgentConfig("agent-1", "session-1", "Agent", null, null, "model", "persona");
        agent.setSkillUuids(java.util.List.of("skill-uuid"));
        session.getAgents().put(agent.getId(), agent);
        sessions.saveSession(session);

        String edited = "---\nname: reviewer\ndescription: Edited\n---\nnew";
        assertThrows(IllegalStateException.class, () -> service().update(
                owner, "skill-uuid", Map.of("SKILL.md", edited.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        assertThrows(IllegalStateException.class, () -> service().delete(owner, "skill-uuid"));
        verify(skillRepository, never()).delete(existing);
    }

    private SkillLibraryService service() {
        return new SkillLibraryService(skillRepository, skillFileRepository, skillBindingRepository, sessions);
    }
}
