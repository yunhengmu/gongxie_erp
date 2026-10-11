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
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.dromara.common.core.exception.ServiceException;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 知识库文档文本提取器。
 *
 * <p>支持 PDF、Office（docx / xlsx / pptx）与常见纯文本格式；提取结果用于切片与在线预览。</p>
 *
 * <p>解析以「块」{@link ParsedBlock} 为单位，块上携带章节层级、章节路径与页码等溯源元数据；
 * {@link #extract(byte[], String)} 仍按旧签名返回拼接后的纯文本，供预览等调用方使用。</p>
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

    /**
     * 块之间拼接用的分隔符（全文即由各块正文以该分隔符顺序连接）
     */
    private static final String BLOCK_SEPARATOR = "\n";

    /**
     * 章节路径分隔符
     */
    private static final String HEADING_PATH_SEPARATOR = " > ";

    /**
     * docx 标题样式标识：Heading1 ~ Heading6 / 标题 1 ~ 标题 6，或直接为 1 ~ 6
     */
    private static final Pattern DOCX_HEADING_STYLE = Pattern.compile("^\\s*(?:heading|标题)?\\s*([1-6])\\s*$",
        Pattern.CASE_INSENSITIVE);

    private AiDocumentParser() {
    }

    /**
     * 解析出的文本块（携带溯源元数据）。
     *
     * @param text         块正文
     * @param headingLevel 标题级别（0=正文，1~6=标题级别）
     * @param headingPath  章节路径（各级标题用 {@code >} 连接，正文无标题时为 null）
     * @param pageNo        页码（PDF 等分页文档；其余格式可空）
     */
    public record ParsedBlock(String text, int headingLevel, String headingPath, Integer pageNo) {
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
     * 提取文档纯文本（内部按块解析后以换行拼接，保持旧签名不变）。
     *
     * @param bytes  文件字节
     * @param suffix 文件后缀（不含点）
     * @return 提取结果
     */
    public static String extract(byte[] bytes, String suffix) {
        return join(parse(bytes, suffix));
    }

    /**
     * 按块解析文档，块上携带章节层级 / 章节路径 / 页码等溯源元数据。
     *
     * <p>MD / TXT 逐行识别 {@code #}~{@code ######} 标题并维护标题栈；DOCX 按段落样式判定标题；
     * PDF 逐页成块并标注页码；XLSX / PPTX 按工作表 / 幻灯片成块（层级为正文）。</p>
     *
     * @param bytes  文件字节
     * @param suffix 文件后缀（不含点）
     * @return 文本块列表（按文档顺序）
     */
    public static List<ParsedBlock> parse(byte[] bytes, String suffix) {
        String normalized = normalize(suffix);
        try {
            if (PLAIN_TEXT_SUFFIXES.contains(normalized)) {
                return parsePlainText(bytes);
            }
            if (HTML_SUFFIXES.contains(normalized)) {
                return List.of(new ParsedBlock(stripHtml(readPlainText(bytes)), 0, null, null));
            }
            if (SUFFIX_PDF.equals(normalized)) {
                return parsePdf(bytes);
            }
            if (SUFFIX_DOCX.equals(normalized)) {
                return parseDocx(bytes);
            }
            if (SUFFIX_XLSX.equals(normalized)) {
                return parseXlsx(bytes);
            }
            if (SUFFIX_PPTX.equals(normalized)) {
                return parsePptx(bytes);
            }
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new ServiceException("文档解析失败：" + e.getMessage());
        }
        throw new ServiceException("暂不支持解析 ." + normalized + " 格式，请上传 PDF / docx / xlsx / pptx / 文本类文件");
    }

    /**
     * 拼接各块正文（跳过空白块，以换行连接）。
     *
     * @param blocks 文本块
     * @return 拼接后的纯文本
     */
    public static String join(List<ParsedBlock> blocks) {
        StringBuilder builder = new StringBuilder();
        if (blocks != null) {
            for (ParsedBlock block : blocks) {
                String text = block.text();
                if (text == null || text.isEmpty()) {
                    continue;
                }
                if (builder.length() > 0) {
                    builder.append(BLOCK_SEPARATOR);
                }
                builder.append(text);
            }
        }
        return builder.toString();
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
     * 解析纯文本 / Markdown：逐行识别标题并维护标题栈生成章节路径。
     *
     * @param bytes 文件字节
     * @return 文本块列表
     */
    private static List<ParsedBlock> parsePlainText(byte[] bytes) {
        String text = readPlainText(bytes);
        List<ParsedBlock> blocks = new ArrayList<>();
        List<String> headingStack = new ArrayList<>();
        int start = 0;
        int length = text.length();
        while (start <= length) {
            int newline = text.indexOf('\n', start);
            int end = newline < 0 ? length : newline;
            String line = text.substring(start, end);
            if (line.endsWith("\r")) {
                line = line.substring(0, line.length() - 1);
            }
            if (!line.isBlank()) {
                int level = markdownHeadingLevel(line);
                if (level > 0) {
                    while (headingStack.size() >= level) {
                        headingStack.remove(headingStack.size() - 1);
                    }
                    headingStack.add(line.substring(level).trim());
                    blocks.add(new ParsedBlock(line, level, String.join(HEADING_PATH_SEPARATOR, headingStack), null));
                } else {
                    blocks.add(new ParsedBlock(line, 0, headingStack.isEmpty() ? null
                        : String.join(HEADING_PATH_SEPARATOR, headingStack), null));
                }
            }
            if (newline < 0) {
                break;
            }
            start = newline + 1;
        }
        return blocks;
    }

    /**
     * 提取 PDF 文本：逐页成块并标注页码（标题不猜）。
     *
     * @param bytes 文件字节
     * @return 文本块列表
     * @throws Exception 解析异常
     */
    private static List<ParsedBlock> parsePdf(byte[] bytes) throws Exception {
        List<ParsedBlock> blocks = new ArrayList<>();
        try (PDDocument document = Loader.loadPDF(bytes)) {
            int pages = document.getNumberOfPages();
            for (int page = 1; page <= pages; page++) {
                PDFTextStripper stripper = new PDFTextStripper();
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String text = stripper.getText(document);
                if (text != null && !text.isBlank()) {
                    blocks.add(new ParsedBlock(text.stripTrailing(), 0, null, page));
                }
            }
        }
        return blocks;
    }

    /**
     * 提取 docx：按段落样式判定 Heading1~6 维护标题栈，表格按行成块。
     *
     * @param bytes 文件字节
     * @return 文本块列表
     * @throws Exception 解析异常
     */
    private static List<ParsedBlock> parseDocx(byte[] bytes) throws Exception {
        List<ParsedBlock> blocks = new ArrayList<>();
        List<String> headingStack = new ArrayList<>();
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            for (XWPFParagraph paragraph : document.getParagraphs()) {
                String text = paragraph.getText();
                if (text == null || text.isBlank()) {
                    continue;
                }
                int level = docxHeadingLevel(paragraph.getStyle());
                if (level > 0) {
                    while (headingStack.size() >= level) {
                        headingStack.remove(headingStack.size() - 1);
                    }
                    headingStack.add(text.trim());
                    blocks.add(new ParsedBlock(text, level, String.join(HEADING_PATH_SEPARATOR, headingStack), null));
                } else {
                    blocks.add(new ParsedBlock(text, 0, headingStack.isEmpty() ? null
                        : String.join(HEADING_PATH_SEPARATOR, headingStack), null));
                }
            }
            String path = headingStack.isEmpty() ? null : String.join(HEADING_PATH_SEPARATOR, headingStack);
            for (XWPFTable table : document.getTables()) {
                table.getRows().forEach(row -> {
                    StringBuilder line = new StringBuilder();
                    row.getTableCells().forEach(cell -> line.append(cell.getText()).append('\t'));
                    if (!line.toString().isBlank()) {
                        blocks.add(new ParsedBlock(line.toString(), 0, path, null));
                    }
                });
            }
        }
        return blocks;
    }

    /**
     * 提取 xlsx 单元格文本（含公式计算结果）：每个工作表一个块。
     *
     * @param bytes 文件字节
     * @return 文本块列表
     * @throws Exception 解析异常
     */
    private static List<ParsedBlock> parseXlsx(byte[] bytes) throws Exception {
        DataFormatter formatter = new DataFormatter();
        List<ParsedBlock> blocks = new ArrayList<>();
        try (Workbook workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            for (int sheetIndex = 0; sheetIndex < workbook.getNumberOfSheets(); sheetIndex++) {
                Sheet sheet = workbook.getSheetAt(sheetIndex);
                StringBuilder builder = new StringBuilder();
                builder.append("【工作表】").append(sheet.getSheetName()).append('\n');
                for (Row row : sheet) {
                    StringBuilder line = new StringBuilder();
                    for (Cell cell : row) {
                        line.append(formatter.formatCellValue(cell)).append('\t');
                    }
                    builder.append(line).append('\n');
                }
                blocks.add(new ParsedBlock(builder.toString(), 0, null, null));
            }
        }
        return blocks;
    }

    /**
     * 提取 pptx 各页文本：每张幻灯片一个块。
     *
     * @param bytes 文件字节
     * @return 文本块列表
     * @throws Exception 解析异常
     */
    private static List<ParsedBlock> parsePptx(byte[] bytes) throws Exception {
        List<ParsedBlock> blocks = new ArrayList<>();
        try (XMLSlideShow slideShow = new XMLSlideShow(new ByteArrayInputStream(bytes))) {
            int page = 1;
            for (XSLFSlide slide : slideShow.getSlides()) {
                StringBuilder builder = new StringBuilder();
                builder.append("【第 ").append(page++).append(" 页】").append('\n');
                for (XSLFShape shape : slide.getShapes()) {
                    if (shape instanceof XSLFTextShape textShape && textShape.getText() != null
                        && !textShape.getText().isBlank()) {
                        builder.append(textShape.getText()).append('\n');
                    }
                }
                blocks.add(new ParsedBlock(builder.toString(), 0, null, null));
            }
        }
        return blocks;
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
     * 识别 Markdown 标题层级。
     *
     * @param line 行内容
     * @return 标题级别（1~6），非标题返回 0
     */
    private static int markdownHeadingLevel(String line) {
        int level = 0;
        while (level < line.length() && line.charAt(level) == '#') {
            level++;
        }
        return level >= 1 && level <= 6 ? level : 0;
    }

    /**
     * 由 docx 段落样式标识判定标题级别。
     *
     * @param styleId 段落样式标识
     * @return 标题级别（1~6），非标题返回 0
     */
    private static int docxHeadingLevel(String styleId) {
        if (styleId == null) {
            return 0;
        }
        var matcher = DOCX_HEADING_STYLE.matcher(styleId);
        return matcher.matches() ? Integer.parseInt(matcher.group(1)) : 0;
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