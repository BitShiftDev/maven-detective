package io.github.bitshiftdev.mavendetective.core.preflight;

import io.github.bitshiftdev.mavendetective.core.env.MavenSettings;

import javax.net.ssl.SSLException;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.Authenticator;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;

/**
 * Netzzugriff des Preflights: eine HEAD-Anfrage an eine URL, ohne Zugangsdaten. Läuft nur, wenn der
 * Nutzer es verlangt ({@link Preflight.Options#network(boolean)}).
 */
public interface RepositoryProbe {

    /** Woran eine Anfrage scheiterte, bevor eine HTTP-Antwort kam. */
    enum Failure { DNS, TIMEOUT, CERTIFICATE, REFUSED, OTHER }

    /** Antwort: HTTP-Status oder {@link Failure}. */
    final class Response {
        public final int code;
        public final Failure failure;
        public final String message;
        public final long millis;

        public Response(int code, Failure failure, String message, long millis) {
            this.code = code;
            this.failure = failure;
            this.message = message;
            this.millis = millis;
        }

        public static Response status(int code, long millis) {
            return new Response(code, null, null, millis);
        }

        public static Response failed(Failure failure, String message) {
            return new Response(-1, failure, message, -1);
        }
    }

    Response head(String url, MavenSettings.Proxy proxy, int timeoutMillis);

    /** Standard: {@link HttpURLConnection}, Proxy aus settings.xml (ohne Anmeldung). */
    RepositoryProbe HTTP = new RepositoryProbe() {
        @Override
        public Response head(String url, MavenSettings.Proxy proxy, int timeoutMillis) {
            long start = System.nanoTime();
            HttpURLConnection c = null;
            try {
                URL u = new URL(url);
                Proxy p = proxy == null ? Proxy.NO_PROXY
                        : new Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(proxy.host, proxy.port));
                c = (HttpURLConnection) u.openConnection(p);
                NoCredentials.apply(c);
                c.setRequestMethod("HEAD");
                c.setConnectTimeout(timeoutMillis);
                c.setReadTimeout(timeoutMillis);
                c.setInstanceFollowRedirects(true);
                c.setUseCaches(false);
                c.setRequestProperty("User-Agent", "maven-detective-preflight");
                int code = c.getResponseCode();
                return Response.status(code, (System.nanoTime() - start) / 1000000L);
            } catch (UnknownHostException e) {
                return Response.failed(Failure.DNS, e.getMessage());
            } catch (SocketTimeoutException e) {
                return Response.failed(Failure.TIMEOUT, e.getMessage());
            } catch (SSLException e) {
                return Response.failed(Failure.CERTIFICATE, e.getMessage());
            } catch (ConnectException e) {
                return Response.failed(Failure.REFUSED, e.getMessage());
            } catch (IOException | RuntimeException e) {
                return Response.failed(Failure.OTHER, e.getClass().getSimpleName() + ": " + e.getMessage());
            } finally {
                if (c != null) {
                    c.disconnect();
                }
            }
        }
    };

    /**
     * Sorgt dafür, dass die Probe nie Zugangsdaten sendet. Ohne eigenen Authenticator fragt
     * {@link HttpURLConnection} bei 401/407 {@link Authenticator#getDefault()} – in der IDE ist das deren
     * Authenticator, der gemerkte Zugangsdaten liefern oder einen Dialog zeigen kann – und schickt Daten aus
     * dem JVM-weiten Anmelde-Cache mit. Ein eigener Authenticator je Verbindung (ab Java 9; der Kern bleibt
     * Java-8-Bytecode, daher Reflection) liefert nichts und hat einen eigenen, leeren Cache.
     */
    final class NoCredentials extends Authenticator {
        private static final Authenticator INSTANCE = new NoCredentials();
        private static final Method SET_AUTHENTICATOR = find();

        private NoCredentials() {
        }

        private static Method find() {
            try {
                return HttpURLConnection.class.getMethod("setAuthenticator", Authenticator.class);
            } catch (NoSuchMethodException | RuntimeException e) {
                return null;   // Java 8: es gibt nur den globalen Authenticator, und die IDE läuft nie auf Java 8
            }
        }

        /** {@code true}, wenn die Verbindung den leeren Authenticator bekommen hat. */
        static boolean apply(HttpURLConnection c) {
            if (SET_AUTHENTICATOR == null) {
                return false;
            }
            try {
                SET_AUTHENTICATOR.invoke(c, INSTANCE);
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        @Override
        protected java.net.PasswordAuthentication getPasswordAuthentication() {
            return null;
        }
    }
}
