package org.dromara.ai.util;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.dromara.common.core.exception.ServiceException;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

/**
 * 知识库文档文本提取器。
 *
 * <p>支持 PDF、Office（docx / xlsx / pptx）与常见纯文本格式；提取结果用于切片与在线预览。</p>
 *
 * @author BizBuddy
 */
public final class AiDocumentParser {

    /**
     * 纯文本格式：直接按 UTF-8 读取
     */
    private static final Set<String> PLAIN_TEXT_SUFFIXES = Set.of(
        "txt", "md", "markdown", "csv", "tsv", "json", "xml", "yml", "yaml",
        "log", "sql", "properties", "ini", "conf", "java", "js", "ts", "py", "sh");

    /**
     * HTML 格式：读入后去除标签
     */
    private static final Set<String> HTML_SUFFIXES = Set.of("html", "htm");

    private static final String SUFFIX_DOCX = "docx";

    private static final String SUFFIX_XLSX = "xlsx";

    private static final String SUFFIX_PPTX = "pptx";

    private static final String SUFFIX_PDF = "pdf";

    private AiDocumentParser() {
    }

    /**
     * 判断是否支持解析该格式。
     *
     * @param suffix 文件后缀（不含点）
     * @return 是否支持
     */
    public static boolean supported(String suffix) {
        String normalized = normalize(suffix);
        return PLAIN_TEXT_SUFFIXES.contains(normalized)
            || HTML_SUFFIXES.contains(normalized)
            || SUFFIX_PDF.equals(normalized)
            || SUFFIX_DOCX.equals(normalized)
            || SUFFIX_XLSX.equals(normalized)
            || SUFFIX_PPTX.equals(normalized);
    }

    /**
     * 提取文档纯文本。
     *
     * @param bytes  文件字节
     * @param suffix 文件后缀（不含点）
     * @return 提取结果
     */
    public static String extract(byte[] bytes, String suffix) {
        String normalized = normalize(suffix);
        try {
            if (PLAIN_TEXT_SUFFIXES.contains(normalized)) {
                return readPlainText(bytes);
            }
            if (HTML_SUFFIXES.contains(normalized)) {
                return stripHtml(readPlainText(bytes));
            }
            if (SUFFIX_PDF.equals(normalized)) {
                return readPdf(bytes);
            }
            if (SUFFIX_DOCX.equals(normalized)) {
                return readDocx(bytes);
            }
            if (SUFFIX_XLSX.equals(normalized)) {
                return readXlsx(bytes);
            }
            if (SUFFIX_PPTX.equals(normalized)) {
                return readPptx(bytes);
            }
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new ServiceException("文档解析失败：" + e.getMessage());
        }
        throw new ServiceException("暂不支持解析 ." + normalized + " 格式，请上传 PDF / docx / xlsx / pptx / 文本类文件");
    }

    /**
     * 读取纯文本，去除 UTF-8 BOM。
     *
     * @param bytes 文件字节
     * @return 文本
     */
    private static String readPlainText(byte[] bytes) {
        String text = new String(bytes, StandardCharsets.UTF_8);
        return text.startsWith("\uFEFF") ? text.substring(1) : text;
    }

    /**
     * 提取 PDF 文本。
     *
     * @param bytes 文件字节
     * @return 文本
     * @throws Exception 解析异常
     */
    private static String readPdf(byte[] bytes) throws Exception {
        try (PDDocument document = Loader.loadPDF(bytes)) {
            return new PDFTextStripper().getText(document);
        }
    }

    /**
     * 提取 docx 段落与表格文本。
     *
     * @param bytes 文件字节
     * @return 文本
     * @throws Exception 解析异常
     */
    private static String readDocx(byte[] bytes) throws Exception {
        StringBuilder builder = new StringBuilder();
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            document.getParagraphs().forEach(paragraph -> appendLine(builder, paragraph.getText()));
            document.getTables().forEach(table -> table.getRows().forEach(row -> row.getTableCells()
                .forEach(cell -> append(builder, cell.getText() + "\t"))));
        }
        return builder.toString();
    }

    /**
     * 提取 xlsx 单元格文本（含公式计算结果）。
     *
     * @param bytes 文件字节
     * @return 文本
     * @throws Exception 解析异常
     */
    private static String readXlsx(byte[] bytes) throws Exception {
        DataFormatter formatter = new DataFormatter();
        StringBuilder builder = new StringBuilder();
        try (Workbook workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            for (int sheetIndex = 0; sheetIndex < workbook.getNumberOfSheets(); sheetIndex++) {
                Sheet sheet = workbook.getSheetAt(sheetIndex);
                appendLine(builder, "【工作表】" + sheet.getSheetName());
                for (Row row : sheet) {
                    StringBuilder line = new StringBuilder();
                    for (Cell cell : row) {
                        line.append(formatter.formatCellValue(cell)).append('\t');
                    }
                    appendLine(builder, line.toString());
                }
            }
        }
        return builder.toString();
    }

    /**
     * 提取 pptx 各页文本。
     *
     * @param bytes 文件字节
     * @return 文本
     * @throws Exception 解析异常
     */
    private static String readPptx(byte[] bytes) throws Exception {
        StringBuilder builder = new StringBuilder();
        try (XMLSlideShow slideShow = new XMLSlideShow(new ByteArrayInputStream(bytes))) {
            int page = 1;
            for (XSLFSlide slide : slideShow.getSlides()) {
                appendLine(builder, "【第 " + page++ + " 页】");
                for (XSLFShape shape : slide.getShapes()) {
                    if (shape instanceof XSLFTextShape textShape) {
                        appendLine(builder, textShape.getText());
                    }
                }
            }
        }
        return builder.toString();
    }

    /**
     * 去除 HTML 标签与脚本样式块。
     *
     * @param html HTML 文本
     * @return 纯文本
     */
    private static String stripHtml(String html) {
        String text = html.replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", " ");
        text = text.replaceAll("(?is)<br\\s*/?>", "\n");
        text = text.replaceAll("(?is)</(p|div|tr|li|h[1-6])>", "\n");
        text = text.replaceAll("(?s)<[^>]+>", " ");
        text = text.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">");
        return text.replaceAll("[ \\t\\x0B\\f\\r]+", " ").replaceAll("\\n{3,}", "\n\n");
    }

    /**
     * 追加一行文本。
     *
     * @param builder 缓冲
     * @param line    行内容
     */
    private static void appendLine(StringBuilder builder, String line) {
        append(builder, line);
        builder.append('\n');
    }

    /**
     * 追加文本片段。
     *
     * @param builder 缓冲
     * @param text    文本
     */
    private static void append(StringBuilder builder, String text) {
        if (text != null && !text.isBlank()) {
            builder.append(text.trim());
        }
    }

    /**
     * 规范化后缀。
     *
     * @param suffix 原始后缀
     * @return 小写且不含点的后缀
     */
    private static String normalize(String suffix) {
        if (suffix == null) {
            return "";
        }
        String normalized = suffix.trim().toLowerCase(Locale.ROOT);
        return normalized.startsWith(".") ? normalized.substring(1) : normalized;
    }

}