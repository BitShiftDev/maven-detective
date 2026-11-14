package io.github.bitshiftdev.mavendetective.core.preflight;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.Authenticator;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Review #49: die HEAD-Probe sendet nie Zugangsdaten, auch wenn die JVM einen Authenticator hat (IDE). */
class RepositoryProbeTest {

    @AfterEach
    void reset() {
        Authenticator.setDefault(null);
    }

    @Test
    void probeIgnoresTheJvmAuthenticator() throws Exception {
        AtomicInteger asked = new AtomicInteger();
        Authenticator.setDefault(new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                asked.incrementAndGet();
                return new PasswordAuthentication("user", "secret".toCharArray());
            }
        });
        List<String> authorization = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            String a = exchange.getRequestHeaders().getFirst("Authorization");
            if (a != null) {
                authorization.add(a);
            }
            exchange.getResponseHeaders().add("WWW-Authenticate", "Basic realm=\"nexus\"");
            exchange.sendResponseHeaders(401, -1);
            exchange.close();
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/repository/maven-releases/";
            RepositoryProbe.Response r = RepositoryProbe.HTTP.head(url, null, 5000);
            assertEquals(401, r.code);
        } finally {
            server.stop(0);
        }
        assertEquals(List.of(), authorization);
        assertEquals(0, asked.get());
    }
}
