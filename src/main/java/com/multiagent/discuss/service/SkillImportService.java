package com.multiagent.discuss.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiagent.discuss.db.entity.UserEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Service
public class SkillImportService {
    private static final int MAX_DOWNLOAD_BYTES = 20 * 1024 * 1024;
    private static final int MAX_ARCHIVE_ENTRIES = 1000;
    private static final int MAX_EXPANDED_BYTES = 10 * 1024 * 1024;

    private final SkillLibraryService library;
    private final ObjectMapper objectMapper;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    public SkillImportService(SkillLibraryService library, ObjectMapper objectMapper) {
        this.library = library;
        this.objectMapper = objectMapper;
    }

    public static List<Candidate> scanZip(byte[] zipBytes) {
        Map<String, byte[]> archive = readZip(zipBytes);
        List<Candidate> result = new ArrayList<>();
        for (Map.Entry<String, byte[]> entry : archive.entrySet()) {
            if (!entry.getKey().equals("SKILL.md") && !entry.getKey().endsWith("/SKILL.md")) continue;
            String directory = entry.getKey().equals("SKILL.md") ? "" : entry.getKey().substring(0, entry.getKey().length() - 9);
            SkillLibraryService.Metadata metadata = SkillLibraryService.parseMetadata(
                    new String(entry.getValue(), java.nio.charset.StandardCharsets.UTF_8));
            result.add(new Candidate(directory, metadata.name(), metadata.description()));
        }
        if (result.isEmpty()) throw new IllegalArgumentException("压缩包中没有找到 SKILL.md");
        return result;
    }

    @Transactional
    public List<SkillLibraryService.SkillView> importZip(UserEntity owner, byte[] zipBytes,
                                                           List<String> selectedPaths,
                                                           SkillLibraryService.ConflictAction action,
                                                           Map<String, String> renames,
                                                           String sourceType, String sourceUrl) {
        Map<String, byte[]> archive = readZip(zipBytes);
        List<Candidate> candidates = scanZip(zipBytes);
        Set<String> selected = selectedPaths == null || selectedPaths.isEmpty()
                ? candidates.stream().map(Candidate::path).collect(java.util.stream.Collectors.toSet())
                : Set.copyOf(selectedPaths);
        List<SkillLibraryService.SkillView> result = new ArrayList<>();
        for (Candidate candidate : candidates) {
            if (!selected.contains(candidate.path())) continue;
            Map<String, byte[]> files = packageFiles(archive, candidate.path());
            result.add(library.importPackage(owner, files, action,
                    renames == null ? null : renames.get(candidate.path()), sourceType, sourceUrl));
        }
        if (result.isEmpty()) throw new IllegalArgumentException("没有选择有效的 Skill");
        return result;
    }

    public GitHubScan scanGitHub(String url) {
        GitHubLocation location = parseGitHubUrl(url);
        RepoInfo repo = repositoryInfo(location.owner(), location.repo());
        String ref = location.ref() == null ? repo.defaultBranch() : location.ref();
        byte[] zip = download("https://codeload.github.com/" + location.owner() + "/" + location.repo()
                + "/zip/refs/heads/" + ref);
        List<Candidate> visible = visibleCandidates(zip, location.subdir());
        if (visible.isEmpty()) throw new IllegalArgumentException("指定的 GitHub 路径中没有找到 Skill");
        return new GitHubScan(url, ref, visible);
    }

    @Transactional
    public List<SkillLibraryService.SkillView> importGitHub(UserEntity owner, String url,
                                                              List<String> selectedPaths,
                                                              SkillLibraryService.ConflictAction action,
                                                              Map<String, String> renames) {
        GitHubLocation location = parseGitHubUrl(url);
        RepoInfo repo = repositoryInfo(location.owner(), location.repo());
        String ref = location.ref() == null ? repo.defaultBranch() : location.ref();
        byte[] zip = download("https://codeload.github.com/" + location.owner() + "/" + location.repo()
                + "/zip/refs/heads/" + ref);
        List<Candidate> visible = visibleCandidates(zip, location.subdir());
        Set<String> allowed = visible.stream().map(Candidate::path).collect(java.util.stream.Collectors.toSet());
        List<String> effectiveSelection = selectedPaths == null || selectedPaths.isEmpty()
                ? visible.stream().map(Candidate::path).toList()
                : selectedPaths;
        if (!allowed.containsAll(effectiveSelection)) {
            throw new IllegalArgumentException("选择的 Skill 不在指定 GitHub 路径内");
        }
        return importZip(owner, zip, effectiveSelection, action, renames, "GITHUB", url);
    }

    private List<Candidate> visibleCandidates(byte[] zip, String requestedSubdir) {
        String subdir = requestedSubdir == null ? "" : trimSlashes(requestedSubdir);
        List<Candidate> visible = scanZip(zip).stream().filter(candidate -> {
            String withoutRoot = stripArchiveRoot(candidate.path());
            return subdir.isEmpty() || withoutRoot.equals(subdir) || withoutRoot.startsWith(subdir + "/");
        }).toList();
        if (visible.isEmpty()) throw new IllegalArgumentException("指定的 GitHub 路径中没有找到 Skill");
        return visible;
    }

    private RepoInfo repositoryInfo(String owner, String repo) {
        byte[] body = download("https://api.github.com/repos/" + owner + "/" + repo);
        try {
            JsonNode json = objectMapper.readTree(body);
            if (json.path("private").asBoolean(false)) throw new IllegalArgumentException("第一版只支持公开 GitHub 仓库");
            String branch = json.path("default_branch").asText();
            if (branch.isBlank()) throw new IllegalArgumentException("无法识别 GitHub 仓库默认分支");
            return new RepoInfo(branch);
        } catch (IOException e) {
            throw new IllegalArgumentException("GitHub 仓库信息解析失败", e);
        }
    }

    private byte[] download(String url) {
        URI current = URI.create(url);
        try {
            for (int redirects = 0; redirects <= 3; redirects++) {
                validateDownloadUri(current);
                HttpRequest request = HttpRequest.newBuilder(current).timeout(Duration.ofSeconds(30))
                        .header("User-Agent", "multi-agent-discuss-skill-importer")
                        .header("Accept", "application/vnd.github+json").GET().build();
                HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
                int status = response.statusCode();
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    try (InputStream ignored = response.body()) {
                        String location = response.headers().firstValue("Location")
                                .orElseThrow(() -> new IllegalArgumentException("GitHub 重定向缺少目标地址"));
                        current = current.resolve(location);
                    }
                    continue;
                }
                if (status == 404) {
                    response.body().close();
                    throw new IllegalArgumentException("公开 GitHub 仓库或路径不存在");
                }
                if (status < 200 || status >= 300) {
                    response.body().close();
                    throw new IllegalArgumentException("GitHub 请求失败，状态码 " + status);
                }
                return readLimitedWithin(response.body(), MAX_DOWNLOAD_BYTES, Duration.ofSeconds(30));
            }
            throw new IllegalArgumentException("GitHub 重定向次数过多");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("GitHub 下载被中断", e);
        } catch (IOException e) {
            throw new IllegalStateException("GitHub 下载失败: " + e.getMessage(), e);
        }
    }

    static void validateDownloadUri(URI uri) {
        String host = uri.getHost();
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getUserInfo() != null || uri.getPort() != -1
                || !("api.github.com".equalsIgnoreCase(host) || "codeload.github.com".equalsIgnoreCase(host))) {
            throw new IllegalArgumentException("GitHub 下载重定向到了不受信任的地址");
        }
    }

    static byte[] readLimitedWithin(InputStream input, int limit, Duration timeout) {
        try (input) {
            CompletableFuture<byte[]> read = CompletableFuture.supplyAsync(() -> {
                try { return readLimited(input, limit); }
                catch (IOException e) { throw new CompletionException(e); }
            });
            try {
                return read.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                read.cancel(true);
                throw new IllegalStateException("GitHub 下载响应超时", e);
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof IllegalArgumentException argument) throw argument;
                throw new IllegalStateException("GitHub 下载失败", cause);
            } catch (InterruptedException e) {
                read.cancel(true);
                Thread.currentThread().interrupt();
                throw new IllegalStateException("GitHub 下载被中断", e);
            }
        } catch (IOException e) {
            throw new IllegalStateException("GitHub 下载流关闭失败", e);
        }
    }

    private static Map<String, byte[]> readZip(byte[] bytes) {
        if (bytes == null || bytes.length == 0) throw new IllegalArgumentException("ZIP 文件为空");
        if (bytes.length > MAX_DOWNLOAD_BYTES) throw new IllegalArgumentException("ZIP 文件不能超过 20MB");
        rejectSymbolicLinks(bytes);
        Map<String, byte[]> entries = new LinkedHashMap<>();
        int count = 0;
        int total = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                if (++count > MAX_ARCHIVE_ENTRIES) throw new IllegalArgumentException("ZIP 文件数量过多");
                String name = safePath(entry.getName());
                byte[] content = readLimited(zip, MAX_EXPANDED_BYTES - total);
                total += content.length;
                if (total > MAX_EXPANDED_BYTES) throw new IllegalArgumentException("ZIP 解压后不能超过 10MB");
                if (entries.putIfAbsent(name, content) != null) throw new IllegalArgumentException("ZIP 包含重复路径: " + name);
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("ZIP 文件无法读取", e);
        }
        return entries;
    }

    /** ZipInputStream 不暴露 Unix mode，因此先检查中央目录的外部属性。 */
    private static void rejectSymbolicLinks(byte[] zip) {
        ByteBuffer buffer = ByteBuffer.wrap(zip).order(ByteOrder.LITTLE_ENDIAN);
        for (int offset = 0; offset + 46 <= zip.length; offset++) {
            if (buffer.getInt(offset) != 0x02014b50) continue;
            int versionMadeBy = Short.toUnsignedInt(buffer.getShort(offset + 4));
            int creatorOs = versionMadeBy >>> 8;
            int nameLength = Short.toUnsignedInt(buffer.getShort(offset + 28));
            int extraLength = Short.toUnsignedInt(buffer.getShort(offset + 30));
            int commentLength = Short.toUnsignedInt(buffer.getShort(offset + 32));
            if (offset + 46 + nameLength + extraLength + commentLength > zip.length) {
                throw new IllegalArgumentException("ZIP 中央目录损坏");
            }
            int unixMode = buffer.getInt(offset + 38) >>> 16;
            if (creatorOs == 3 && (unixMode & 0170000) == 0120000) {
                String name = new String(zip, offset + 46, nameLength, StandardCharsets.UTF_8);
                throw new IllegalArgumentException("ZIP 包含符号链接: " + name);
            }
            offset += 45 + nameLength + extraLength + commentLength;
        }
    }

    private static byte[] readLimited(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        int total = 0;
        while ((read = input.read(buffer)) >= 0) {
            total += read;
            if (total > limit) throw new IllegalArgumentException("下载或解压内容超过大小限制");
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private static Map<String, byte[]> packageFiles(Map<String, byte[]> archive, String directory) {
        String prefix = directory.isEmpty() ? "" : directory + "/";
        List<String> nestedSkillPrefixes = archive.keySet().stream()
                .filter(path -> path.startsWith(prefix) && path.endsWith("/SKILL.md"))
                .map(path -> path.substring(0, path.length() - "SKILL.md".length()))
                .filter(candidatePrefix -> !candidatePrefix.equals(prefix))
                .toList();
        Map<String, byte[]> files = new LinkedHashMap<>();
        archive.forEach((path, content) -> {
            boolean belongsToNestedSkill = nestedSkillPrefixes.stream().anyMatch(path::startsWith);
            if (path.startsWith(prefix) && !belongsToNestedSkill) {
                String relative = path.substring(prefix.length());
                if (!relative.isBlank()) files.put(relative, content);
            }
        });
        return files;
    }

    private static String safePath(String raw) {
        String path = raw.replace('\\', '/');
        if (path.startsWith("/") || path.matches("^[A-Za-z]:/.*")
                || java.util.Arrays.asList(path.split("/", -1)).contains("..")) {
            throw new IllegalArgumentException("ZIP 包含不安全路径: " + raw);
        }
        return trimSlashes(path);
    }

    static GitHubLocation parseGitHubUrl(String value) {
        try {
            URI uri = URI.create(value);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || !"github.com".equalsIgnoreCase(uri.getHost())) {
                throw new IllegalArgumentException("只支持 https://github.com 的公开仓库地址");
            }
            if (uri.getUserInfo() != null || uri.getPort() != -1 || uri.getQuery() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException("GitHub 地址不能包含账号、端口、查询参数或片段");
            }
            String[] parts = trimSlashes(uri.getPath()).split("/");
            if (parts.length < 2 || !validGitHubSegment(parts[0]) || !validGitHubSegment(parts[1])) {
                throw new IllegalArgumentException("GitHub 仓库地址格式不正确");
            }
            String repo = parts[1].toLowerCase(Locale.ROOT).endsWith(".git")
                    ? parts[1].substring(0, parts[1].length() - 4) : parts[1];
            if (parts.length >= 4 && "tree".equals(parts[2])) {
                if (!validGitHubSegment(parts[3])) throw new IllegalArgumentException("GitHub 分支格式不正确");
                String subdir = parts.length > 4 ? String.join("/", java.util.Arrays.copyOfRange(parts, 4, parts.length)) : "";
                if (!subdir.isEmpty() && java.util.Arrays.stream(subdir.split("/", -1)).anyMatch(part -> !validGitHubSegment(part))) {
                    throw new IllegalArgumentException("GitHub 子目录格式不正确");
                }
                return new GitHubLocation(parts[0], repo, parts[3], subdir);
            }
            if (parts.length != 2) throw new IllegalArgumentException("GitHub 地址必须指向仓库或其子目录");
            return new GitHubLocation(parts[0], repo, null, null);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("GitHub 地址格式不正确", e);
        }
    }

    private static boolean validGitHubSegment(String value) {
        return value != null && !value.isBlank() && !".".equals(value) && !"..".equals(value)
                && value.matches("[A-Za-z0-9_.-]+");
    }

    private static String stripArchiveRoot(String path) {
        int slash = path.indexOf('/');
        return slash < 0 ? "" : path.substring(slash + 1);
    }

    private static String trimSlashes(String value) {
        String result = value == null ? "" : value;
        while (result.startsWith("/")) result = result.substring(1);
        while (result.endsWith("/")) result = result.substring(0, result.length() - 1);
        return result;
    }

    public record Candidate(String path, String name, String description) {}
    public record GitHubScan(String url, String ref, List<Candidate> skills) {}
    record GitHubLocation(String owner, String repo, String ref, String subdir) {}
    private record RepoInfo(String defaultBranch) {}
}
