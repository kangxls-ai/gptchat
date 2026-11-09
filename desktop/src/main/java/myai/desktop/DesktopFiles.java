package myai.desktop;

import com.google.gson.JsonObject;

import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.swing.ImageIcon;
import java.awt.Color;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Locale;

final class DesktopFiles {
    static final int MAX_BYTES = 5 * 1024 * 1024;
    private static final int MAX_ENCODED = (MAX_BYTES + 2) / 3 * 4;

    private DesktopFiles() {}

    static JsonObject upload(Path path) throws IOException {
        String name = path.getFileName().toString();
        String extension = name.toLowerCase(Locale.ROOT);
        if (extension.matches(".*\\.(mp4|mov|mkv|webm|avi|m4v|3gp|mpeg)$")) {
            throw new IOException("请选择图片或文件。");
        }
        byte[] bytes;
        try (var input = Files.newInputStream(path)) { bytes = input.readNBytes(MAX_BYTES + 1); }
        if (bytes.length == 0 || bytes.length > MAX_BYTES) throw new IOException("每个附件需在 5 MiB 以内。");
        if (extension.matches(".*\\.(jpg|jpeg|png|gif|bmp|tiff?)$")) {
            BufferedImage image = scaled(bytes, 1400);
            var output = new ByteArrayOutputStream();
            if (!ImageIO.write(image, "jpeg", output)) throw new IOException("无法编码图片。");
            bytes = output.toByteArray();
            name = name.substring(0, name.lastIndexOf('.')) + ".jpg";
        }
        JsonObject attachment = new JsonObject();
        attachment.addProperty("name", safeName(name));
        attachment.addProperty("data", Base64.getEncoder().encodeToString(bytes));
        return attachment;
    }

    static String safeName(String value) {
        String cleaned = value.replaceAll("[\\p{Cntrl}\\\\/:*?\"<>|]", "_").strip();
        if (cleaned.isEmpty() || cleaned.equals(".") || cleaned.equals("..")) return "附件";
        if (cleaned.length() <= 110) return cleaned;
        int dot = cleaned.lastIndexOf('.');
        return cleaned.substring(0, 90) + (dot >= 90 && cleaned.length() - dot <= 15 ? cleaned.substring(dot) : "");
    }

    static byte[] download(String data) throws IOException {
        if (data.length() > MAX_ENCODED) throw new IOException("下载超过 5 MiB。");
        try {
            byte[] bytes = Base64.getDecoder().decode(data);
            if (bytes.length == 0 || bytes.length > MAX_BYTES) throw new IOException("附件大小无效。");
            return bytes;
        } catch (IllegalArgumentException invalid) { throw new IOException("附件编码无效。", invalid); }
    }

    static ImageIcon preview(byte[] bytes) throws IOException { return new ImageIcon(scaled(bytes, 620)); }

    private static BufferedImage scaled(byte[] bytes, int limit) throws IOException {
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IOException("无法识别图片。");
            var reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width < 1 || height < 1 || (long) width * height > 32_000_000) {
                    throw new IOException("图片像素尺寸超限。");
                }
                var params = reader.getDefaultReadParam();
                int sampling = Math.max(1, Math.max(width, height) / limit);
                params.setSourceSubsampling(sampling, sampling, 0, 0);
                BufferedImage source = reader.read(0, params);
                double ratio = Math.min(1, limit / (double) Math.max(source.getWidth(), source.getHeight()));
                var output = new BufferedImage(Math.max(1, (int) (source.getWidth() * ratio)),
                        Math.max(1, (int) (source.getHeight() * ratio)), BufferedImage.TYPE_INT_RGB);
                var paint = output.createGraphics();
                try {
                    paint.setColor(Color.WHITE);
                    paint.fillRect(0, 0, output.getWidth(), output.getHeight());
                    paint.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                    paint.drawImage(source, 0, 0, output.getWidth(), output.getHeight(), null);
                } finally { paint.dispose(); }
                return output;
            } finally { reader.dispose(); }
        }
    }
}
