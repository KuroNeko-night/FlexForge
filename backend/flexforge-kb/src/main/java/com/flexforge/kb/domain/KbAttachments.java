package com.flexforge.kb.domain;

import com.flexforge.common.PublicApi;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 助手对话附件校验与文本提取（FR-KB-05，docs/13 §3.6-8）：白名单+尺寸上限
 * 服务端强制；txt/csv/md 直读，docx/xlsx 用标准库 zip+XML 提取（炸弹防护：
 * 条目数/解压总长/单条目硬上限），pdf 经 PDFBox，图片与提取失败返回 null
 * （仅存档，问答不阻断）。
 */
@PublicApi
public final class KbAttachments {

    /** 上限常量（docs/13 §3.6-8 与 docs/02 FR-KB-05 同口径）。 */
    public static final int MAX_FILES = 3;
    public static final long MAX_BYTES = 10L * 1024 * 1024;
    public static final int EXTRACT_MAX_CHARS = 20_000;
    static final int ZIP_MAX_ENTRIES = 256;
    static final long ZIP_MAX_TOTAL = 20L * 1024 * 1024;
    static final long ZIP_MAX_ENTRY = 10L * 1024 * 1024;

    /** 扩展名白名单（小写；图片仅存档，其余可提取）。 */
    private static final Set<String> IMAGE_EXTENSIONS = Set.of("png", "jpg", "jpeg", "gif", "webp");
    private static final Set<String> TEXT_EXTENSIONS = Set.of("txt", "csv", "md");
    private static final Set<String> ZIP_XML_EXTENSIONS = Set.of("docx", "xlsx");
    private static final Set<String> ALL_EXTENSIONS =
            Set.of("png", "jpg", "jpeg", "gif", "webp", "pdf", "csv", "xlsx", "docx", "txt", "md");

    private KbAttachments() {
    }

    /** 扩展名（小写，无点）；无扩展名返回空串。 */
    public static String extensionOf(String filename) {
        if (filename == null) {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        return dot >= 0 && dot < filename.length() - 1
                ? filename.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
    }

    /** 白名单与尺寸校验（IAE 消息面向用户可直接回显）。 */
    public static void validate(String filename, long sizeBytes) {
        String ext = extensionOf(filename);
        if (!ALL_EXTENSIONS.contains(ext)) {
            throw new IllegalArgumentException("不支持的附件类型: " + filename
                    + "（允许 png/jpg/jpeg/gif/webp/pdf/csv/xlsx/docx/txt/md）");
        }
        if (sizeBytes <= 0 || sizeBytes > MAX_BYTES) {
            throw new IllegalArgumentException("附件 " + filename + " 超过 10MB 上限");
        }
    }

    public static boolean isImage(String filename) {
        return IMAGE_EXTENSIONS.contains(extensionOf(filename));
    }

    /** 提取文本（超上限截断）；图片/无法提取返回 null（仅存档）。 */
    public static String extract(String filename, byte[] data) {
        String ext = extensionOf(filename);
        try {
            if (TEXT_EXTENSIONS.contains(ext)) {
                return cap(new String(data, StandardCharsets.UTF_8));
            }
            if (ZIP_XML_EXTENSIONS.contains(ext)) {
                return cap(extractZipXml(ext, data));
            }
            if ("pdf".equals(ext)) {
                return cap(extractPdf(data));
            }
            return null;
        } catch (RuntimeException | IOException e) {
            // 解析失败降级为仅存档（FR-KB-05：不阻断问答）
            return null;
        }
    }

    /** docx/xlsx：定位目标 XML 条目（word/document.xml / xl/sharedStrings.xml），
     * 提取其中叶子文本节点；zip 炸弹防护三层=条目数 ≤256、累计压缩长度 ≤20MB
     * （流式 ZipInputStream 的 size 常为 -1 未知，只累加已知正值——审查 P2-2；
     * 真正的内存边界是单条目读取上限）、单条目读取 ≤10MB（readCapped 硬截断）。 */
    private static String extractZipXml(String ext, byte[] data) throws IOException {
        String target = "docx".equals(ext) ? "word/document.xml" : "xl/sharedStrings.xml";
        long total = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(data))) {
            ZipEntry entry;
            int count = 0;
            while ((entry = zip.getNextEntry()) != null) {
                if (++count > ZIP_MAX_ENTRIES) {
                    return null;
                }
                long compressed = entry.getCompressedSize();
                if (compressed > 0 && (total += compressed) > ZIP_MAX_TOTAL) {
                    return null;
                }
                if (target.equals(entry.getName())) {
                    return xmlText(readCapped(zip, ZIP_MAX_ENTRY));
                }
            }
        }
        return null;
    }

    /** 读取上限内的条目字节（超限即截断停止读取，防解压炸弹撑爆内存）。 */
    private static byte[] readCapped(ZipInputStream zip, long maxBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long read = 0;
        int n;
        while (read < maxBytes && (n = zip.read(buffer, 0,
                (int) Math.min(buffer.length, maxBytes - read))) > 0) {
            out.write(buffer, 0, n);
            read += n;
        }
        return out.toByteArray();
    }

    /** 提取 XML 文档叶子元素的文本（docx 的 w:t / xlsx 的 t 节点）。
     * 只取无子元素的叶子——live 走查实证父链（document→body→p→t）各层
     * textContent 均含同一文本，整树收集会成倍重复。 */
    private static String xmlText(byte[] xml) {
        try {
            var factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            var builder = factory.newDocumentBuilder();
            var document = builder.parse(new ByteArrayInputStream(xml));
            var nodes = document.getElementsByTagName("*");
            StringBuilder text = new StringBuilder();
            for (int i = 0; i < nodes.getLength(); i++) {
                var element = nodes.item(i);
                if (hasElementChild(element)) {
                    continue;
                }
                String content = element.getTextContent();
                if (content != null && !content.isBlank()) {
                    text.append(content.strip()).append('\n');
                }
            }
            return text.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean hasElementChild(org.w3c.dom.Node node) {
        var children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i).getNodeType() == org.w3c.dom.Node.ELEMENT_NODE) {
                return true;
            }
        }
        return false;
    }

    /** PDF 提取：先做页数上限守卫（深嵌套/超多页文档在解析前降级仅存档，
     * 审查 P2-3——PDFBox 本身无限额，上限是我们自己的责任），解析异常
     * （含 Error，如深嵌套结构的 StackOverflowError）一律降级 null。 */
    private static final int PDF_MAX_PAGES = 2000;

    private static String extractPdf(byte[] data) {
        try (var document = org.apache.pdfbox.Loader.loadPDF(data)) {
            if (document.getNumberOfPages() > PDF_MAX_PAGES) {
                return null;
            }
            return new org.apache.pdfbox.text.PDFTextStripper().getText(document);
        } catch (IOException | RuntimeException | StackOverflowError | OutOfMemoryError e) {
            return null;
        }
    }

    static String cap(String text) {
        if (text == null) {
            return null;
        }
        String stripped = text.strip();
        return stripped.length() > EXTRACT_MAX_CHARS
                ? stripped.substring(0, EXTRACT_MAX_CHARS) : stripped;
    }
}
