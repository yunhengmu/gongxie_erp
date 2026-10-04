package org.dromara.ai.util;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AiDocumentParser} 单元测试：各格式分支与不支持格式异常（文档全部内存构造）。
 */
@DisplayName("知识库文档文本提取器")
class AiDocumentParserTest {

    @Test
    @DisplayName("是否支持的格式判定（大小写与点号归一）")
    void shouldJudgeSupportedSuffix() {
        assertTrue(AiDocumentParser.supported("txt"));
        assertTrue(AiDocumentParser.supported("MD"));
        assertTrue(AiDocumentParser.supported(".docx"));
        assertTrue(AiDocumentParser.supported("PDF"));
        assertTrue(AiDocumentParser.supported("xlsx"));
        assertTrue(AiDocumentParser.supported("pptx"));
        assertTrue(AiDocumentParser.supported("html"));
        assertFalse(AiDocumentParser.supported("exe"));
        assertFalse(AiDocumentParser.supported(""));
        assertFalse(AiDocumentParser.supported(null));
    }

    @Test
    @DisplayName("纯文本读取并去除 UTF-8 BOM")
    void shouldReadPlainTextAndStripBom() {
        byte[] bytes = ("\uFEFF你好 BizBuddy").getBytes(StandardCharsets.UTF_8);
        assertTrue(AiDocumentParser.extract(bytes, "txt").startsWith("你好"));
    }

    @Test
    @DisplayName("HTML 去除脚本样式与标签并还原实体")
    void shouldStripHtml() {
        String html = "<html><head><style>.a{color:red}</style></head><body>"
            + "<h1>标题</h1><p>Hello</p><script>bad()</script><div>World&nbsp;&amp;</div></body></html>";
        String text = AiDocumentParser.extract(html.getBytes(StandardCharsets.UTF_8), "htm");
        assertTrue(text.contains("标题"));
        assertTrue(text.contains("Hello"));
        assertTrue(text.contains("World &"));
        assertFalse(text.contains("bad()"));
        assertFalse(text.contains("color:red"));
    }

    @Test
    @DisplayName("docx 提取段落与表格文本")
    void shouldReadDocx() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (XWPFDocument document = new XWPFDocument()) {
            XWPFParagraph paragraph = document.createParagraph();
            paragraph.createRun().setText("段落内容");
            XWPFTable table = document.createTable(1, 1);
            table.getRow(0).getCell(0).setText("单元格");
            document.write(out);
        }
        String text = AiDocumentParser.extract(out.toByteArray(), "docx");
        assertTrue(text.contains("段落内容"));
        assertTrue(text.contains("单元格"));
    }

    @Test
    @DisplayName("xlsx 提取工作表名与单元格文本")
    void shouldReadXlsx() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("数据表");
            sheet.createRow(0).createCell(0).setCellValue("A1值");
            workbook.write(out);
        }
        String text = AiDocumentParser.extract(out.toByteArray(), "xlsx");
        assertTrue(text.contains("【工作表】数据表"));
        assertTrue(text.contains("A1值"));
    }

    @Test
    @DisplayName("pptx 逐页提取文本框内容")
    void shouldReadPptx() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (XMLSlideShow slideShow = new XMLSlideShow()) {
            XSLFSlide slide = slideShow.createSlide();
            XSLFTextBox box = slide.createTextBox();
            box.setText("幻灯片文本");
            slideShow.write(out);
        }
        String text = AiDocumentParser.extract(out.toByteArray(), "pptx");
        assertTrue(text.contains("【第 1 页】"));
        assertTrue(text.contains("幻灯片文本"));
    }

    @Test
    @DisplayName("pdf 提取正文文本")
    void shouldReadPdf() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(50, 700);
                content.showText("HelloPDF");
                content.endText();
            }
            document.save(out);
        }
        assertTrue(AiDocumentParser.extract(out.toByteArray(), "pdf").contains("HelloPDF"));
    }

    @Test
    @DisplayName("不支持的格式抛出异常")
    void shouldRejectUnsupportedFormat() {
        ServiceException ex = assertThrows(ServiceException.class,
            () -> AiDocumentParser.extract("x".getBytes(StandardCharsets.UTF_8), "exe"));
        assertTrue(ex.getMessage().contains("暂不支持"));
    }

    @Test
    @DisplayName("后缀为空视为不支持")
    void shouldRejectBlankSuffix() {
        assertThrows(ServiceException.class,
            () -> AiDocumentParser.extract("x".getBytes(StandardCharsets.UTF_8), null));
    }

    @Test
    @DisplayName("内容损坏时抛出解析失败异常")
    void shouldWrapParseFailure() {
        ServiceException ex = assertThrows(ServiceException.class,
            () -> AiDocumentParser.extract("not-a-docx".getBytes(StandardCharsets.UTF_8), "docx"));
        assertTrue(ex.getMessage().contains("文档解析失败"));
    }

}