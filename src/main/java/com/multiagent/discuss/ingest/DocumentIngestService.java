package com.multiagent.discuss.ingest;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.docx4j.openpackaging.parts.WordprocessingML.MainDocumentPart;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import jakarta.xml.bind.JAXBElement;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 多格式统一为页图像 + 可选文字层辅助。
 *
 * <p>关键策略：
 * <ul>
 *   <li>PDF：逐页抽文本。文本层足够长时（≥ {@link #TEXT_LAYER_THRESHOLD}）标记为
 *       {@code hasTextLayer=true}，下游 {@link com.multiagent.discuss.vision.VisionService}
 *       会跳过 Vision 直接走文字层，避免对带文本层的文献误调昂贵的多模态模型。</li>
 *   <li>DOCX：用 docx4j 解析出纯文本，再按虚拟页切片，整篇视为「纯文本页」，无需 Vision。</li>
 *   <li>TXT / MD：直接按字符切虚拟页。</li>
 *   <li>PNG / JPG：单页图，必须走 Vision。</li>
 * </ul>
 */
@Component
public class DocumentIngestService {

    /** 一页有意义的文本长度阈值。低于此值认为是扫描版或图像型 PDF，需要 Vision。 */
    public static final int TEXT_LAYER_THRESHOLD = 80;

    /**
     * 一页虚拟页字符数（仅文本类文档按此切片）。
     */
    private static final int TEXT_PAGE_CHUNK = 2500;

    public record PageImage(int pageIndex, Path imagePath, String plainHint, boolean hasTextLayer) {
    }

    public List<PageImage> ingestToPages(byte[] data, String fileName, Path sessionWorkDir) throws IOException {
        Files.createDirectories(sessionWorkDir);
        String lower = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);

        if (lower.endsWith(".pdf")) {
            return renderPdf(data, sessionWorkDir);
        }
        if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".webp")) {
            Path img = sessionWorkDir.resolve("page-0" + extension(lower));
            Files.write(img, data);
            return List.of(new PageImage(0, img, null, false));
        }
        if (lower.endsWith(".txt") || lower.endsWith(".md") || lower.endsWith(".markdown")) {
            return textAsPages(new String(data, StandardCharsets.UTF_8), sessionWorkDir);
        }
        if (lower.endsWith(".docx")) {
            return ingestDocx(data, sessionWorkDir);
        }
        // 默认按文本
        return textAsPages(new String(data, StandardCharsets.UTF_8), sessionWorkDir);
    }

    public String extractPlainText(byte[] data, String fileName) throws IOException {
        String lower = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".pdf")) {
            try (PDDocument doc = Loader.loadPDF(data)) {
                return new PDFTextStripper().getText(doc);
            }
        }
        if (lower.endsWith(".docx")) {
            return extractDocxPlainText(data);
        }
        if (lower.endsWith(".txt") || lower.endsWith(".md") || lower.endsWith(".markdown") || !StringUtils.hasText(lower)) {
            return new String(data, StandardCharsets.UTF_8);
        }
        return "";
    }

    private List<PageImage> renderPdf(byte[] data, Path dir) throws IOException {
        List<PageImage> pages = new ArrayList<>();
        try (PDDocument doc = Loader.loadPDF(data)) {
            PDFRenderer renderer = new PDFRenderer(doc);
            PDFTextStripper stripper = new PDFTextStripper();
            int n = doc.getNumberOfPages();
            for (int i = 0; i < n; i++) {
                stripper.setStartPage(i + 1);
                stripper.setEndPage(i + 1);
                String hint = blankToNull(stripper.getText(doc));
                boolean textLayer = hint != null && hint.length() >= TEXT_LAYER_THRESHOLD;

                Path out = null;
                // 文本层完整时不渲染 PNG，节省磁盘和后续 Vision 调用
                if (!textLayer) {
                    BufferedImage image = renderer.renderImageWithDPI(i, 160, ImageType.RGB);
                    out = dir.resolve("page-" + i + ".png");
                    ImageIO.write(image, "png", out.toFile());
                }
                pages.add(new PageImage(i, out, hint, textLayer));
            }
        }
        return pages;
    }

    /**
     * 解析 DOCX：直接走文本层，整篇视为多虚拟页（无需 Vision）。
     * docx4j 11 移除了旧 convertToText()，改用 TextUtils 拼接 main document part。
     */
    private List<PageImage> ingestDocx(byte[] data, Path dir) throws IOException {
        String text;
        try {
            text = extractDocxPlainText(data);
        } catch (Exception e) {
            throw new IOException("DOCX 解析失败: " + e.getMessage(), e);
        }
        if (!StringUtils.hasText(text)) {
            text = "（DOCX 无可提取文本）";
        }
        return textAsPages(text, dir);
    }

    private String extractDocxPlainText(byte[] data) throws IOException {
        try (var in = new java.io.ByteArrayInputStream(data)) {
            WordprocessingMLPackage pkg = WordprocessingMLPackage.load(in);
            MainDocumentPart main = pkg.getMainDocumentPart();
            // docx4j 11：移除 getParagraphs() / getDocumentPart() / TextUtils.toString(Object)。
            // 用 XPath 收集所有 w:t 文本节点，逐个 unwrap 后拼接。
            @SuppressWarnings("unchecked")
            List<Object> nodes = main.getJAXBNodesViaXPath("//w:t", false);
            StringBuilder sb = new StringBuilder();
            if (nodes != null) {
                for (Object tn : nodes) {
                    Object unwrapped = (tn instanceof JAXBElement) ? ((JAXBElement<?>) tn).getValue() : tn;
                    if (unwrapped != null) {
                        sb.append(unwrapped.toString());
                    }
                    sb.append(' ');
                }
            }
            return sb.toString().trim();
        } catch (Exception e) {
            throw new IOException("docx4j 解析失败: " + e.getMessage(), e);
        }
    }

    private List<PageImage> textAsPages(String text, Path dir) throws IOException {
        List<PageImage> pages = new ArrayList<>();
        if (text == null) text = "";
        int chunk = TEXT_PAGE_CHUNK;
        int idx = 0;
        for (int start = 0; start < text.length() || idx == 0; start += chunk, idx++) {
            int end = Math.min(text.length(), start + chunk);
            String part = start < text.length() ? text.substring(start, end) : text;
            Path meta = dir.resolve("text-page-" + idx + ".txt");
            Files.writeString(meta, part, StandardCharsets.UTF_8);
            // 文本类整页都是纯文本，hasTextLayer=true 且不渲染图像
            pages.add(new PageImage(idx, meta, part, true));
            if (end >= text.length()) break;
        }
        return pages;
    }

    private static String extension(String lower) {
        int i = lower.lastIndexOf('.');
        return i >= 0 ? lower.substring(i) : ".bin";
    }

    private static String blankToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}