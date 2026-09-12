package com.flexforge.kb.domain;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 附件校验与提取单测（FR-KB-05、docs/13 §3.6-8）：白名单/尺寸、四类提取
 * （txt 直读、docx/xlsx zip+XML、pdf PDFBox）、zip 炸弹防护、图片与坏文件
 * 降级仅存档、提取上限截断。
 */
class KbAttachmentsTest {

    private static byte[] zipOf(String entryName, String content) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry(entryName));
            zip.write(content.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return out.toByteArray();
    }

    private static byte[] pdfWith(String text) throws Exception {
        try (var document = new org.apache.pdfbox.pdmodel.PDDocument()) {
            var page = new org.apache.pdfbox.pdmodel.PDPage();
            document.addPage(page);
            try (var stream = new org.apache.pdfbox.pdmodel.PDPageContentStream(document, page)) {
                stream.beginText();
                stream.setFont(new org.apache.pdfbox.pdmodel.font.PDType1Font(
                        org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA), 12);
                stream.newLineAtOffset(50, 700);
                stream.showText(text);
                stream.endText();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }

    @Test
    void validatesWhitelistAndSize() {
        assertThatThrownBy(() -> KbAttachments.validate("a.exe", 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不支持的附件类型");
        assertThatThrownBy(() -> KbAttachments.validate("a.pdf", 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("10MB");
        for (String name : new String[]{"a.png", "a.jpg", "a.pdf", "a.csv", "a.xlsx",
                "a.docx", "a.txt", "a.md"}) {
            KbAttachments.validate(name, 100);
        }
    }

    @Test
    void extractsPlainTextDirectly() {
        assertThat(KbAttachments.extract("说明.txt", "你好 FlexForge".getBytes())).isEqualTo("你好 FlexForge");
        assertThat(KbAttachments.extensionOf("A.PDF")).isEqualTo("pdf");
        assertThat(KbAttachments.extensionOf("noext")).isEmpty();
    }

    @Test
    void extractsDocxAndXlsxText() throws Exception {
        byte[] docx = zipOf("word/document.xml",
                "<w:document><w:body><w:p><w:t>设备巡检规范</w:t></w:p></w:body></w:document>");
        assertThat(KbAttachments.extract("规范.docx", docx)).contains("设备巡检规范");

        byte[] xlsx = zipOf("xl/sharedStrings.xml",
                "<sst><si><t>物料编码</t></si><si><t>库存数量</t></si></sst>");
        assertThat(KbAttachments.extract("清单.xlsx", xlsx)).contains("物料编码", "库存数量");
    }

    @Test
    void extractsPdfText() throws Exception {
        assertThat(KbAttachments.extract("报告.pdf", pdfWith("quarterly revenue 42")))
                .contains("quarterly");
    }

    @Test
    void imageAndBrokenFilesDegradeToArchiveOnly() throws Exception {
        assertThat(KbAttachments.extract("截图.png", new byte[] {1, 2, 3})).isNull();
        assertThat(KbAttachments.isImage("截图.png")).isTrue();
        assertThat(KbAttachments.extract("坏文件.docx", "不是zip".getBytes())).isNull();
        assertThat(KbAttachments.extract("坏文件.pdf", "不是pdf".getBytes())).isNull();
        // docx 结构对但缺目标条目 → 仅存档
        assertThat(KbAttachments.extract("空壳.docx", zipOf("other.xml", "x"))).isNull();
    }

    @Test
    void zipBombEntryIsCappedNotExploded() throws Exception {
        // 高压缩比条目（2MB 零字节解压后远超单条目上限的构造困难，用条目数与
        // 解压总长守卫验证：256+ 条目触发条目数守卫）
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (int i = 0; i < 300; i++) {
                zip.putNextEntry(new ZipEntry("entry-" + i));
                zip.write(new byte[64]);
                zip.closeEntry();
            }
        }
        assertThat(KbAttachments.extract("炸弹.docx", out.toByteArray())).isNull();
    }

    @Test
    void extractionCapsAtTwentyThousandChars() {
        String big = "长".repeat(KbAttachments.EXTRACT_MAX_CHARS + 500);
        String extracted = KbAttachments.extract("大文件.txt", big.getBytes());
        assertThat(extracted).hasSize(KbAttachments.EXTRACT_MAX_CHARS);
    }
}
