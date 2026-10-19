package com.multiagent.discuss.service;

import com.multiagent.discuss.db.entity.SkillEntity;
import com.multiagent.discuss.db.entity.SkillFileEntity;
import com.multiagent.discuss.db.entity.UserEntity;
import com.multiagent.discuss.db.repo.SkillRepository;
import com.multiagent.discuss.db.repo.SkillFileRepository;
import com.multiagent.discuss.db.repo.SkillBindingRepository;
import com.multiagent.discuss.knowledge.InMemorySessionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.List;
import java.util.UUID;

@Service
public class SkillLibraryService {

    private static final int MAX_SKILL_MD_BYTES = 256 * 1024;
    private static final int MAX_FILE_COUNT = 100;
    private static final long MAX_PACKAGE_BYTES = 10L * 1024 * 1024;

    private final SkillRepository skillRepository;
    private final SkillFileRepository skillFileRepository;
    private final SkillBindingRepository skillBindingRepository;
    private final InMemorySessionRepository sessions;

    public SkillLibraryService(SkillRepository skillRepository, SkillFileRepository skillFileRepository,
                               SkillBindingRepository skillBindingRepository,
                               InMemorySessionRepository sessions) {
        this.skillRepository = skillRepository;
        this.skillFileRepository = skillFileRepository;
        this.skillBindingRepository = skillBindingRepository;
        this.sessions = sessions;
    }

    @Transactional
    public SkillView create(UserEntity owner, Map<String, byte[]> files) {
        return importPackage(owner, files, ConflictAction.ERROR, null, "WEB", null);
    }

    @Transactional
    public SkillView importPackage(UserEntity owner, Map<String, byte[]> files,
                                   ConflictAction conflictAction, String renameTo,
                                   String sourceType, String sourceUrl) {
        if (owner == null || owner.getId() == null) {
            throw new IllegalArgumentException("必须登录后才能创建 Skill");
        }
        validatePackagePathsAndSize(files);
        byte[] raw = files == null ? null : files.get("SKILL.md");
        if (raw == null || raw.length == 0) {
            throw new IllegalArgumentException("Skill 包缺少 SKILL.md");
        }
        if (raw.length > MAX_SKILL_MD_BYTES) {
            throw new IllegalArgumentException("SKILL.md 不能超过 256KB");
        }
        String markdown = new String(raw, StandardCharsets.UTF_8);
        Metadata metadata = parseMetadata(markdown);

        SkillEntity duplicate = skillRepository
                .findByOwnerUserIdAndNameIgnoreCase(owner.getId(), metadata.name())
                .orElse(null);
        if (duplicate != null && conflictAction == ConflictAction.ERROR) {
            throw new IllegalArgumentException("账户 Skill 库中已存在同名 Skill");
        }
        if (duplicate != null && conflictAction == ConflictAction.OVERWRITE) {
            requireNotUsedByActiveSeat(owner.getId(), duplicate.getSkillUuid());
            duplicate.setDescription(metadata.description());
            duplicate.setSkillMd(markdown);
            duplicate.setSourceType(normalizeSourceType(sourceType));
            duplicate.setSourceUrl(sourceUrl);
            SkillEntity saved = skillRepository.save(duplicate);
            skillFileRepository.deleteBySkillId(saved.getId());
            saveFiles(saved, files);
            return toView(saved);
        }
        if (duplicate != null && conflictAction == ConflictAction.RENAME) {
            if (!isValidName(renameTo)) {
                throw new IllegalArgumentException("重命名安装必须提供有效的新名称");
            }
            if (skillRepository.findByOwnerUserIdAndNameIgnoreCase(owner.getId(), renameTo.trim()).isPresent()) {
                throw new IllegalArgumentException("账户 Skill 库中已存在重命名后的 Skill");
            }
            metadata = new Metadata(renameTo.trim(), metadata.description());
            markdown = replaceFrontmatterName(markdown, metadata.name());
        }

        SkillEntity entity = new SkillEntity();
        entity.setSkillUuid(UUID.randomUUID().toString());
        entity.setOwnerUserId(owner.getId());
        entity.setName(metadata.name());
        entity.setDescription(metadata.description());
        entity.setSkillMd(markdown);
        entity.setSourceType(normalizeSourceType(sourceType));
        entity.setSourceUrl(sourceUrl);
        SkillEntity saved = skillRepository.save(entity);
        saveFiles(saved, files);
        return toView(saved);
    }

    private void saveFiles(SkillEntity skill, Map<String, byte[]> files) {
        if (skill.getId() == null || files == null) return;
        files.entrySet().stream()
                .filter(entry -> !"SKILL.md".equals(entry.getKey()))
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    SkillFileEntity file = new SkillFileEntity();
                    file.setSkillId(skill.getId());
                    file.setPath(entry.getKey().replace('\\', '/'));
                    file.setContent(entry.getValue());
                    file.setExecutable(false);
                    skillFileRepository.save(file);
                });
    }

    private static String normalizeSourceType(String sourceType) {
        String value = sourceType == null ? "WEB" : sourceType.trim().toUpperCase();
        return switch (value) {
            case "WEB", "ZIP", "GITHUB" -> value;
            default -> throw new IllegalArgumentException("不支持的 Skill 来源: " + sourceType);
        };
    }

    static String replaceFrontmatterName(String markdown, String name) {
        String yamlScalar = "\"" + name.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        int start = 0;
        int lineNumber = 0;
        while (start <= markdown.length()) {
            int newline = markdown.indexOf('\n', start);
            int lineEnd = newline < 0 ? markdown.length() : newline;
            int contentEnd = lineEnd > start && markdown.charAt(lineEnd - 1) == '\r' ? lineEnd - 1 : lineEnd;
            String line = markdown.substring(start, contentEnd);
            if (lineNumber == 0 && !"---".equals(line.strip())) return markdown;
            if (lineNumber > 0 && "---".equals(line.strip())) break;
            int colon = line.indexOf(':');
            if (lineNumber > 0 && colon >= 0 && "name".equals(line.substring(0, colon).strip())) {
                return markdown.substring(0, start) + "name: " + yamlScalar + markdown.substring(contentEnd);
            }
            if (newline < 0) break;
            start = newline + 1;
            lineNumber++;
        }
        return markdown;
    }

    private static void validatePackagePathsAndSize(Map<String, byte[]> files) {
        if (files == null) return;
        if (files.size() > MAX_FILE_COUNT) {
            throw new IllegalArgumentException("Skill 包文件数量不能超过 " + MAX_FILE_COUNT);
        }
        long total = 0;
        for (Map.Entry<String, byte[]> entry : files.entrySet()) {
            String path = entry.getKey() == null ? "" : entry.getKey().replace('\\', '/');
            boolean unsafe = path.isBlank()
                    || path.startsWith("/")
                    || path.matches("^[A-Za-z]:/.*")
                    || java.util.Arrays.asList(path.split("/", -1)).contains("..")
                    || java.util.Arrays.asList(path.split("/", -1)).contains("");
            if (unsafe) {
                throw new IllegalArgumentException("Skill 包含不安全路径: " + entry.getKey());
            }
            byte[] content = entry.getValue();
            if (content == null) {
                throw new IllegalArgumentException("Skill 文件内容不能为空: " + path);
            }
            total += content.length;
            if (total > MAX_PACKAGE_BYTES) {
                throw new IllegalArgumentException("Skill 包解压后不能超过 10MB");
            }
        }
    }

    static Metadata parseMetadata(String markdown) {
        String normalized = markdown == null ? "" : markdown.replace("\r\n", "\n");
        if (!normalized.startsWith("---\n")) {
            throw new IllegalArgumentException("SKILL.md 必须以 YAML frontmatter 开头");
        }
        int end = normalized.indexOf("\n---\n", 4);
        if (end < 0 && normalized.endsWith("\n---")) end = normalized.length() - 4;
        if (end < 0) {
            throw new IllegalArgumentException("SKILL.md 的 YAML frontmatter 未闭合");
        }
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(0);
        options.setCodePointLimit(MAX_SKILL_MD_BYTES);
        Object parsed;
        try {
            parsed = new Yaml(new SafeConstructor(options)).load(normalized.substring(4, end));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("SKILL.md 的 YAML frontmatter 无效", e);
        }
        if (!(parsed instanceof Map<?, ?> metadata)) {
            throw new IllegalArgumentException("SKILL.md 的 YAML frontmatter 必须是对象");
        }
        Object rawName = metadata.get("name");
        Object rawDescription = metadata.get("description");
        String name = rawName instanceof String value ? value : null;
        String description = rawDescription instanceof String value ? value : null;
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("SKILL.md 必须声明 name");
        }
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("SKILL.md 必须声明 description");
        }
        if (!isValidName(name) || description.length() > 1000) {
            throw new IllegalArgumentException("Skill 名称或简介过长");
        }
        return new Metadata(name.trim(), description.trim());
    }

    private static boolean isValidName(String name) {
        if (name == null || name.isBlank() || name.length() > 128) return false;
        return name.chars().noneMatch(ch -> Character.isISOControl(ch));
    }

    private static SkillView toView(SkillEntity entity) {
        return new SkillView(entity.getSkillUuid(), entity.getName(), entity.getDescription(),
                entity.getSourceType(), entity.getSourceUrl(), entity.getCreatedAt(), entity.getUpdatedAt());
    }

    @Transactional(readOnly = true)
    public List<SkillView> list(UserEntity owner) {
        return skillRepository.findByOwnerUserIdOrderByNameAsc(owner.getId()).stream()
                .map(SkillLibraryService::toView).toList();
    }

    @Transactional(readOnly = true)
    public SkillDetail detail(UserEntity owner, String skillUuid) {
        SkillEntity skill = requireOwned(owner, skillUuid);
        List<FileView> files = skillFileRepository.findBySkillIdOrderByPathAsc(skill.getId()).stream()
                .map(file -> new FileView(file.getPath(), file.getContent().length, false))
                .toList();
        return new SkillDetail(toView(skill), skill.getSkillMd(), files,
                skillBindingRepository.countBySkillId(skill.getId()));
    }

    @Transactional
    public SkillView update(UserEntity owner, String skillUuid, Map<String, byte[]> files) {
        validatePackagePathsAndSize(files);
        byte[] raw = files == null ? null : files.get("SKILL.md");
        if (raw == null) throw new IllegalArgumentException("Skill 包缺少 SKILL.md");
        if (raw.length > MAX_SKILL_MD_BYTES) throw new IllegalArgumentException("SKILL.md 不能超过 256KB");
        String markdown = new String(raw, StandardCharsets.UTF_8);
        Metadata metadata = parseMetadata(markdown);
        SkillEntity skill = requireOwned(owner, skillUuid);
        requireNotUsedByActiveSeat(owner.getId(), skillUuid);
        skillRepository.findByOwnerUserIdAndNameIgnoreCase(owner.getId(), metadata.name())
                .filter(other -> !other.getSkillUuid().equals(skillUuid))
                .ifPresent(other -> { throw new IllegalArgumentException("账户 Skill 库中已存在同名 Skill"); });
        skill.setName(metadata.name());
        skill.setDescription(metadata.description());
        skill.setSkillMd(markdown);
        // The web editor edits SKILL.md only. Keep references/assets/scripts from the
        // originally imported package instead of silently discarding them.
        return toView(skillRepository.save(skill));
    }

    @Transactional
    public void delete(UserEntity owner, String skillUuid) {
        SkillEntity skill = requireOwned(owner, skillUuid);
        requireNotUsedByActiveSeat(owner.getId(), skillUuid);
        skillBindingRepository.deleteBySkillId(skill.getId());
        skillFileRepository.deleteBySkillId(skill.getId());
        skillRepository.delete(skill);
    }

    private SkillEntity requireOwned(UserEntity owner, String skillUuid) {
        return skillRepository.findByOwnerUserIdAndSkillUuid(owner.getId(), skillUuid)
                .orElseThrow(() -> new IllegalArgumentException("Skill 不存在或无权限"));
    }

    private void requireNotUsedByActiveSeat(Long ownerUserId, String skillUuid) {
        boolean used = sessions.findAll().stream()
                .filter(session -> ownerUserId.equals(session.getOwnerUserId()))
                .flatMap(session -> session.getAgents().values().stream())
                .anyMatch(agent -> agent.getSkillUuids().contains(skillUuid));
        if (used) {
            throw new IllegalStateException("该 Skill 正被活跃会话使用，结束或移出相关智能体后可修改");
        }
    }

    record Metadata(String name, String description) {}

    public enum ConflictAction { ERROR, OVERWRITE, RENAME }

    public record SkillView(String skillUuid, String name, String description,
                            String sourceType, String sourceUrl,
                            Instant createdAt, Instant updatedAt) {}
    public record FileView(String path, long size, boolean executable) {}
    public record SkillDetail(SkillView skill, String skillMd, List<FileView> files, long bindingCount) {}
}
