package gateway;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.apache.fontbox.ttf.TrueTypeCollection;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.text.PDFTextStripper;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

final class Media {
    static final int MAX_BYTES = 5 * 1024 * 1024;
    private static final int MAX_TEXT = 24_000;
    private static final String CONTENT_TYPES = "http://schemas.openxmlformats.org/package/2006/content-types";
    private static final String PACKAGE_REL = "http://schemas.openxmlformats.org/package/2006/relationships";
    private static final String OFFICE_REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    private static final String WORD = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final String SHEET = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
    private static final String XML = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>";

    static String name(String raw) {
        String name = raw.replaceAll("[\\p{Cntrl}\\\\/:*?\"<>|]", "_").strip();
        if (name.isBlank() || name.length() > 120) throw new IllegalArgumentException("Invalid file name");
        return name;
    }

    static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    static String mime(String name) {
        return switch (extension(name)) {
            case "jpg", "jpeg" -> "image/jpeg";
            case "png" -> "image/png";
            case "pdf" -> "application/pdf";
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "txt", "md", "csv", "json" -> "text/plain";
            default -> "application/octet-stream";
        };
    }

    static byte[] jpeg(byte[] bytes) throws IOException {
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IOException("Invalid image");
            var reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width < 1 || height < 1 || (long) width * height > 24_000_000) {
                    throw new IOException("Image dimensions exceed limit");
                }
                var params = reader.getDefaultReadParam();
                int sample = Math.max(1, Math.max(width, height) / 1400);
                params.setSourceSubsampling(sample, sample, 0, 0);
                BufferedImage image = reader.read(0, params);
                double scale = Math.min(1, 1400.0 / Math.max(image.getWidth(), image.getHeight()));
                BufferedImage result = new BufferedImage(Math.max(1, (int) (image.getWidth() * scale)),
                        Math.max(1, (int) (image.getHeight() * scale)), BufferedImage.TYPE_INT_RGB);
                Graphics2D graphics = result.createGraphics();
                try {
                    graphics.setColor(Color.WHITE);
                    graphics.fillRect(0, 0, result.getWidth(), result.getHeight());
                    graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                            RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                    graphics.drawImage(image, 0, 0, result.getWidth(), result.getHeight(), null);
                } finally {
                    graphics.dispose();
                }
                var encoded = new ByteArrayOutputStream();
                if (!ImageIO.write(result, "jpeg", encoded)) throw new IOException("JPEG encoder unavailable");
                return encoded.toByteArray();
            } finally {
                reader.dispose();
            }
        }
    }

    static String extract(Path path, String name) throws Exception {
        String text = switch (extension(name)) {
            case "txt", "md", "csv", "json" -> {
                try (var input = Files.newInputStream(path)) {
                    yield new String(input.readNBytes(MAX_TEXT * 4), StandardCharsets.UTF_8);
                }
            }
            case "pdf" -> {
                try (PDDocument doc = Loader.loadPDF(path.toFile())) {
                    PDFTextStripper stripper = new PDFTextStripper();
                    stripper.setEndPage(30);
                    var writer = new java.io.Writer() {
                        final StringBuilder value = new StringBuilder();
                        @Override public void write(char[] chars, int offset, int length) {
                            value.append(chars, offset, Math.min(length, Math.max(0, MAX_TEXT - value.length())));
                        }
                        @Override public void flush() {}
                        @Override public void close() {}
                        @Override public String toString() { return value.toString(); }
                    };
                    stripper.writeText(doc, writer);
                    yield writer.toString() + (doc.getNumberOfPages() > 30 ? "\n[仅提取前 30 页]" : "");
                }
            }
            case "docx" -> {
                try (ZipFile zip = new ZipFile(path.toFile())) {
                    NodeList paragraphs = xmlPart(zip, "word/document.xml").getElementsByTagNameNS(WORD, "p");
                    var value = new StringBuilder();
                    for (int i = 0; i < paragraphs.getLength() && value.length() < MAX_TEXT; i++) {
                        value.append(join((Element) paragraphs.item(i), WORD, "t")).append('\n');
                    }
                    yield value.toString();
                }
            }
            case "xlsx" -> readSheet(path);
            default -> throw new IOException("此文件已保存。内容分析支持 PDF、DOCX、XLSX 和 UTF-8 文本文件。");
        };
        if (text.isBlank()) throw new IOException("未提取到可读文字。扫描版 PDF 需要先做 OCR。");
        return text.length() <= MAX_TEXT ? text : text.substring(0, MAX_TEXT) + "\n[后续内容已截断]";
    }

    private static String readSheet(Path path) throws Exception {
        try (ZipFile zip = new ZipFile(path.toFile())) {
            List<String> shared = new ArrayList<>();
            if (zip.getEntry("xl/sharedStrings.xml") != null) {
                NodeList values = xmlPart(zip, "xl/sharedStrings.xml").getElementsByTagNameNS(SHEET, "si");
                for (int i = 0; i < values.getLength(); i++) shared.add(join((Element) values.item(i), SHEET, "t"));
            }
            var result = new StringBuilder();
            var sheets = zip.stream().filter(part -> part.getName().matches("xl/worksheets/sheet[0-9]+\\.xml"))
                    .sorted(java.util.Comparator.comparing(ZipEntry::getName)).limit(12).toList();
            for (ZipEntry part : sheets) {
                result.append(part.getName()).append('\n');
                NodeList rows = xmlPart(zip, part.getName()).getElementsByTagNameNS(SHEET, "row");
                for (int r = 0; r < rows.getLength() && result.length() < MAX_TEXT; r++) {
                    NodeList cells = ((Element) rows.item(r)).getElementsByTagNameNS(SHEET, "c");
                    for (int c = 0; c < cells.getLength() && result.length() < MAX_TEXT; c++) {
                        Element cell = (Element) cells.item(c);
                        String value = join(cell, SHEET, "v");
                        switch (cell.getAttribute("t")) {
                            case "s" -> {
                                int index = Integer.parseInt(value);
                                if (index >= 0 && index < shared.size()) value = shared.get(index);
                            }
                            case "inlineStr" -> value = join(cell, SHEET, "t");
                            default -> {}
                        }
                        result.append(cell.getAttribute("r")).append('=').append(value).append('\t');
                    }
                    result.append('\n');
                }
                if (result.length() >= MAX_TEXT) break;
            }
            return result.toString();
        }
    }

    private static Element xmlPart(ZipFile zip, String name) throws Exception {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null) throw new IOException("Missing document part");
        try (var input = zip.getInputStream(entry)) {
            byte[] bytes = input.readNBytes(2_000_001);
            if (bytes.length > 2_000_000) throw new IOException("Document part exceeds extraction limit");
            var factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            return factory.newDocumentBuilder().parse(new ByteArrayInputStream(bytes)).getDocumentElement();
        }
    }

    private static String join(Element element, String namespace, String name) {
        NodeList children = element.getElementsByTagNameNS(namespace, name);
        var value = new StringBuilder();
        for (int i = 0; i < children.getLength(); i++) value.append(children.item(i).getTextContent());
        return value.toString();
    }

    static String generate(Path path, String mode, JsonObject content) throws Exception {
        String title = content.get("title").getAsString().strip();
        if (title.isBlank()) title = "生成结果";
        title = title.substring(0, Math.min(title.length(), 65));
        if ("xlsx".equals(mode)) {
            sheet(path, content.getAsJsonArray("headers"), content.getAsJsonArray("rows"));
        } else {
            List<String> paragraphs = new ArrayList<>();
            for (JsonElement line : content.getAsJsonArray("paragraphs")) {
                paragraphs.add(line.getAsString());
            }
            if ("pdf".equals(mode)) pdf(path, title, paragraphs);
            else word(path, title, paragraphs);
        }
        return name(title + "." + mode);
    }

    private static void word(Path path, String title, List<String> paragraphs) throws IOException {
        var body = new StringBuilder("<w:p><w:r><w:rPr><w:b/><w:sz w:val=\"38\"/></w:rPr>"
                + "<w:t xml:space=\"preserve\">" + escape(title) + "</w:t></w:r></w:p>");
        for (String paragraph : paragraphs) {
            for (String line : paragraph.split("\\R", -1)) {
                body.append("<w:p><w:r><w:t xml:space=\"preserve\">")
                        .append(escape(line)).append("</w:t></w:r></w:p>");
            }
        }
        packageFile(path, "word/document.xml",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml",
                Map.of("word/document.xml", "<w:document xmlns:w=\"" + WORD + "\"><w:body>" + body
                        + "<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/>"
                        + "<w:pgMar w:top=\"1440\" w:right=\"1440\" w:bottom=\"1440\" w:left=\"1440\"/>"
                        + "</w:sectPr></w:body></w:document>"));
    }

    private static void sheet(Path path, JsonArray headers, JsonArray rows) throws IOException {
        var data = new StringBuilder();
        if (!headers.isEmpty()) sheetRow(data, headers, 1);
        for (int i = 0; i < Math.min(rows.size(), 200); i++) {
            sheetRow(data, rows.get(i).getAsJsonArray(), i + (headers.isEmpty() ? 1 : 2));
        }
        packageFile(path, "xl/workbook.xml",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml", Map.of(
                        "xl/workbook.xml", "<workbook xmlns=\"" + SHEET + "\" xmlns:r=\"" + OFFICE_REL
                                + "\"><sheets><sheet name=\"结果\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>",
                        "xl/_rels/workbook.xml.rels", "<Relationships xmlns=\"" + PACKAGE_REL
                                + "\"><Relationship Id=\"rId1\" Type=\"" + OFFICE_REL
                                + "/worksheet\" Target=\"worksheets/sheet1.xml\"/></Relationships>",
                        "xl/worksheets/sheet1.xml", "<worksheet xmlns=\"" + SHEET
                                + "\"><sheetData>" + data + "</sheetData></worksheet>"));
    }

    private static void sheetRow(StringBuilder xml, JsonArray values, int row) {
        xml.append("<row r=\"").append(row).append("\">");
        for (int c = 0; c < Math.min(26, values.size()); c++) {
            JsonElement value = values.get(c);
            if (value.isJsonNull()) continue;
            String cell = Character.toString('A' + c) + row;
            if (value.getAsJsonPrimitive().isNumber() && Double.isFinite(value.getAsDouble())) {
                xml.append("<c r=\"").append(cell).append("\"><v>").append(value).append("</v></c>");
            } else {
                xml.append("<c r=\"").append(cell).append("\" t=\"inlineStr\"><is><t xml:space=\"preserve\">")
                        .append(escape(value.getAsString())).append("</t></is></c>");
            }
        }
        xml.append("</row>");
    }

    private static void packageFile(Path path, String main, String type, Map<String, String> parts) throws IOException {
        try (var zip = new ZipOutputStream(Files.newOutputStream(path))) {
            String sheet = parts.containsKey("xl/worksheets/sheet1.xml")
                    ? "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>"
                    : "";
            put(zip, "[Content_Types].xml", "<Types xmlns=\"" + CONTENT_TYPES + "\">"
                    + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                    + "<Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/" + main
                    + "\" ContentType=\"" + type + "\"/>" + sheet + "</Types>");
            put(zip, "_rels/.rels", "<Relationships xmlns=\"" + PACKAGE_REL
                    + "\"><Relationship Id=\"rId1\" Type=\"" + OFFICE_REL
                    + "/officeDocument\" Target=\"" + main + "\"/></Relationships>");
            for (var part : parts.entrySet()) put(zip, part.getKey(), part.getValue());
        }
    }

    private static void put(ZipOutputStream zip, String name, String xml) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write((XML + xml).getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static String escape(String text) {
        return text.replaceAll("[^\\u0009\\u000A\\u000D\\u0020-\\uD7FF\\uE000-\\uFFFD\\x{10000}-\\x{10FFFF}]", "")
                .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static void pdf(Path path, String title, List<String> paragraphs) throws Exception {
        String custom = System.getenv("MYAI_PDF_FONT");
        Path fontFile = custom == null || custom.isBlank()
                ? Path.of(System.getenv().getOrDefault("WINDIR", "C:/Windows"), "Fonts", "msyh.ttc")
                : Path.of(custom);
        if (!Files.isRegularFile(fontFile)) throw new IOException("Configure MYAI_PDF_FONT with a Chinese TrueType font");
        boolean collectionFile = extension(fontFile.toString()).equals("ttc");
        try (var collection = collectionFile ? new TrueTypeCollection(fontFile.toFile()) : null;
             var doc = new PDDocument()) {
            PDType0Font font;
            if (collection != null) {
                TrueTypeFont regular = collection.getFontByName(System.getenv()
                        .getOrDefault("MYAI_PDF_FONT_FACE", "MicrosoftYaHei"));
                if (regular == null) throw new IOException("Configure the font face with MYAI_PDF_FONT_FACE");
                font = PDType0Font.load(doc, regular, true);
            } else {
                font = PDType0Font.load(doc, fontFile.toFile());
            }
            doc.getDocumentInformation().setTitle(title);
            List<String> lines = new ArrayList<>(wrap(font, title, 18));
            int titleLines = lines.size();
            lines.add("");
            for (String paragraph : paragraphs) {
                for (String line : paragraph.split("\\R", -1)) lines.addAll(wrap(font, line, 11));
                lines.add("");
            }
            for (int first = 0; first < lines.size(); first += 43) {
                PDPage page = new PDPage(PDRectangle.A4);
                doc.addPage(page);
                try (var stream = new PDPageContentStream(doc, page)) {
                    stream.beginText();
                    stream.setLeading(17);
                    stream.newLineAtOffset(50, 785);
                    for (int i = first; i < Math.min(first + 43, lines.size()); i++) {
                        stream.setFont(font, i < titleLines ? 18 : 11);
                        stream.showText(lines.get(i));
                        stream.newLine();
                    }
                    stream.endText();
                }
            }
            doc.save(path.toFile());
        }
    }

    private static List<String> wrap(PDType0Font font, String text, int size) throws IOException {
        var lines = new ArrayList<String>();
        var line = new StringBuilder();
        float width = 0;
        for (int code : text.codePoints().toArray()) {
            String glyph = Character.isISOControl(code) ? " " : Character.toString(code);
            float advance;
            try {
                advance = font.getStringWidth(glyph) * size / 1000;
            } catch (IllegalArgumentException missing) {
                glyph = "?";
                advance = font.getStringWidth(glyph) * size / 1000;
            }
            if (width + advance > 490) {
                lines.add(line.toString());
                line.setLength(0);
                width = 0;
            }
            line.append(glyph);
            width += advance;
        }
        lines.add(line.toString());
        return lines;
    }

    private Media() {}
}
