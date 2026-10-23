import connore.Config;
import connore.ConnectionManager;
import connore.Connore;
import connore.Database;
import connore.Json;
import connore.WebServer;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Real HTTP/JDBC regression checks. Run from the project folder after Build.java test.
 * Only a NEW randomly named local test database is written and dropped. The normal
 * connore database supplies connection credentials, never application test data.
 * Requires CREATE/DROP DATABASE permission; failure to obtain it stops the test.
 */
public class IntegrationTest {
    private static int assertions;
    private static int groups;
    private static String base;
    private static final String PASSWORD = "Lab-test-only-9482!";

    public static void main(String[] args) throws Exception {
        Properties original = Config.load();
        String url = original.getProperty("DB_URL");
        check(url.matches("(?i)jdbc:mysql://(localhost|127\\.0\\.0\\.1|\\[::1\\])(?::[0-9]+)?/connore(?:\\?.*)?"),
                "Refusing to provision tests: configure a local /connore JDBC URL");
        String database = "connore_lab_" + randomHex() + "_test";
        check(database.matches("connore_lab_[0-9a-f]{24}_test"), "Test database name guard");
        Properties test = new Properties();
        test.putAll(original);
        String testUrl = url.replaceFirst("(?i)/connore(?=\\?|$)", "/" + database);
        check(!testUrl.equals(url) && testUrl.contains("/" + database), "Test URL must address the newly generated database");
        test.setProperty("DB_URL", testUrl);
        test.setProperty("SERVER_HOST", "127.0.0.1");
        test.setProperty("SERVER_PORT", "0");
        Class.forName("com.mysql.cj.jdbc.Driver");
        WebServer server = null;
        boolean created = false;
        try (Connection admin = DriverManager.getConnection(url,
                original.getProperty("DB_USER"), original.getProperty("DB_PASSWORD"))) {
            try {
                try (PreparedStatement query = admin.prepareStatement(
                        "SELECT SCHEMA_NAME FROM information_schema.SCHEMATA WHERE SCHEMA_NAME=?")) {
                    query.setString(1, database);
                    try (ResultSet result = query.executeQuery()) {
                        check(!result.next(), "Refusing to reuse an existing database");
                    }
                }
                try (Statement statement = admin.createStatement()) {
                    statement.execute("CREATE DATABASE `" + database + "` CHARACTER SET utf8mb4");
                }
                created = true;
                Database.configure(test);
                installSchema();
                seedCatalogue();
                group("Isolated database and fixture catalogue");
                Connore features = new Connore(test.getProperty("COLLEGE_DOMAIN"));
                server = new WebServer(test, features, new ConnectionManager(features));
                server.start();
                base = "http://127.0.0.1:" + server.port();
                runJourney(test.getProperty("COLLEGE_DOMAIN"));
                System.out.println("Integration checks passed: " + assertions + " assertions in " + groups + " groups.");
            } finally {
                if (server != null) server.stop();
                Database.configure(original);
                if (created) {
                    // database is generated here, never taken from configuration or HTTP input.
                    if (!database.matches("connore_lab_[0-9a-f]{24}_test")) {
                        throw new IllegalStateException("Cleanup refused: invalid generated test database name");
                    }
                    try (Statement statement = admin.createStatement()) {
                        statement.execute("DROP DATABASE `" + database + "`");
                    }
                    System.out.println("Fresh test database removed; shared connore data was not modified.");
                }
            }
        }
    }

    private static void installSchema() throws Exception {
        String sql = Files.readString(Path.of("sql", "schema.sql"), StandardCharsets.UTF_8)
                .replace("\ufeff", "").replaceAll("(?m)^\\s*--[^\\r\\n]*", "");
        int tables = 0;
        try (Connection connection = Database.open(); Statement statement = connection.createStatement()) {
            for (String part : sql.split(";")) {
                String command = part.trim();
                if (command.length() == 0 || command.toUpperCase(java.util.Locale.ROOT).startsWith("CREATE DATABASE")
                        || command.toUpperCase(java.util.Locale.ROOT).startsWith("USE ")) continue;
                check(command.toUpperCase(java.util.Locale.ROOT).startsWith("CREATE TABLE "),
                        "Only table creation is allowed in the test schema loader");
                statement.execute(command);
                tables++;
            }
        }
        check(tables == 13, "All 13 schema tables created");
    }

    private static void seedCatalogue() throws Exception {
        Database.update("INSERT INTO category(category_id,name) VALUES(1,'Technology'),(2,'Activities')");
        Database.update("INSERT INTO interest(interest_id,name,category_id,is_approved) VALUES "
                + "(1,'Programming',1,TRUE),(2,'Photography',2,TRUE),(3,'Chess',2,TRUE),(4,'Retired interest',2,FALSE)");
        Database.update("INSERT INTO community(community_id,interest_id,description) VALUES "
                + "(1,1,'Programming community'),(2,2,'Photography community'),(3,3,'Chess community'),(4,4,'Inactive community')");
        check(count("SELECT COUNT(*) AS n FROM account") == 0, "Fresh schema has no accounts");
    }

    private static void runJourney(String domain) throws Exception {
        Client guest = new Client();
        guest.call("GET", "/api/session", null, 401);
        HttpResponse<String> home = guest.raw("GET", "/", null, false, null, null);
        check(home.statusCode() == 200 && home.body().contains("Connore"), "Frontend is served");
        check(home.headers().firstValue("Content-Security-Policy").isPresent(), "CSP header exists");
        for (String path : new String[]{"/.env", "/sql/schema.sql", "/src/main/java/connore/Config.java", "/%2e%2e/.env"}) {
            check(guest.raw("GET", path, null, false, null, null).statusCode() == 404, "Static secrets are inaccessible: " + path);
        }
        check(guest.raw("HEAD", "/", null, false, null, null).statusCode() == 200, "HEAD static response");
        group("Static frontend, headers and private-file protection");

        guest.call("POST", "/api/auth/register", Json.map("email", "wrong@example.org", "password", PASSWORD), 400);
        guest.call("POST", "/api/auth/register", Json.map("email", "short@" + domain, "password", "abc"), 400);
        Client a = new Client();
        Client b = new Client();
        Client c = new Client();
        Client d = new Client();
        int aid = register(a, "alpha", domain);
        int bid = register(b, "beta", domain);
        int cid = register(c, "gamma", domain);
        int did = register(d, "delta", domain);
        guest.call("POST", "/api/auth/register", Json.map("email", "ALPHA@" + domain, "password", PASSWORD), 409);
        guest.call("POST", "/api/auth/login", Json.map("email", "alpha@" + domain, "password", "' OR '1'='1"), 401);
        guest.call("POST", "/api/auth/login", Json.map("email", "' OR 1=1 --@" + domain, "password", PASSWORD), 401);
        check(count("SELECT COUNT(*) AS n FROM account") == 4, "Failed auth cannot create or bypass accounts");
        check(Boolean.FALSE.equals(object(a.call("GET", "/api/session", null, 200)).get("hasProfile")), "Registration does not create a profile");
        a.call("POST", "/api/profile/interests", Json.map("interestId", 1), 409);
        group("Registration, login, isolated sessions and injection resistance");

        a.call("PUT", "/api/profile", profile("", 2), 400);
        a.call("PUT", "/api/profile", profile("Alpha", 7), 400);
        a.call("PUT", "/api/profile", profile("Alpha", 1.5), 400);
        a.call("PUT", "/api/profile", profile("Alpha", 2), 200);
        b.call("PUT", "/api/profile", profile("Beta", 2), 200);
        c.call("PUT", "/api/profile", profile("Gamma", null), 200);
        d.call("PUT", "/api/profile", profile("Delta", 3), 200);
        a.call("PUT", "/api/profile", profile("Alpha edited", 2), 200);
        check(count("SELECT COUNT(*) AS n FROM student_profile") == 4, "Profile upsert does not duplicate rows");
        check(Boolean.TRUE.equals(object(a.call("GET", "/api/session", null, 200)).get("hasProfile")), "Saved profile becomes available");
        check(number(object(b.call("GET", "/api/profile", null, 200)).get("studentId")) == bid, "Sessions identify their own profiles");
        group("Profile validation, nullable year and upsert");

        List<?> categories = list(a.call("GET", "/api/interests", null, 200));
        int available = 0;
        for (Object category : categories) available += list(object(category).get("interests")).size();
        check(available == 3, "Only approved interests are returned");
        for (int id : new int[]{1, 2, 1}) a.call("POST", "/api/profile/interests", Json.map("interestId", id), 200);
        for (int id : new int[]{1, 2}) b.call("POST", "/api/profile/interests", Json.map("interestId", id), 200);
        c.call("POST", "/api/profile/interests", Json.map("interestId", 1), 200);
        d.call("POST", "/api/profile/interests", Json.map("interestId", 3), 200);
        a.call("POST", "/api/profile/interests", Json.map("interestId", 4), 404);
        a.call("POST", "/api/profile/interests", Json.map("interestId", 99999), 404);
        a.call("POST", "/api/profile/interests", Json.map("interestId", "1"), 400);
        a.call("POST", "/api/profile/interests", Json.map("interestId", 1.5), 400);
        a.call("POST", "/api/profile/interests", Json.map("interestId", 0), 400);
        check(list(a.call("GET", "/api/profile/interests", null, 200)).size() == 2, "Interest insert is idempotent");
        check(count("SELECT COUNT(*) AS n FROM community_membership") == 0, "Interest selection does not join a community");
        a.call("DELETE", "/api/profile/interests/2", null, 204);
        check(list(a.call("GET", "/api/profile/interests", null, 200)).size() == 1, "Interest deselection works");
        a.call("POST", "/api/profile/interests", Json.map("interestId", 2), 200);
        group("Approved catalogue, strict IDs, selection idempotency and removal");

        List<?> matches = list(a.call("GET", "/api/matches", null, 200));
        check(matches.size() == 2, "Only students with shared interests are suggested");
        check(number(object(matches.get(0)).get("studentId")) == bid, "Larger shared-interest count ranks first");
        check(list(object(matches.get(0)).get("sharedInterests")).size() == 2, "Shared interests are returned");
        Map<String, Object> alpha = find(list(b.call("GET", "/api/matches", null, 200)), "studentId", aid);
        check(!alpha.containsKey("department") && !alpha.containsKey("yearOfStudy"), "Public profile hides private fields");
        a.call("PUT", "/api/profile/privacy", privacy(false, false, false), 200);
        check(!contains(list(b.call("GET", "/api/matches", null, 200)), "studentId", aid), "Suggestion opt-out is enforced");
        a.call("PUT", "/api/profile/privacy", privacy(true, true, true), 200);
        alpha = find(list(b.call("GET", "/api/matches", null, 200)), "studentId", aid);
        check(alpha.containsKey("department") && alpha.containsKey("yearOfStudy"), "Opted-in public fields are returned");
        a.call("PUT", "/api/profile/privacy", privacy(true, false, false), 200);
        check(list(a.call("GET", "/api/matches?limit=1", null, 200)).size() == 1, "Match limit is respected");
        a.call("GET", "/api/matches?limit=invalid", null, 400);
        group("Match ranking, limits and profile privacy");

        List<?> communities = list(a.call("GET", "/api/communities", null, 200));
        check(communities.size() == 3, "Inactive communities are hidden");
        check(number(find(communities, "communityId", 1).get("memberCount")) == 0, "Empty community has zero members");
        a.call("POST", "/api/communities/1/join", Json.map(), 200);
        a.call("POST", "/api/communities/1/join", Json.map(), 200);
        b.call("POST", "/api/communities/1/join", Json.map(), 200);
        a.call("POST", "/api/communities/4/join", Json.map(), 404);
        check(number(find(list(a.call("GET", "/api/communities", null, 200)), "communityId", 1).get("memberCount")) == 2,
                "Membership is idempotent and counts are correct");
        Map<String, Object> member = find(list(b.call("GET", "/api/communities/1/members", null, 200)), "studentId", aid);
        check(!member.containsKey("department"), "Community member profiles also respect privacy");
        b.call("DELETE", "/api/communities/1/membership", null, 204);
        check(count("SELECT COUNT(*) AS n FROM community_membership WHERE student_id=?", bid) == 0, "Leaving removes membership");
        check(list(b.call("GET", "/api/profile/interests", null, 200)).size() == 2, "Leaving a community keeps interest selections");
        group("Community independence, membership counts and privacy");

        a.call("POST", "/api/profile/social-links", Json.map("platform", "Invalid", "handle", "alpha"), 400);
        Map<String, Object> handle = object(a.call("POST", "/api/profile/social-links", Json.map("platform", "GitHub", "handle", "alpha-test"), 201));
        int link = number(handle.get("linkId"));
        check(Boolean.FALSE.equals(handle.get("shared")), "New social links are private");
        b.call("PUT", "/api/profile/social-links/" + link, Json.map("shared", true), 403);
        a.call("PUT", "/api/profile/social-links/" + link, Json.map("shared", true), 200);
        group("Social-handle validation and ownership");

        a.call("POST", "/api/connections", Json.map("toStudentId", aid, "interestId", 1), 403);
        a.call("POST", "/api/connections", Json.map("toStudentId", 99999, "interestId", 1), 403);
        a.call("POST", "/api/connections", Json.map("toStudentId", bid, "interestId", 4), 400);
        Map<String, Object> pending = object(a.call("POST", "/api/connections", Json.map("toStudentId", bid, "interestId", 1), 200));
        int ab = number(pending.get("connectionId"));
        check("PENDING".equals(pending.get("result")), "New request is pending");
        Map<String, Object> duplicate = object(a.call("POST", "/api/connections", Json.map("toStudentId", bid, "interestId", 1), 200));
        check("ALREADY_PENDING".equals(duplicate.get("result")), "Repeated request is idempotent");
        check(number(duplicate.get("connectionId")) == ab, "Repeated request retains its identity");
        check(!contains(list(a.call("GET", "/api/matches", null, 200)), "studentId", bid), "Pending pairs are removed from suggestions");
        Map<String, Object> incoming = find(list(b.call("GET", "/api/connections?view=incoming", null, 200)), "connectionId", ab);
        check(!incoming.containsKey("socialLinks"), "Pending requests do not expose shared handles");
        a.call("POST", "/api/connections/" + ab + "/respond", Json.map("accept", true), 403);
        c.call("POST", "/api/connections/" + ab + "/respond", Json.map("accept", true), 403);
        c.call("POST", "/api/connections/" + ab + "/block", Json.map(), 403);
        Map<String, Object> reverse = object(b.call("POST", "/api/connections", Json.map("toStudentId", aid, "interestId", 1), 200));
        check("CONNECTED".equals(reverse.get("result")) && number(reverse.get("connectionId")) == ab,
                "Reverse pending request accepts the same pair");
        assertState(ab, "ACCEPTED", "accepted_connection");
        check(count("SELECT COUNT(*) AS n FROM `connection` WHERE student_low_id=? AND student_high_id=?", Math.min(aid, bid), Math.max(aid, bid)) == 1,
                "Opposite directions retain one canonical pair");
        check("ALREADY_CONNECTED".equals(object(a.call("POST", "/api/connections", Json.map("toStudentId", bid), 200)).get("result")),
                "Accepted pair cannot duplicate");
        a.call("GET", "/api/connections?view=invalid", null, 400);
        group("Request validation, ownership, duplicates and mutual acceptance");

        Map<String, Object> accepted = find(list(b.call("GET", "/api/connections?view=accepted", null, 200)), "connectionId", ab);
        check(list(accepted.get("socialLinks")).size() == 1, "Accepted connection sees an explicitly shared handle");
        a.call("PUT", "/api/profile/social-links/" + link, Json.map("shared", false), 200);
        accepted = find(list(b.call("GET", "/api/connections?view=accepted", null, 200)), "connectionId", ab);
        check(list(accepted.get("socialLinks")).isEmpty(), "Unsharing immediately removes a handle from API results");
        check(list(a.call("GET", "/api/profile/social-links", null, 200)).size() == 1, "Owner keeps their private handle");
        group("Accepted-only handle access and revocation");

        int ac = number(object(a.call("POST", "/api/connections", Json.map("toStudentId", cid, "interestId", 1), 200)).get("connectionId"));
        c.call("POST", "/api/connections/" + ac + "/respond", Json.map("accept", false), 200);
        assertState(ac, "CLOSED", "closed_request");
        check("DECLINED".equals(value("SELECT outcome AS v FROM closed_request WHERE connection_id=?", ac)), "Decline records its outcome");
        check(contains(list(a.call("GET", "/api/matches", null, 200)), "studentId", cid), "Closed pair may be suggested again");
        check(number(object(c.call("POST", "/api/connections", Json.map("toStudentId", aid, "interestId", 1), 200)).get("connectionId")) == ac,
                "Reopening preserves pair identity and reverses direction");
        assertState(ac, "PENDING", "pending_request");
        Database.update("UPDATE pending_request SET expires_at=UTC_TIMESTAMP()-INTERVAL 1 MINUTE WHERE connection_id=?", ac);
        a.call("GET", "/api/connections?view=incoming", null, 200);
        assertState(ac, "CLOSED", "closed_request");
        check("EXPIRED".equals(value("SELECT outcome AS v FROM closed_request WHERE connection_id=?", ac)), "Read path expires overdue requests without scheduler");
        a.call("POST", "/api/connections", Json.map("toStudentId", cid), 200);
        c.call("POST", "/api/connections/" + ac + "/respond", Json.map("accept", true), 200);
        assertState(ac, "ACCEPTED", "accepted_connection");
        a.call("POST", "/api/connections/" + ac + "/block", Json.map(), 200);
        assertState(ac, "BLOCKED", "block");
        c.call("POST", "/api/connections", Json.map("toStudentId", aid), 403);
        check(!contains(list(c.call("GET", "/api/connections?view=accepted", null, 200)), "connectionId", ac), "Blocked pair is absent from accepted connections");
        group("Decline, same-row reopen, expiry, explicit acceptance and blocking");

        // This pair never selected a common interest: optional context is still valid.
        int bd = number(object(b.call("POST", "/api/connections", Json.map("toStudentId", did), 200)).get("connectionId"));
        d.call("POST", "/api/connections/" + bd + "/block", Json.map(), 200);
        assertState(bd, "BLOCKED", "block");
        check(number(value("SELECT blocked_by AS v FROM `block` WHERE connection_id=?", bd)) == did, "Pending receiver can block and is recorded");
        group("Pending blocking and optional connection context");

        check(a.raw("PUT", "/api/profile", Json.stringify(profile("Rejected", 2)), false, null, "application/json").statusCode() == 403,
                "Write requests require the custom header");
        check(a.raw("PUT", "/api/profile", Json.stringify(profile("Rejected", 2)), true, "https://attacker.invalid", "application/json").statusCode() == 403,
                "Foreign origins cannot write");
        check(a.raw("PUT", "/api/profile", "{}", true, null, "text/plain").statusCode() == 415, "JSON content type is required");
        check(a.raw("PUT", "/api/profile", "{broken", true, null, "application/json").statusCode() == 400, "Malformed JSON is rejected");
        check(a.raw("PUT", "/api/profile", "{\"fullName\":\"A\",\"fullName\":\"B\"}", true, null, "application/json").statusCode() == 400,
                "Duplicate JSON keys are rejected");
        check(a.raw("PUT", "/api/profile", " ".repeat(65537), true, null, "application/json").statusCode() == 413, "Oversized JSON is rejected");
        check("Alpha edited".equals(object(a.call("GET", "/api/profile", null, 200)).get("fullName")), "Rejected writes do not change a profile");
        group("CSRF/origin, JSON type, malformed data and size limits");

        a.call("POST", "/api/auth/logout", Json.map(), 204);
        a.call("GET", "/api/session", null, 401);
        check(number(object(b.call("GET", "/api/session", null, 200)).get("studentId")) == bid, "Logging out one account leaves other sessions intact");
        check(count("SELECT COUNT(*) AS n FROM account") == 4, "Logout keeps account records");
        check(count("SELECT COUNT(*) AS n FROM `connection`") == 3, "Lifecycle changes reuse pair records");
        group("Logout, session isolation and final persistence invariants");
    }

    private static int register(Client client, String name, String domain) throws Exception {
        int id = number(object(client.call("POST", "/api/auth/register", Json.map("email", name + "@" + domain, "password", PASSWORD), 201)).get("accountId"));
        check(id > 0, "Registration returns a generated account ID");
        Map<String, Object> login = object(client.call("POST", "/api/auth/login", Json.map("email", name.toUpperCase(java.util.Locale.ROOT) + "@" + domain, "password", PASSWORD), 200));
        check(number(login.get("studentId")) == id, "Case-normalized login returns its account");
        return id;
    }

    private static Map<String, Object> profile(String name, Object year) {
        return Json.map("fullName", name, "department", "Computer Science", "yearOfStudy", year, "bio", "Testing a student profile");
    }

    private static Map<String, Object> privacy(boolean suggestible, boolean department, boolean year) {
        return Json.map("suggestible", suggestible, "showDepartment", department, "showYear", year);
    }

    private static void assertState(int id, String expected, String table) throws Exception {
        check(expected.equals(value("SELECT status AS v FROM `connection` WHERE connection_id=?", id)), "Parent status is " + expected);
        int total = 0;
        for (String subtype : new String[]{"pending_request", "accepted_connection", "closed_request", "block"}) {
            int size = count("SELECT COUNT(*) AS n FROM `" + subtype + "` WHERE connection_id=?", id);
            total += size;
            check(size == (subtype.equals(table) ? 1 : 0), "Correct subtype after " + expected + ": " + subtype);
        }
        check(total == 1, "Exactly one subtype exists after " + expected);
    }

    private static Object value(String sql, Object... args) throws Exception {
        return Database.query(sql, args).get(0).get("v");
    }

    private static int count(String sql, Object... args) throws Exception {
        return number(Database.query(sql, args).get(0).get("n"));
    }

    private static int number(Object value) { return ((Number) value).intValue(); }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        check(value instanceof Map, "Expected a JSON object");
        return (Map<String, Object>) value;
    }

    private static List<?> list(Object value) {
        check(value instanceof List, "Expected a JSON list");
        return (List<?>) value;
    }

    private static boolean contains(List<?> values, String key, int id) {
        for (Object value : values) if (number(object(value).get(key)) == id) return true;
        return false;
    }

    private static Map<String, Object> find(List<?> values, String key, int id) {
        for (Object value : values) {
            Map<String, Object> row = object(value);
            if (number(row.get(key)) == id) return row;
        }
        throw new AssertionError("Missing expected row: " + key + "=" + id);
    }

    private static String randomHex() {
        byte[] bytes = new byte[12];
        new SecureRandom().nextBytes(bytes);
        StringBuilder text = new StringBuilder();
        for (byte value : bytes) text.append(String.format("%02x", value & 255));
        return text.toString();
    }

    private static void check(boolean valid, String description) {
        assertions++;
        if (!valid) throw new AssertionError(description);
    }

    private static void group(String description) {
        groups++;
        System.out.println("PASS " + groups + ": " + description);
    }

    private static class Client {
        private final HttpClient client = HttpClient.newBuilder()
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                .connectTimeout(Duration.ofSeconds(5)).build();

        Object call(String method, String path, Map<String, Object> body, int status) throws Exception {
            HttpResponse<String> result = raw(method, path, body == null ? null : Json.stringify(body), true, null, "application/json");
            check(result.statusCode() == status, method + " " + path + " expected " + status + " but received " + result.statusCode() + ": " + result.body());
            if (status == 204 || result.body().length() == 0) return null;
            // Json.object deliberately accepts object roots only; wrapping also checks array replies.
            return Json.object("{\"value\":" + result.body() + "}").get("value");
        }

        HttpResponse<String> raw(String method, String path, String body, boolean header, String origin, String type) throws Exception {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(15));
            if (header) request.header("X-Connore-Request", "1");
            if (origin != null) request.header("Origin", origin);
            if (type != null && (method.equals("POST") || method.equals("PUT"))) request.header("Content-Type", type);
            request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }
}
