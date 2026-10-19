package com.multiagent.discuss.ingest;

import com.multiagent.discuss.config.AppProperties;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentIngestServiceTest {
    @TempDir Path tempDir;

    @Test
    void documentOutputMustRemainInsideConfiguredTempRoot() throws Exception {
        AppProperties props = new AppProperties();
        Path allowed = tempDir.resolve("allowed");
        props.getSession().setTempDir(allowed.toString());
        DocumentIngestService service = new DocumentIngestService(props);

        var pages = service.ingestToPages("hello".getBytes(StandardCharsets.UTF_8),
                "note.txt", allowed.resolve("session").resolve("agent"));
        assertEquals(1, pages.size());
        assertTrue(pages.get(0).imagePath().toRealPath().startsWith(allowed.toRealPath()));
        assertThrows(IllegalArgumentException.class, () -> service.ingestToPages(
                "hello".getBytes(StandardCharsets.UTF_8), "note.txt", tempDir.resolve("outside")));
    }

    @Test
    void linkedDirectoryCannotCreateFilesOutsideConfiguredRoot() throws Exception {
        AppProperties props = new AppProperties();
        Path allowed = tempDir.resolve("allowed");
        Path outside = tempDir.resolve("outside");
        Files.createDirectories(allowed);
        Files.createDirectories(outside);
        try {
            Files.createSymbolicLink(allowed.resolve("linked"), outside);
        } catch (UnsupportedOperationException | java.io.IOException | SecurityException e) {
            Assumptions.abort("当前平台不允许创建测试用符号链接: " + e.getClass().getSimpleName());
        }
        props.getSession().setTempDir(allowed.toString());
        DocumentIngestService service = new DocumentIngestService(props);

        assertThrows(IllegalArgumentException.class, () -> service.ingestToPages(
                "hello".getBytes(StandardCharsets.UTF_8), "note.txt",
                allowed.resolve("linked").resolve("must-not-exist")));
        assertTrue(Files.notExists(outside.resolve("must-not-exist")));
    }

    @Test
    void docxUpgradeStillExtractsTextAndBuildsPages() throws Exception {
        AppProperties props = new AppProperties();
        Path allowed = tempDir.resolve("allowed");
        props.getSession().setTempDir(allowed.toString());
        DocumentIngestService service = new DocumentIngestService(props);
        WordprocessingMLPackage document = WordprocessingMLPackage.createPackage();
        document.getMainDocumentPart().addParagraphOfText("DOCX regression text");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        document.save(bytes);

        var pages = service.ingestToPages(bytes.toByteArray(), "sample.docx",
                allowed.resolve("session").resolve("agent"));

        assertEquals(1, pages.size());
        assertTrue(pages.get(0).plainHint().contains("DOCX regression text"));
        assertTrue(pages.get(0).hasTextLayer());
    }
}
