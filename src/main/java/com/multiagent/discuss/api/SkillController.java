package com.multiagent.discuss.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiagent.discuss.db.entity.UserEntity;
import com.multiagent.discuss.security.AuthContext;
import com.multiagent.discuss.service.SkillBindingService;
import com.multiagent.discuss.service.SkillImportService;
import com.multiagent.discuss.service.SkillLibraryService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/skills")
public class SkillController {
    private final SkillLibraryService library;
    private final SkillImportService importer;
    private final SkillBindingService bindings;
    private final ObjectMapper objectMapper;

    public SkillController(SkillLibraryService library, SkillImportService importer,
                           SkillBindingService bindings, ObjectMapper objectMapper) {
        this.library = library;
        this.importer = importer;
        this.bindings = bindings;
        this.objectMapper = objectMapper;
    }

    @GetMapping
    public List<SkillLibraryService.SkillView> list() {
        return library.list(AuthContext.require());
    }

    @GetMapping("/{uuid}")
    public SkillLibraryService.SkillDetail detail(@PathVariable String uuid) {
        return library.detail(AuthContext.require(), uuid);
    }

    @PostMapping("/web")
    public SkillLibraryService.SkillView createWeb(@RequestBody WebSkillRequest request) {
        return library.importPackage(AuthContext.require(), packageOf(request.skillMd),
                action(request.conflictAction), request.renameTo, "WEB", null);
    }

    @PutMapping("/{uuid}")
    public SkillLibraryService.SkillView update(@PathVariable String uuid, @RequestBody WebSkillRequest request) {
        return library.update(AuthContext.require(), uuid, packageOf(request.skillMd));
    }

    @GetMapping("/{uuid}/delete-impact")
    public SkillBindingService.DeleteImpact deleteImpact(@PathVariable String uuid) {
        return bindings.deleteImpact(AuthContext.require(), uuid);
    }

    @DeleteMapping("/{uuid}")
    public Map<String, Object> delete(@PathVariable String uuid) {
        UserEntity owner = AuthContext.require();
        bindings.removeDeletedSkillFromTemporaryAgents(owner.getId(), uuid);
        library.delete(owner, uuid);
        return Map.of("deleted", true, "skillUuid", uuid);
    }

    @PostMapping(value = "/zip/scan", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public List<SkillImportService.Candidate> scanZip(@RequestPart("file") MultipartFile file) throws IOException {
        AuthContext.require();
        return importer.scanZip(file.getBytes());
    }

    @PostMapping(value = "/zip/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public List<SkillLibraryService.SkillView> importZip(
            @RequestPart("file") MultipartFile file,
            @RequestParam(required = false) List<String> selectedPaths,
            @RequestParam(defaultValue = "ERROR") String conflictAction,
            @RequestParam(required = false) String renames) throws IOException {
        return importer.importZip(AuthContext.require(), file.getBytes(), selectedPaths,
                action(conflictAction), parseRenames(renames), "ZIP", file.getOriginalFilename());
    }

    @PostMapping("/github/scan")
    public SkillImportService.GitHubScan scanGitHub(@RequestBody GitHubRequest request) {
        AuthContext.require();
        return importer.scanGitHub(request.url);
    }

    @PostMapping("/github/import")
    public List<SkillLibraryService.SkillView> importGitHub(@RequestBody GitHubRequest request) {
        return importer.importGitHub(AuthContext.require(), request.url, request.selectedPaths,
                action(request.conflictAction), request.renames);
    }

    @GetMapping("/bindings")
    public List<SkillBindingService.SkillRef> bindings(
            @RequestParam SkillBindingService.TargetType targetType,
            @RequestParam String targetUuid,
            @RequestParam(required = false) String sessionId) {
        return bindings.current(AuthContext.require(), targetType, targetUuid, sessionId);
    }

    @GetMapping("/bindings/overview")
    public List<SkillBindingService.BindingOverview> bindingOverview() {
        return bindings.overview(AuthContext.require());
    }

    @PutMapping("/bindings")
    public List<SkillBindingService.SkillRef> replaceBindings(@RequestBody BindingRequest request) {
        return bindings.replace(AuthContext.require(), request.targetType, request.targetUuid,
                request.sessionId, request.skillUuids);
    }

    private static Map<String, byte[]> packageOf(String markdown) {
        if (markdown == null) throw new IllegalArgumentException("缺少 SKILL.md 内容");
        return Map.of("SKILL.md", markdown.getBytes(StandardCharsets.UTF_8));
    }

    private static SkillLibraryService.ConflictAction action(String value) {
        try {
            return SkillLibraryService.ConflictAction.valueOf(
                    value == null ? "ERROR" : value.trim().toUpperCase());
        } catch (Exception e) {
            throw new IllegalArgumentException("conflictAction 必须是 ERROR、OVERWRITE 或 RENAME");
        }
    }

    private Map<String, String> parseRenames(String value) {
        if (value == null || value.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(value, new TypeReference<>() {});
        } catch (IOException e) {
            throw new IllegalArgumentException("renames 必须是 JSON 对象", e);
        }
    }

    public static class WebSkillRequest {
        public String skillMd;
        public String conflictAction;
        public String renameTo;
    }

    public static class GitHubRequest {
        public String url;
        public List<String> selectedPaths;
        public String conflictAction;
        public Map<String, String> renames;
    }

    public static class BindingRequest {
        public SkillBindingService.TargetType targetType;
        public String targetUuid;
        public String sessionId;
        public List<String> skillUuids;
    }
}
