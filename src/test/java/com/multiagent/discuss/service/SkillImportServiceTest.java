package com.multiagent.discuss.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.io.InputStream;
import java.io.IOException;
import java.net.URI;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SkillImportServiceTest {

    @Test
    void rejectsNonCanonicalGitHubUrlsBeforeAnyNetworkRequest() {
        assertThrows(IllegalArgumentException.class,
                () -> SkillImportService.parseGitHubUrl("https://user@github.com/owner/repo"));
        assertThrows(IllegalArgumentException.class,
                () -> SkillImportService.parseGitHubUrl("https://github.com:443/owner/repo"));
        assertThrows(IllegalArgumentException.class,
                () -> SkillImportService.parseGitHubUrl("https://github.com/owner/repo?download=1"));
        assertThrows(IllegalArgumentException.class,
                () -> SkillImportService.parseGitHubUrl("https://github.com/../repo"));
    }

    @Test
    void rejectsSymbolicLinksInsideZipPackages() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("SKILL.md"));
            zip.write("---\nname: safe\ndescription: safe\n---\n".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("references/link.txt"));
            zip.write("../../outside".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        byte[] archive = markAsUnixSymlink(bytes.toByteArray(), "references/link.txt");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> SkillImportService.scanZip(archive));

        assertEquals("ZIP 包含符号链接: references/link.txt", error.getMessage());
    }

    @Test
    void timesOutAndClosesAStalledDownloadBody() {
        AtomicBoolean closed = new AtomicBoolean();
        InputStream stalled = new InputStream() {
            @Override public int read() throws IOException {
                while (!closed.get()) {
                    try { Thread.sleep(5); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); return -1; }
                }
                return -1;
            }
            @Override public void close() { closed.set(true); }
        };

        assertThrows(IllegalStateException.class,
                () -> SkillImportService.readLimitedWithin(stalled, 1024, Duration.ofMillis(50)));
        assertEquals(true, closed.get());
    }

    @Test
    void redirectTargetsStayOnTrustedGitHubDownloadHosts() {
        SkillImportService.validateDownloadUri(URI.create("https://api.github.com/repos/owner/repo"));
        SkillImportService.validateDownloadUri(URI.create("https://codeload.github.com/owner/repo/zip/main"));
        assertThrows(IllegalArgumentException.class,
                () -> SkillImportService.validateDownloadUri(URI.create("https://example.com/private")));
        assertThrows(IllegalArgumentException.class,
                () -> SkillImportService.validateDownloadUri(URI.create("https://user@api.github.com/repos/owner/repo")));
    }

    private static byte[] markAsUnixSymlink(byte[] zip, String target) {
        byte[] wanted = target.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.wrap(zip).order(ByteOrder.LITTLE_ENDIAN);
        for (int offset = 0; offset + 46 <= zip.length; offset++) {
            if (buffer.getInt(offset) != 0x02014b50) continue;
            int nameLength = Short.toUnsignedInt(buffer.getShort(offset + 28));
            if (nameLength != wanted.length) continue;
            boolean match = true;
            for (int i = 0; i < wanted.length; i++) {
                if (zip[offset + 46 + i] != wanted[i]) { match = false; break; }
            }
            if (!match) continue;
            buffer.putShort(offset + 4, (short) 0x0314); // Unix creator, ZIP spec 2.0
            buffer.putInt(offset + 38, 0120000 << 16);  // Unix S_IFLNK in high word
            return zip;
        }
        throw new AssertionError("central directory entry not found");
    }
}
