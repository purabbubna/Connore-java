package connore;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Map;
import java.util.Properties;

/** Small website adapter. Feature logic and JDBC stay outside HTTP handling. */
public class WebServer implements HttpHandler {
    private static final String COOKIE = "CONNORE_LAB_SESSION";
    private final HttpServer server;
    private final Connore features;
    private final ConnectionManager connections;
    private final ArrayList<Session> sessions = new ArrayList<Session>();
    private final ArrayList<Attempt> attempts = new ArrayList<Attempt>();
    private final Path webRoot;
    private final String collegeDomain;
    private final long sessionMillis;

    // Assignment 6: helper classes used only by their containing class.
    private static class Session {
        String token;
        int student;
        long expires;
        Session(String token, int student, long expires) {
            this.token = token;
            this.student = student;
            this.expires = expires;
        }
    }

    private static class Attempt {
        String address;
        int count;
        long expires;
        Attempt(String address, long expires) {
            this.address = address;
            this.expires = expires;
        }
    }

    public WebServer(Properties settings, Connore features, ConnectionManager connections) throws IOException {
        this.features = features;
        this.connections = connections;
        this.collegeDomain = settings.getProperty("COLLEGE_DOMAIN");
        this.sessionMillis = Long.parseLong(settings.getProperty("SESSION_MINUTES")) * 60000;
        this.webRoot = Path.of("dist").toRealPath();
        server = HttpServer.create(new InetSocketAddress(settings.getProperty("SERVER_HOST"),
            Integer.parseInt(settings.getProperty("SERVER_PORT"))), 0);
        server.createContext("/", this);
        // Use the JDK's default dispatcher; no worker pool or background scheduler.
        server.setExecutor(null);
    }

    public void start() { server.start(); }
    public int port() { return server.getAddress().getPort(); }
    public void stop() { server.stop(1); }

    // Serial request processing is deliberate for this small teaching version.
    public synchronized void handle(HttpExchange exchange) throws IOException {
        try {
            headers(exchange);
            if (!exchange.getRequestURI().getPath().startsWith("/api/")) {
                staticFile(exchange);
                return;
            }
            int student = authorize(exchange);
            String method = exchange.getRequestMethod();
            Map<String, Object> body = Json.map();
            if (method.equals("POST") || method.equals("PUT")) {
                String type = exchange.getRequestHeaders().getFirst("Content-Type");
                AppException.require(type != null && type.toLowerCase(java.util.Locale.ROOT).startsWith("application/json"), 415, "JSON_REQUIRED");
                byte[] data = exchange.getRequestBody().readNBytes(65537);
                AppException.require(data.length <= 65536, 413, "REQUEST_TOO_LARGE");
                body = Json.object(new String(data, StandardCharsets.UTF_8));
            }
            route(exchange, student, body);
        } catch (AppException exception) {
            reply(exchange, exception.status, Json.map("error", exception.code));
        } catch (SQLException exception) {
            System.err.println("Database request failed (SQL state " + exception.getSQLState() + ").");
            reply(exchange, 500, Json.map("error", "SERVER_ERROR"));
        } catch (Exception exception) {
            System.err.println("Request failed: " + exception.getClass().getSimpleName());
            reply(exchange, 500, Json.map("error", "SERVER_ERROR"));
        } finally {
            exchange.close();
        }
    }

    private void route(HttpExchange exchange, int student, Map<String, Object> body)
            throws IOException, SQLException, AppException {
        String path = exchange.getRequestURI().getPath().substring(4);
        String method = exchange.getRequestMethod();
        String[] parts = path.split("/");
        Object result = Json.map();
        int status = 200;

        // Authentication.
        if (method.equals("POST") && path.equals("/auth/register")) {
            result = Json.map("accountId", features.register(string(body, "email"), string(body, "password")));
            status = 201;
        } else if (method.equals("POST") && path.equals("/auth/login")) {
            int id = features.login(string(body, "email"), string(body, "password"));
            createSession(exchange, id);
            result = Json.map("studentId", id, "hasProfile", features.hasProfile(id));
        } else if (method.equals("POST") && path.equals("/auth/logout")) {
            removeSession(exchange);
            exchange.getResponseHeaders().add("Set-Cookie", COOKIE + "=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0");
            status = 204;
        } else if (method.equals("GET") && path.equals("/session")) {
            result = Json.map("studentId", student, "hasProfile", features.hasProfile(student));

        // Student profile and interests.
        } else if (method.equals("GET") && path.equals("/profile")) {
            result = features.profile(student, true);
        } else if (method.equals("PUT") && path.equals("/profile")) {
            result = features.saveProfile(student, string(body, "fullName"), string(body, "department"),
                optionalId(body, "yearOfStudy"), string(body, "bio"));
        } else if (method.equals("PUT") && path.equals("/profile/privacy")) {
            features.privacy(student, bool(body, "suggestible"), bool(body, "showDepartment"), bool(body, "showYear"));
        } else if (method.equals("GET") && path.equals("/interests")) {
            result = features.interests();
        } else if (method.equals("GET") && path.equals("/profile/interests")) {
            result = features.interestIds(student);
        } else if (method.equals("POST") && path.equals("/profile/interests")) {
            features.addInterest(student, requiredId(body, "interestId"));
        } else if (method.equals("DELETE") && path.matches("/profile/interests/[0-9]+")) {
            features.removeInterest(student, pathId(parts[3]));
            status = 204;

        // Social handles and matching.
        } else if (method.equals("GET") && path.equals("/profile/social-links")) {
            result = features.socialLinks(student, false);
        } else if (method.equals("POST") && path.equals("/profile/social-links")) {
            result = features.addSocialLink(student, string(body, "platform"), string(body, "handle"));
            status = 201;
        } else if (method.equals("PUT") && path.matches("/profile/social-links/[0-9]+")) {
            features.shareSocialLink(pathId(parts[3]), student, bool(body, "shared"));
        } else if (method.equals("GET") && path.equals("/matches")) {
            connections.expire();
            result = features.matches(student, queryInt(exchange, "limit", 30));

        // Community membership.
        } else if (method.equals("GET") && path.equals("/communities")) {
            result = features.communities(student);
        } else if (method.equals("POST") && path.matches("/communities/[0-9]+/join")) {
            features.joinCommunity(student, pathId(parts[2]));
        } else if (method.equals("DELETE") && path.matches("/communities/[0-9]+/membership")) {
            features.leaveCommunity(student, pathId(parts[2]));
            status = 204;
        } else if (method.equals("GET") && path.matches("/communities/[0-9]+/members")) {
            result = features.communityMembers(pathId(parts[2]), student);

        // Connection requests.
        } else if (method.equals("GET") && path.equals("/connections")) {
            result = connections.list(student, query(exchange, "view", "incoming"));
        } else if (method.equals("POST") && path.equals("/connections")) {
            result = connections.send(student, requiredId(body, "toStudentId"), optionalId(body, "interestId"));
        } else if (method.equals("POST") && path.matches("/connections/[0-9]+/respond")) {
            connections.respond(pathId(parts[2]), student, bool(body, "accept"));
        } else if (method.equals("POST") && path.matches("/connections/[0-9]+/block")) {
            connections.block(pathId(parts[2]), student);
        } else throw new AppException(404, "NOT_FOUND");

        reply(exchange, status, result);
    }

    private int authorize(HttpExchange exchange) throws AppException {
        long now = System.currentTimeMillis();
        for (int index = sessions.size() - 1; index >= 0; index--) {
            if (sessions.get(index).expires <= now) sessions.remove(index);
        }
        for (int index = attempts.size() - 1; index >= 0; index--) {
            if (attempts.get(index).expires <= now) attempts.remove(index);
        }
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        if (!method.equals("GET")) {
            AppException.require("1".equals(exchange.getRequestHeaders().getFirst("X-Connore-Request")), 403, "FORBIDDEN");
            String origin = exchange.getRequestHeaders().getFirst("Origin");
            String host = exchange.getRequestHeaders().getFirst("Host");
            AppException.require(origin == null || origin.equals("http://" + host) || origin.equals("https://" + host), 403, "FORBIDDEN");
        }
        if (method.equals("POST") && (path.equals("/api/auth/login") || path.equals("/api/auth/register"))) {
            String address = exchange.getRemoteAddress().getAddress().getHostAddress();
            Attempt attempt = null;
            for (Attempt item : attempts) {
                if (item.address.equals(address)) { attempt = item; break; }
            }
            if (attempt == null) {
                attempt = new Attempt(address, now + 600000);
                attempts.add(attempt);
            }
            attempt.count++;
            AppException.require(attempt.count <= 30, 429, "TOO_MANY_ATTEMPTS");
            return 0;
        }
        String token = token(exchange);
        for (Session session : sessions) {
            if (session.token.equals(token)) {
                session.expires = now + sessionMillis;
                return session.student;
            }
        }
        throw new AppException(401, "UNAUTHENTICATED");
    }

    private String token(HttpExchange exchange) {
        String cookies = exchange.getRequestHeaders().getFirst("Cookie");
        if (cookies != null) {
            for (String part : cookies.split(";")) {
                String[] pair = part.trim().split("=", 2);
                if (pair.length == 2 && pair[0].equals(COOKIE)) return pair[1];
            }
        }
        return "";
    }

    private void removeSession(HttpExchange exchange) {
        String token = token(exchange);
        for (int index = sessions.size() - 1; index >= 0; index--) {
            if (sessions.get(index).token.equals(token)) sessions.remove(index);
        }
    }

    private void createSession(HttpExchange exchange, int student) {
        removeSession(exchange);
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        sessions.add(new Session(token, student, System.currentTimeMillis() + sessionMillis));
        exchange.getResponseHeaders().add("Set-Cookie", COOKIE + "=" + token + "; Path=/; HttpOnly; SameSite=Strict");
    }

    private void staticFile(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        if (!method.equals("GET") && !method.equals("HEAD")) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }
        String path = exchange.getRequestURI().getPath();
        if (path.equals("/js/config.js")) {
            send(exchange, "text/javascript; charset=utf-8", ("window.CONNORE_CONFIG="
                + Json.stringify(Json.map("collegeDomain", collegeDomain)) + ";").getBytes(StandardCharsets.UTF_8));
            return;
        }
        if (path.equals("/") || path.matches("/(home|profile|communities|community|login|register)\\.html")) path = "/index.html";
        Path file = webRoot.resolve(path.substring(1)).normalize();
        if (!file.startsWith(webRoot) || !Files.isRegularFile(file) || !file.toRealPath().startsWith(webRoot)) {
            exchange.sendResponseHeaders(404, -1);
            return;
        }
        String type = "application/octet-stream";
        if (path.endsWith(".html")) type = "text/html; charset=utf-8";
        else if (path.endsWith(".css")) type = "text/css; charset=utf-8";
        else if (path.endsWith(".js")) type = "text/javascript; charset=utf-8";
        else if (path.endsWith(".svg")) type = "image/svg+xml";
        send(exchange, type, Files.readAllBytes(file));
    }

    private void send(HttpExchange exchange, String type, byte[] data) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", type);
        if (exchange.getRequestMethod().equals("HEAD")) {
            exchange.getResponseHeaders().set("Content-Length", String.valueOf(data.length));
            exchange.sendResponseHeaders(200, -1);
        } else {
            exchange.sendResponseHeaders(200, data.length);
            exchange.getResponseBody().write(data);
        }
    }

    private void reply(HttpExchange exchange, int status, Object result) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        if (status == 204) exchange.sendResponseHeaders(204, -1);
        else {
            byte[] data = Json.stringify(result).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, data.length);
            exchange.getResponseBody().write(data);
        }
    }

    private void headers(HttpExchange exchange) {
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("Referrer-Policy", "same-origin");
        exchange.getResponseHeaders().set("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self'; connect-src 'self'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'");
    }

    private String string(Map<String, Object> body, String key) throws AppException {
        Object value = body.get(key);
        AppException.require(value == null || value instanceof String, 400, "INVALID_INPUT");
        return value == null ? "" : (String) value;
    }

    private boolean bool(Map<String, Object> body, String key) throws AppException {
        Object value = body.get(key);
        AppException.require(value instanceof Boolean, 400, "INVALID_INPUT");
        return ((Boolean) value).booleanValue();
    }

    private Integer optionalId(Map<String, Object> body, String key) throws AppException {
        Object value = body.get(key);
        if (value == null) return null;
        AppException.require(value instanceof Number, 400, "INVALID_INPUT");
        double number = ((Number) value).doubleValue();
        AppException.require(Double.isFinite(number) && number >= 1 && number <= Integer.MAX_VALUE && number == Math.rint(number), 400, "INVALID_INPUT");
        return Integer.valueOf((int) number);
    }

    private int requiredId(Map<String, Object> body, String key) throws AppException {
        Integer value = optionalId(body, key);
        AppException.require(value != null, 400, "INVALID_INPUT");
        return value.intValue();
    }

    private int pathId(String text) throws AppException {
        try {
            int value = Integer.parseInt(text);
            AppException.require(value > 0, 400, "INVALID_INPUT");
            return value;
        } catch (NumberFormatException exception) { throw new AppException(400, "INVALID_INPUT"); }
    }

    private String query(HttpExchange exchange, String key, String fallback) throws AppException {
        String query = exchange.getRequestURI().getRawQuery();
        if (query != null) {
            for (String part : query.split("&")) {
                String[] pair = part.split("=", 2);
                if (pair[0].equals(key)) {
                    try { return URLDecoder.decode(pair.length > 1 ? pair[1] : "", StandardCharsets.UTF_8); }
                    catch (IllegalArgumentException exception) { throw new AppException(400, "INVALID_INPUT"); }
                }
            }
        }
        return fallback;
    }

    private int queryInt(HttpExchange exchange, String key, int fallback) throws AppException {
        try { return Integer.parseInt(query(exchange, key, String.valueOf(fallback))); }
        catch (NumberFormatException exception) { throw new AppException(400, "INVALID_INPUT"); }
    }
}
