package com.multiagent.discuss.vision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiagent.discuss.config.AppProperties;
import com.multiagent.discuss.domain.PageBlock;
import com.multiagent.discuss.domain.TokenUsage;
import com.multiagent.discuss.ingest.DocumentIngestService.PageImage;
import com.multiagent.discuss.util.ChatUsageExtractor;
import com.multiagent.discuss.util.OpenAiEndpointResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeTypeUtils;
import org.springframework.util.StringUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.function.IntConsumer;

/**
 * 全局 Vision 服务：整页理解，结果由调用方写入指定 agent 知识槽。
 *
 * <p>支持按页进度回调：每完成一页调一次 {@code progress}，传入「已完成页数 / 总页数」。
 * 文本层完整的页直接走 textOnly，不再调 Vision（节省多模态调用）。
 */
@Service
public class VisionService {

    private static final Logger log = LoggerFactory.getLogger(VisionService.class);

    private final AppProperties props;
    private final ObjectMapper objectMapper;
    private final Semaphore visionPageSemaphore;
    private volatile ChatModel visionModel;

    public VisionService(AppProperties props, ObjectMapper objectMapper, Semaphore visionPageSemaphore) {
        this.props = props;
        this.objectMapper = objectMapper;
        this.visionPageSemaphore = visionPageSemaphore;
    }

    /**
     * 逐页分析并以列表返回。同步阻塞。
     *
     * @param pages    ingest 出的页
     * @param progress 进度回调，参数为「已完成的页数」（1..N）。可为 null。
     * @param tokenSink Vision token 用量汇聚点（可为 null）。每页 Vision 调用后会合并进去。
     */
    public List<PageBlock> analyzePages(List<PageImage> pages, IntConsumer progress, TokenUsage tokenSink) {
        List<PageBlock> result = new ArrayList<>();
        int total = pages.size();
        int done = 0;
        for (PageImage page : pages) {
            try {
                if (needsVisionSemaphore(page)) {
                    visionPageSemaphore.acquire();
                    try {
                        result.add(analyzeOne(page, tokenSink));
                    } finally {
                        visionPageSemaphore.release();
                    }
                } else {
                    result.add(analyzeOne(page, tokenSink));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                result.add(failedBlock(page, "Vision 被中断"));
            } catch (Exception e) {
                log.warn("Vision page {} failed: {}", page.pageIndex(), e.toString());
                result.add(failedBlock(page, e.getMessage()));
            }
            done++;
            if (progress != null) {
                try {
                    progress.accept(done);
                } catch (Exception e) {
                    log.debug("progress callback error: {}", e.toString());
                }
            }
        }
        return result;
    }

    /** 默认无回调、无 token 收集，保持原签名兼容。 */
    public List<PageBlock> analyzePages(List<PageImage> pages) {
        return analyzePages(pages, null, null);
    }

    /** 仅带进度回调，不收集 token。 */
    public List<PageBlock> analyzePages(List<PageImage> pages, IntConsumer progress) {
        return analyzePages(pages, progress, null);
    }

    /** 仅当真正要调用 Vision（图像页、功能启用且配置可用）时才需要抢信号量。 */
    private boolean needsVisionSemaphore(PageImage page) {
        if (page.hasTextLayer()) return false;
        if (!props.getVision().isEnabled()) return false;
        if (page.imagePath() == null) return false;
        if (!isImageFile(page.imagePath())) return false;
        if (shouldFallback()) return false;
        return true;
    }

    public PageBlock analyzeOne(PageImage page) {
        return analyzeOne(page, null);
    }

    public PageBlock analyzeOne(PageImage page, TokenUsage tokenSink) {
        PageBlock block = new PageBlock();
        block.setPageIndex(page.pageIndex());
        block.setImageRef(page.imagePath() == null ? null : page.imagePath().toString());
        block.setPlainHint(page.plainHint());

        if (!props.getVision().isEnabled()) {
            block.setUnderstanding(fallbackUnderstanding(page, "Vision 已禁用，使用文字层/占位理解"));
            return block;
        }

        // 文本层完整：直接走文字层，跳过 Vision
        if (page.hasTextLayer()) {
            log.info("[hasTextLayer] 跳过 Vision, page={}, chars={}", page.pageIndex(),
                    page.plainHint() == null ? 0 : page.plainHint().length());
            block.setUnderstanding(textOnlyUnderstanding(page, "PDF 文本层完整，已跳过 Vision"));
            return block;
        }

        Path path = page.imagePath();
        boolean isImage = path != null && isImageFile(path);

        // 纯文本页（imagePath 是 .txt 等）：不强制看图
        if (!isImage) {
            block.setUnderstanding(textOnlyUnderstanding(page, null));
            return block;
        }

        if (shouldFallback()) {
            block.setUnderstanding(fallbackUnderstanding(page, "Vision 未配置可用 Key，使用文字层/占位理解"));
            return block;
        }

        try {
            VisionCallResult vr = callVisionModelWithUsage(path, page.plainHint());
            block.setUnderstanding(parseUnderstanding(vr.json, page));
            if (tokenSink != null && vr.usage != null) {
                tokenSink.merge(vr.usage);
            }
        } catch (Exception e) {
            log.warn("Vision call failed, using fallback: {}", e.toString());
            if (props.getVision().isFallbackWhenUnavailable()) {
                block.setUnderstanding(fallbackUnderstanding(page, "Vision 调用失败，使用文字层/占位理解: " + e.getMessage()));
            } else {
                throw new IllegalStateException("Vision 失败: " + e.getMessage(), e);
            }
        }
        return block;
    }

    private String callVisionModel(Path imagePath, String plainHint) {
        return callVisionModelWithUsage(imagePath, plainHint).json;
    }

    private VisionCallResult callVisionModelWithUsage(Path imagePath, String plainHint) {
        ChatModel model = visionChatModel();
        String promptText = """
                你是文献整页视觉分析器。必须把正文、表格、图片、图题、脚注当作整体理解。
                禁止只根据文字层忽略图或表。
                可选文字层辅助（可能不完整）：
                ---
                %s
                ---
                请严格输出 JSON（不要 Markdown 围栏），字段：
                {
                  "narrative": "本页图文表一体在讲什么",
                  "tables": [{"title":"","markdown":"","unit":"","note":""}],
                  "figures": [{"type":"","keyInfo":"","relationToText":""}],
                  "claims": ["可引用结论"],
                  "uncertainties": ["看不清或可能歧义处"]
                }
                """.formatted(plainHint == null ? "" : plainHint);

        Media media = new Media(MimeTypeUtils.IMAGE_PNG, new FileSystemResource(imagePath));
        UserMessage message = UserMessage.builder()
                .text(promptText)
                .media(media)
                .build();
        ChatResponse response = model.call(new Prompt(List.of(message)));
        VisionCallResult r = new VisionCallResult();
        r.json = response.getResult().getOutput().getText();
        r.usage = ChatUsageExtractor.extract(response, props.getVision().getModel());
        return r;
    }

    private static class VisionCallResult {
        String json;
        TokenUsage usage;
    }

    private PageBlock.Understanding parseUnderstanding(String json, PageImage page) {
        try {
            String cleaned = stripFence(json);
            JsonNode root = objectMapper.readTree(cleaned);
            PageBlock.Understanding u = new PageBlock.Understanding();
            u.setNarrative(text(root, "narrative"));
            u.setClaims(stringList(root.get("claims")));
            u.setUncertainties(stringList(root.get("uncertainties")));
            if (root.has("tables") && root.get("tables").isArray()) {
                for (JsonNode t : root.get("tables")) {
                    PageBlock.TableInfo ti = new PageBlock.TableInfo();
                    ti.setTitle(text(t, "title"));
                    ti.setMarkdown(text(t, "markdown"));
                    ti.setUnit(text(t, "unit"));
                    ti.setNote(text(t, "note"));
                    u.getTables().add(ti);
                }
            }
            if (root.has("figures") && root.get("figures").isArray()) {
                for (JsonNode f : root.get("figures")) {
                    PageBlock.FigureInfo fi = new PageBlock.FigureInfo();
                    fi.setType(text(f, "type"));
                    fi.setKeyInfo(text(f, "keyInfo"));
                    fi.setRelationToText(text(f, "relationToText"));
                    u.getFigures().add(fi);
                }
            }
            if (!StringUtils.hasText(u.getNarrative())) {
                u.setNarrative(page.plainHint());
            }
            return u;
        } catch (Exception e) {
            PageBlock.Understanding u = fallbackUnderstanding(page, "Vision JSON 解析失败，保留模型原文作为占位");
            u.setNarrative(json);
            return u;
        }
    }

    private PageBlock.Understanding textOnlyUnderstanding(PageImage page, String extraNote) {
        PageBlock.Understanding u = new PageBlock.Understanding();
        String base = page.plainHint() == null ? "（空文本页）" : page.plainHint();
        u.setNarrative(extraNote == null ? base : "[" + extraNote + "]\n" + base);
        if (page.plainHint() != null && page.plainHint().length() > 40) {
            u.getClaims().add(page.plainHint().substring(0, Math.min(120, page.plainHint().length())));
        }
        return u;
    }

    private PageBlock.Understanding fallbackUnderstanding(PageImage page, String reason) {
        PageBlock.Understanding u = new PageBlock.Understanding();
        String hint = page.plainHint();
        u.setNarrative(StringUtils.hasText(hint)
                ? "[FALLBACK] " + reason + "\n" + hint
                : "[FALLBACK] " + reason + "；第 " + (page.pageIndex() + 1) + " 页无文字层。");
        u.getUncertainties().add(reason);
        if (StringUtils.hasText(hint)) {
            u.getClaims().add(hint.length() > 100 ? hint.substring(0, 100) + "..." : hint);
        }
        return u;
    }

    private PageBlock failedBlock(PageImage page, String msg) {
        PageBlock block = new PageBlock();
        block.setPageIndex(page.pageIndex());
        block.setImageRef(page.imagePath() == null ? null : page.imagePath().toString());
        block.setPlainHint(page.plainHint());
        PageBlock.Understanding u = new PageBlock.Understanding();
        u.setNarrative(page.plainHint());
        u.getUncertainties().add(msg == null ? "Vision 失败" : msg);
        block.setUnderstanding(u);
        return block;
    }

    private boolean shouldFallback() {
        String key = props.getVision().getApiKey();
        if (!StringUtils.hasText(key) || key.contains("placeholder")) {
            return props.getVision().isFallbackWhenUnavailable();
        }
        return false;
    }

    private ChatModel visionChatModel() {
        ChatModel local = visionModel;
        if (local != null) {
            return local;
        }
        synchronized (this) {
            if (visionModel == null) {
                OpenAiEndpointResolver.Resolved ep = OpenAiEndpointResolver.resolve(props.getVision().getBaseUrl());
                OpenAiApi api = OpenAiApi.builder()
                        .apiKey(props.getVision().getApiKey())
                        .baseUrl(ep.baseUrl())
                        .completionsPath(ep.completionsPath())
                        .build();
                visionModel = OpenAiChatModel.builder()
                        .openAiApi(api)
                        .defaultOptions(OpenAiChatOptions.builder()
                                .model(props.getVision().getModel())
                                .temperature(0.2)
                                .build())
                        .build();
            }
            return visionModel;
        }
    }

    private static boolean isImageFile(Path path) {
        String n = path.getFileName().toString().toLowerCase();
        return n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".webp")
                || n.endsWith(".gif");
    }

    private static String stripFence(String s) {
        if (s == null) return "{}";
        String t = s.trim();
        if (t.startsWith("```")) {
            int firstNl = t.indexOf('\n');
            int last = t.lastIndexOf("```");
            if (firstNl > 0 && last > firstNl) {
                t = t.substring(firstNl + 1, last).trim();
            }
        }
        return t;
    }

    private static String text(JsonNode node, String field) {
        if (node == null || !node.has(field) || node.get(field).isNull()) return null;
        return node.get(field).asText();
    }

    private static List<String> stringList(JsonNode arr) {
        List<String> list = new ArrayList<>();
        if (arr != null && arr.isArray()) {
            arr.forEach(n -> list.add(n.asText()));
        }
        return list;
    }
}
