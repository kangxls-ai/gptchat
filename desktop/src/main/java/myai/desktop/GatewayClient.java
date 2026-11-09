package myai.desktop;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.drafts.Draft_6455;
import org.java_websocket.handshake.ServerHandshake;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.URI;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.HexFormat;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

final class GatewayClient extends WebSocketClient {
    private static final Gson JSON = new Gson();
    private final Pairing pairing;
    private final Consumer<JsonObject> events;
    private final BiConsumer<Integer, String> closed;

    record Pairing(URI endpoint, String fingerprint, String secret) {
        static Pairing parse(String address, String password) {
            String value = address.strip();
            URI endpoint = URI.create(value.startsWith("wss://") ? value : "wss://" + value);
            if (!"wss".equals(endpoint.getScheme()) || endpoint.getHost() == null
                    || endpoint.getPort() < 1 || endpoint.getPort() > 65_535
                    || endpoint.getUserInfo() != null || endpoint.getQuery() != null || endpoint.getFragment() != null
                    || !(endpoint.getPath().isEmpty() || "/".equals(endpoint.getPath()))) {
                throw new IllegalArgumentException("请输入有效的服务器地址和端口。");
            }
            String[] parts = password.strip().split("\\.", -1);
            if (parts.length != 2 || !parts[0].matches("[a-fA-F0-9]{64}")
                    || !parts[1].matches("[A-Za-z0-9_-]{43}")) {
                throw new IllegalArgumentException("请粘贴完整的配对密码。");
            }
            return new Pairing(URI.create("wss://" + endpoint.getRawAuthority()),
                    parts[0].toLowerCase(java.util.Locale.ROOT), parts[1]);
        }

        String password() { return fingerprint + "." + secret; }

        @Override public String toString() { return "Pairing[" + endpoint + "]"; }
    }

    GatewayClient(Pairing pairing, Consumer<JsonObject> events, BiConsumer<Integer, String> closed)
            throws GeneralSecurityException {
        super(pairing.endpoint(), new Draft_6455(List.of(), 7_100_000), null, 12_000);
        this.pairing = pairing;
        this.events = events;
        this.closed = closed;
        setSocketFactory(pinnedTls(pairing.fingerprint()).getSocketFactory());
    }

    static SSLContext pinnedTls(String fingerprint) throws GeneralSecurityException {
        byte[] expected = HexFormat.of().parseHex(fingerprint);
        X509TrustManager pinned = new X509TrustManager() {
            @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            @Override public void checkClientTrusted(X509Certificate[] chain, String algorithm)
                    throws CertificateException { throw new CertificateException("Unexpected client certificate"); }
            @Override public void checkServerTrusted(X509Certificate[] chain, String algorithm)
                    throws CertificateException {
                if (chain == null || chain.length == 0) throw new CertificateException("Missing certificate");
                try {
                    byte[] actual = MessageDigest.getInstance("SHA-256").digest(chain[0].getEncoded());
                    if (!MessageDigest.isEqual(expected, actual)) {
                        throw new CertificateException("Gateway certificate fingerprint mismatch");
                    }
                    chain[0].checkValidity();
                } catch (GeneralSecurityException invalid) {
                    throw new CertificateException("Untrusted gateway", invalid);
                }
            }
        };
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, new TrustManager[]{pinned}, null);
        return context;
    }

    @Override protected void onSetSSLParameters(SSLParameters parameters) {
        // The exact pinned certificate identifies the gateway through both private and tunnel addresses.
        parameters.setEndpointIdentificationAlgorithm(null);
    }

    @Override public void onOpen(ServerHandshake handshake) {
        JsonObject request = packet("auth");
        request.addProperty("token", pairing.secret());
        request.addProperty("version", 3);
        sendPacket(request);
    }

    void sendPacket(JsonObject request) { send(JSON.toJson(request)); }

    static JsonObject packet(String type) {
        JsonObject packet = new JsonObject();
        packet.addProperty("type", type);
        return packet;
    }

    @Override public void onMessage(String message) {
        try { events.accept(JsonParser.parseString(message).getAsJsonObject()); }
        catch (IllegalStateException | com.google.gson.JsonParseException invalid) {
            close(1002, "Invalid gateway response");
        }
    }

    @Override public void onClose(int code, String reason, boolean remote) { closed.accept(code, reason); }
    @Override public void onError(Exception error) {}
}
