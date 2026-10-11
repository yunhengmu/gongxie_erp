package org.dromara.ai.util;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.dromara.ai.util.AiDocumentParser.ParsedBlock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AiDocumentParser} 溯源元数据解析测试：Markdown 标题层级 / 章节路径、docx 标题样式、pdf 页码。
 */
@DisplayName("文档解析：溯源元数据")
class AiDocumentParserTraceTest {

    private static ParsedBlock block(List<ParsedBlock> blocks, String text) {
        return blocks.stream().filter(block -> text.equals(block.text())).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("Markdown 按 # 识别标题层级并维护标题栈生成章节路径")
    void shouldParseMarkdownHeadingPath() {
        String md = "# 第2章\n## 2.3 考勤\n正文内容A\n# 第3章\n正文内容B\n";
        List<ParsedBlock> blocks = AiDocumentParser.parse(md.getBytes(StandardCharsets.UTF_8), "md");

        ParsedBlock h1 = block(blocks, "# 第2章");
        assertEquals(1, h1.headingLevel());
        assertEquals("第2章", h1.headingPath());

        ParsedBlock h2 = block(blocks, "## 2.3 考勤");
        assertEquals(2, h2.headingLevel());
        assertEquals("第2章 > 2.3 考勤", h2.headingPath());

        ParsedBlock body = block(blocks, "正文内容A");
        assertEquals(0, body.headingLevel());
        assertEquals("第2章 > 2.3 考勤", body.headingPath());

        // 新的一级标题应清空更深层级
        ParsedBlock bodyB = block(blocks, "正文内容B");
        assertEquals("第3章", bodyB.headingPath());
    }

    @Test
    @DisplayName("docx 按段落样式判定标题层级并生成章节路径")
    void shouldParseDocxHeadingStyle() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (XWPFDocument document = new XWPFDocument()) {
            XWPFParagraph heading = document.createParagraph();
            heading.setStyle("Heading1");
            heading.createRun().setText("第1章");
            XWPFParagraph sub = document.createParagraph();
            sub.setStyle("2");
            sub.createRun().setText("1.1 概述");
            XWPFParagraph body = document.createParagraph();
            body.createRun().setText("正文段落");
            document.write(out);
        }
        List<ParsedBlock> blocks = AiDocumentParser.parse(out.toByteArray(), "docx");

        assertEquals(1, block(blocks, "第1章").headingLevel());
        assertEquals("第1章", block(blocks, "第1章").headingPath());
        assertEquals(2, block(blocks, "1.1 概述").headingLevel());
        assertEquals("第1章 > 1.1 概述", block(blocks, "1.1 概述").headingPath());
        ParsedBlock body = block(blocks, "正文段落");
        assertEquals(0, body.headingLevel());
        assertEquals("第1章 > 1.1 概述", body.headingPath());
    }

    @Test
    @DisplayName("pdf 逐页成块并标注页码，标题不猜")
    void shouldParsePdfPages() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (PDDocument document = new PDDocument()) {
            addPage(document, "PAGE-ONE");
            addPage(document, "PAGE-TWO");
            document.save(out);
        }
        List<ParsedBlock> blocks = AiDocumentParser.parse(out.toByteArray(), "pdf");

        assertEquals(2, blocks.size());
        assertEquals(1, blocks.get(0).pageNo());
        assertEquals(2, blocks.get(1).pageNo());
        assertEquals(0, blocks.get(0).headingLevel());
        assertTrue(blocks.get(1).text().contains("PAGE-TWO"));
        // 旧 extract 仍返回拼接后的纯文本
        assertTrue(AiDocumentParser.extract(out.toByteArray(), "pdf").contains("PAGE-ONE"));
    }

    private static void addPage(PDDocument document, String text) throws Exception {
        PDPage page = new PDPage();
        document.addPage(page);
        try (PDPageContentStream content = new PDPageContentStream(document, page)) {
            content.beginText();
            content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
            content.newLineAtOffset(50, 700);
            content.showText(text);
            content.endText();
        }
    }

}