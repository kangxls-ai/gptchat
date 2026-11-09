package myai.desktop;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class GatewayClientTest {
    private static final String PIN = "AB".repeat(32);
    private static final String SECRET = "A".repeat(43);
    private static final String PASSWORD = PIN + "." + SECRET;

    @Test void acceptsTunnelAndKeepsSecretsOutOfDiagnostics() {
        var pairing = GatewayClient.Pairing.parse("example.net:4000", PASSWORD);
        assertEquals("wss://example.net:4000", pairing.endpoint().toString());
        assertEquals(PIN.toLowerCase(java.util.Locale.ROOT) + "." + SECRET, pairing.password());
        assertFalse(pairing.toString().contains(SECRET));
    }

    @Test void rejectsUnencryptedAndNonRootEndpoints() {
        for (String value : new String[]{"ws://localhost:4000", "example.net:4000/path", "example.net",
                "user@example.net:4000", "example.net:4000?token=x"}) {
            assertThrows(IllegalArgumentException.class, () -> GatewayClient.Pairing.parse(value, PASSWORD), value);
        }
        assertThrows(IllegalArgumentException.class, () -> GatewayClient.Pairing.parse("localhost:4000", "123456"));
    }

    @Test void roundTripsAttachmentAndRejectsOversizedInput(@TempDir Path temp) throws Exception {
        Path file = temp.resolve("sample.txt");
        byte[] payload = "测试附件".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(file, payload);
        var upload = DesktopFiles.upload(file);
        assertEquals("sample.txt", upload.get("name").getAsString());
        assertArrayEquals(payload, DesktopFiles.download(upload.get("data").getAsString()));
        assertThrows(java.io.IOException.class, () -> DesktopFiles.download("???"));
        byte[] tooLarge = new byte[DesktopFiles.MAX_BYTES + 1];
        Files.write(file, tooLarge);
        assertThrows(java.io.IOException.class, () -> DesktopFiles.upload(file));
        assertThrows(java.io.IOException.class, () -> DesktopFiles.download(Base64.getEncoder().encodeToString(tooLarge)));
    }

    @Test void createsNativeBrandAssets(@TempDir Path temp) throws Exception {
        MyAI.main(new String[]{"--write-icons", temp.toString()});
        assertArrayEquals(new byte[]{0, 0, 1, 0}, java.util.Arrays.copyOf(Files.readAllBytes(temp.resolve("MyAI.ico")), 4));
        assertEquals("icns", new String(Files.readAllBytes(temp.resolve("MyAI.icns")), 0, 4,
                java.nio.charset.StandardCharsets.US_ASCII));
    }
}
