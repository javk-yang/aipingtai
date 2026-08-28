package com.agentforge.agent.skill.repo;

import com.agentforge.common.exception.BizException;
import com.agentforge.common.exception.ErrorCode;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SKILL.md 文件仓库（P9 渐进式披露的 L1 全文来源）。
 *
 * 设计（贴近 Anthropic Agent Skills 标准）：
 * - 文件布局：{repo-dir}/{skill_code}/SKILL.md
 * - 格式：YAML frontmatter（name/description/version/allowed_tools）+ Markdown 正文
 * - 解析产物：扁平 Map {name, description, version, allowed_tools[], markdown}
 *
 * 安全约束：
 * - 只读白名单：所有读取必须落在 repo-dir 内，拒绝绝对路径与 .. 穿越
 * - 全文 512KB 上限，防止技能内容成为 prompt 注入载体
 * - 文件缺失/损坏返回业务错误，不影响 DB 元数据查询（DB 兼容通道仍在）
 */
@Component
public class SkillFileRepository {

    private static final Logger log = LoggerFactory.getLogger(SkillFileRepository.class);
    private static final int MAX_CONTENT_BYTES = 512 * 1024;

    private final Path repoRoot;

    public SkillFileRepository(@Value("${app.skill.repo-dir:skill-repo}") String repoDir) {
        Path resolved = Path.of(repoDir);
        if (!resolved.isAbsolute()) {
            resolved = Path.of(System.getProperty("user.dir")).resolve(resolved).normalize();
        }
        this.repoRoot = resolved.normalize();
    }

    @PostConstruct
    public void init() {
        log.info("skill repo root: {}", repoRoot);
        if (!Files.isDirectory(repoRoot)) {
            log.warn("skill repo directory missing, fall back to DB content only: {}", repoRoot);
        }
    }

    /**
     * 读取技能全文。relativePath 形如 "unit_converter/SKILL.md"（对应 skill_file_url）。
     * 返回 null 表示文件不存在（调用方回退 DB content_json）。
     */
    public Map<String, Object> load(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return null;
        }
        Path target = resolveSafe(relativePath);
        if (target == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "非法技能文件路径: " + relativePath);
        }
        if (!Files.isRegularFile(target)) {
            log.warn("skill file not found: {} (relative={})", target, relativePath);
            return null;
        }
        return parse(readWithLimit(target));
    }

    /** 规范化并校验路径：仅允许 repo 内相对路径，禁止绝对路径与 .. 穿越。 */
    private Path resolveSafe(String relativePath) {
        Path raw = Path.of(relativePath);
        if (raw.isAbsolute()) {
            return null;
        }
        Path normalized = repoRoot.resolve(raw).normalize();
        if (!normalized.startsWith(repoRoot)) {
            return null;
        }
        return normalized;
    }

    private String readWithLimit(Path file) {
        try {
            byte[] bytes = Files.readAllBytes(file);
            if (bytes.length > MAX_CONTENT_BYTES) {
                throw new BizException(ErrorCode.SKILL_CONTENT_TOO_LARGE);
            }
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (BizException e) {
            throw e;
        } catch (IOException e) {
            throw new BizException(ErrorCode.SKILL_FILE_READ_ERROR, e);
        }
    }

    /** 解析 SKILL.md：--- frontmatter（YAML）--- 正文（Markdown）。 */
    Map<String, Object> parse(String raw) {
        FrontMatter fm = extractFrontMatter(raw);
        Map<String, Object> content = new LinkedHashMap<>();
        if (fm.yaml != null) {
            Object parsed = new Yaml().load(fm.yaml);
            if (parsed instanceof Map<?, ?> map) {
                map.forEach((k, v) -> content.put(String.valueOf(k), v));
            }
        }
        content.put("markdown", fm.body);
        return content;
    }

    private FrontMatter extractFrontMatter(String raw) {
        if (raw.startsWith("---")) {
            int end = raw.indexOf("\n---", 3);
            if (end > 0) {
                return new FrontMatter(raw.substring(3, end).trim(), raw.substring(end + 4).trim());
            }
        }
        return new FrontMatter(null, raw.trim());
    }

    private record FrontMatter(String yaml, String body) {}
}
