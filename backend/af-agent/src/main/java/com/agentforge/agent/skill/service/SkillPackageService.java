package com.agentforge.agent.skill.service;

import com.agentforge.agent.skill.dto.SkillUploadResponse;
import com.agentforge.agent.skill.entity.SkillEntity;
import com.agentforge.agent.skill.mapper.SkillMapper;
import com.agentforge.agent.tool.entity.ToolEntity;
import com.agentforge.agent.tool.mapper.ToolMapper;
import com.agentforge.common.audit.AuditService;
import com.agentforge.common.exception.BizException;
import com.agentforge.common.exception.ErrorCode;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** skillzip 安全导入服务：先解压校验，再原子落盘并注册租户隔离的技能元数据。 */
@Service
@RequiredArgsConstructor
public class SkillPackageService {

    private static final long MAX_PACKAGE_BYTES = 10L * 1024 * 1024;
    private static final long MAX_UNCOMPRESSED_BYTES = 32L * 1024 * 1024;
    private static final int MAX_ENTRIES = 256;
    private static final int MAX_MANIFEST_BYTES = 8 * 1024;
    private static final List<String> DANGEROUS_SUFFIXES = List.of(
            ".exe", ".dll", ".so", ".dylib", ".bat", ".cmd", ".ps1", ".sh", ".bash",
            ".jar", ".class", ".pyc", ".wasm"
    );

    private final SkillMapper skillMapper;
    private final ToolMapper toolMapper;
    private final AuditService auditService;

    @Value("${app.skill.repo-dir:${user.dir}/skill-repo}")
    private String repoDir;

    @Transactional(rollbackFor = Exception.class)
    public SkillUploadResponse importPackage(Long tenantId, MultipartFile file) {
        Path tempRoot = null;
        Path installedRoot = null;
        try {
            validateUpload(file);
            tempRoot = Files.createTempDirectory("agentforge-skillzip-");
            Path extracted = tempRoot.resolve("extracted");
            Files.createDirectories(extracted);
            extractSafely(file, extracted);

            Manifest manifest = readManifest(extracted);
            validateAllowedTools(tenantId, manifest.allowedTools());
            ensureUniqueCode(tenantId, manifest.code());

            Path repoRoot = resolveRepoRoot();
            Files.createDirectories(repoRoot);
            Path tenantRoot = repoRoot.resolve("tenant-" + tenantId).normalize();
            if (!tenantRoot.startsWith(repoRoot)) {
                throw new BizException(ErrorCode.SKILL_PACKAGE_UNSAFE, "技能仓库路径非法");
            }
            Files.createDirectories(tenantRoot);
            installedRoot = tenantRoot.resolve(manifest.code()).normalize();
            if (!installedRoot.startsWith(tenantRoot)
                    || Files.exists(installedRoot, LinkOption.NOFOLLOW_LINKS)) {
                throw new BizException(ErrorCode.SKILL_PACKAGE_DUPLICATE, "技能目录已存在: " + manifest.code());
            }

            moveAtomically(locateSkillDir(extracted), installedRoot);

            SkillEntity entity = new SkillEntity();
            entity.setTenantId(tenantId);
            entity.setSkillCode(manifest.code());
            entity.setName(manifest.name());
            entity.setDescription(manifest.description());
            entity.setTriggersJson("[]");
            entity.setContentJson(null);
            entity.setSkillFileUrl("tenant-" + tenantId + "/" + manifest.code() + "/SKILL.md");
            entity.setVersion(manifest.version());
            entity.setEnabled(1);
            entity.setIsBuiltin(0);
            skillMapper.insert(entity);

            auditService.record("skill.package.import", "skill", String.valueOf(entity.getId()),
                    Map.of("code", manifest.code(), "version", manifest.version(), "file", safeFileName(file)), 1);
            return new SkillUploadResponse(entity.getId(), manifest.code(), manifest.name(), manifest.version(),
                    entity.getSkillFileUrl(), true);
        } catch (BizException e) {
            cleanupTree(installedRoot);
            auditService.record("skill.package.import", "skill", null,
                    Map.of("file", safeFileName(file), "reason", e.getMessage()), 0);
            throw e;
        } catch (Exception e) {
            cleanupTree(installedRoot);
            auditService.record("skill.package.import", "skill", null,
                    Map.of("file", safeFileName(file), "reason", "internal_error"), 0);
            throw new BizException(ErrorCode.SKILL_PACKAGE_INVALID, e);
        } finally {
            cleanupTree(tempRoot);
        }
    }

    private void validateUpload(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BizException(ErrorCode.SKILL_PACKAGE_INVALID, "上传文件不能为空");
        }
        if (!safeFileName(file).toLowerCase().endsWith(".skillzip")) {
            throw new BizException(ErrorCode.SKILL_PACKAGE_INVALID, "仅支持 .skillzip 文件");
        }
        if (file.getSize() > MAX_PACKAGE_BYTES) {
            throw new BizException(ErrorCode.SKILL_PACKAGE_TOO_LARGE);
        }
    }

    private void extractSafely(MultipartFile file, Path target) throws IOException {
        long total = 0;
        int entries = 0;
        try (InputStream input = file.getInputStream();
             ZipInputStream zip = new ZipInputStream(input, StandardCharsets.UTF_8)) {
            ZipEntry entry;
            byte[] buffer = new byte[8192];
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > MAX_ENTRIES) {
                    throw new BizException(ErrorCode.SKILL_PACKAGE_UNSAFE, "ZIP 条目数量超过限制");
                }
                String rawName = entry.getName();
                Path relative = safeZipPath(rawName);
                if (isDangerous(rawName)) {
                    throw new BizException(ErrorCode.SKILL_PACKAGE_UNSAFE, "技能包包含危险文件: " + rawName);
                }
                Path output = target.resolve(relative).normalize();
                if (!output.startsWith(target)) {
                    throw new BizException(ErrorCode.SKILL_PACKAGE_UNSAFE, "ZIP 路径越界");
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(output);
                } else {
                    Files.createDirectories(output.getParent());
                    long fileSize = 0;
                    try (var out = Files.newOutputStream(output)) {
                        int read;
                        while ((read = zip.read(buffer)) != -1) {
                            fileSize += read;
                            total += read;
                            if (fileSize > MAX_UNCOMPRESSED_BYTES || total > MAX_UNCOMPRESSED_BYTES) {
                                throw new BizException(ErrorCode.SKILL_PACKAGE_TOO_LARGE, "解压后内容超过限制");
                            }
                            out.write(buffer, 0, read);
                        }
                    }
                }
                zip.closeEntry();
            }
        }
    }

    private Manifest readManifest(Path extracted) throws IOException {
        List<Path> manifests;
        try (var stream = Files.walk(extracted)) {
            manifests = stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().equalsIgnoreCase("SKILL.md"))
                    .toList();
        }
        if (manifests.size() != 1) {
            throw new BizException(ErrorCode.SKILL_PACKAGE_MISSING_MANIFEST, "技能包必须包含唯一的 SKILL.md");
        }
        Path manifestPath = manifests.get(0);
        byte[] bytes = Files.readAllBytes(manifestPath);
        if (bytes.length > MAX_MANIFEST_BYTES) {
            throw new BizException(ErrorCode.SKILL_CONTENT_TOO_LARGE);
        }
        String raw = new String(bytes, StandardCharsets.UTF_8);
        FrontMatter frontMatter = parseFrontMatter(raw);
        if (frontMatter.yaml() == null) {
            throw new BizException(ErrorCode.SKILL_PACKAGE_INVALID, "SKILL.md 缺少 YAML front matter");
        }
        Object parsed = new Yaml().load(frontMatter.yaml());
        if (!(parsed instanceof Map<?, ?> map)) {
            throw new BizException(ErrorCode.SKILL_PACKAGE_INVALID, "SKILL.md front matter 格式错误");
        }
        String name = requiredText(map, "name");
        String description = requiredText(map, "description");
        String version = requiredText(map, "version");
        List<String> allowedTools = readAllowedTools(map.get("allowed_tools"));
        String code = manifestPath.getParent() == null || manifestPath.getParent().equals(extracted)
                ? slug(name)
                : manifestPath.getParent().getFileName().toString();
        validateCode(code);
        return new Manifest(code, name, description, version, allowedTools);
    }

    private Path locateSkillDir(Path extracted) throws IOException {
        try (var stream = Files.walk(extracted)) {
            Path manifest = stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().equalsIgnoreCase("SKILL.md"))
                    .findFirst()
                    .orElseThrow(() -> new BizException(ErrorCode.SKILL_PACKAGE_MISSING_MANIFEST));
            Path dir = manifest.getParent();
            if (dir == null) throw new BizException(ErrorCode.SKILL_PACKAGE_INVALID, "技能目录无效");
            return dir;
        }
    }

    private void validateAllowedTools(Long tenantId, List<String> allowedTools) {
        if (allowedTools == null) return;
        for (String toolCode : allowedTools) {
            long count = toolMapper.selectCount(new LambdaQueryWrapper<ToolEntity>()
                    .eq(ToolEntity::getTenantId, tenantId)
                    .eq(ToolEntity::getToolCode, toolCode)
                    .eq(ToolEntity::getStatus, 1));
            if (count == 0) {
                throw new BizException(ErrorCode.SKILL_PACKAGE_INVALID,
                        "allowed_tools 未绑定已启用工具: " + toolCode);
            }
        }
    }

    private void ensureUniqueCode(Long tenantId, String code) {
        long count = skillMapper.selectCount(new LambdaQueryWrapper<SkillEntity>()
                .eq(SkillEntity::getTenantId, tenantId)
                .eq(SkillEntity::getSkillCode, code));
        if (count > 0) throw new BizException(ErrorCode.SKILL_PACKAGE_DUPLICATE, "技能编码已存在: " + code);
    }

    private Path resolveRepoRoot() {
        Path root = Path.of(repoDir);
        if (!root.isAbsolute()) root = Path.of(System.getProperty("user.dir")).resolve(root);
        return root.normalize();
    }

    private Path safeZipPath(String raw) {
        if (raw == null || raw.isBlank() || raw.indexOf('\0') >= 0 || raw.startsWith("/") || raw.startsWith("\\")
                || raw.matches("^[A-Za-z]:[\\\\/].*")) {
            throw new BizException(ErrorCode.SKILL_PACKAGE_UNSAFE, "ZIP 包含绝对路径或非法路径");
        }
        Path path = Path.of(raw.replace('\\', '/')).normalize();
        if (path.isAbsolute() || path.startsWith("..") || path.toString().contains("..")) {
            throw new BizException(ErrorCode.SKILL_PACKAGE_UNSAFE, "ZIP 包含路径穿越: " + raw);
        }
        return path;
    }

    private boolean isDangerous(String name) {
        String lower = name.toLowerCase();
        return DANGEROUS_SUFFIXES.stream().anyMatch(lower::endsWith);
    }

    private void validateCode(String code) {
        if (code == null || !code.matches("[a-zA-Z0-9][a-zA-Z0-9_-]{1,63}")) {
            throw new BizException(ErrorCode.SKILL_PACKAGE_INVALID, "技能编码只能包含字母、数字、下划线和短横线");
        }
    }

    private String requiredText(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            throw new BizException(ErrorCode.SKILL_PACKAGE_INVALID, "SKILL.md 缺少字段: " + key);
        }
        return String.valueOf(value).trim();
    }

    private List<String> readAllowedTools(Object value) {
        if (value == null) return null;
        if (!(value instanceof List<?> list)) {
            throw new BizException(ErrorCode.SKILL_PACKAGE_INVALID, "allowed_tools 必须是数组");
        }
        List<String> result = new ArrayList<>();
        for (Object item : list) {
            if (item == null || String.valueOf(item).isBlank()) {
                throw new BizException(ErrorCode.SKILL_PACKAGE_INVALID, "allowed_tools 包含空工具编码");
            }
            result.add(String.valueOf(item).trim());
        }
        return result;
    }

    private String slug(String name) {
        String value = name.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
        return value.isBlank() ? "skill-" + UUID.randomUUID().toString().substring(0, 8) : value;
    }

    private FrontMatter parseFrontMatter(String raw) {
        if (!raw.startsWith("---")) return new FrontMatter(null, raw);
        int end = raw.indexOf("\n---", 3);
        if (end <= 0) return new FrontMatter(null, raw);
        return new FrontMatter(raw.substring(3, end).trim(), raw.substring(end + 4).trim());
    }

    private String safeFileName(MultipartFile file) {
        String name = file == null ? "unknown" : file.getOriginalFilename();
        if (name == null || name.isBlank()) return "unknown";
        return Path.of(name).getFileName().toString();
    }

    private void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(source, target);
        }
    }

    private void cleanupTree(Path root) {
        if (root == null || !Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        try (var stream = Files.walk(root)) {
            stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (IOException ignored) { }
            });
        } catch (IOException ignored) { }
    }

    private record Manifest(String code, String name, String description, String version, List<String> allowedTools) {}
    private record FrontMatter(String yaml, String body) {}
}
