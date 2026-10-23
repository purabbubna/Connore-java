import connore.AppException;
import connore.Json;
import connore.Passwords;
import java.util.ArrayList;
import java.util.Map;

/** Small non-database checks for the support code used by every API route. */
public class CoreTest {
    private static int checks;

    private static void check(boolean condition, String name) {
        if (!condition) throw new AssertionError(name);
        checks++;
    }

    private static void invalid(String json) throws Exception {
        try {
            Json.object(json);
            throw new AssertionError("Accepted malformed JSON");
        } catch (AppException exception) {
            check(exception.status == 400, "Invalid JSON uses a 400 error");
        }
    }

    public static void main(String[] args) throws Exception {
        String text = "Name with quotes \" and a newline\n and a tab\t";
        ArrayList<Integer> ids = new ArrayList<Integer>();
        ids.add(Integer.valueOf(12)); ids.add(Integer.valueOf(25));
        Map<String, Object> source = Json.map("name", text, "ids", ids, "shared", false, "year", null);
        Map<String, Object> parsed = Json.object(Json.stringify(source));
        check(text.equals(parsed.get("name")), "Escaped JSON strings round trip");
        check(parsed.get("ids") instanceof ArrayList, "Arrays remain lists");
        check(Boolean.FALSE.equals(parsed.get("shared")), "Booleans stay booleans");
        check(parsed.containsKey("year") && parsed.get("year") == null, "NULL remains null");
        check(((Number) Json.object("{\"id\":42}").get("id")).intValue() == 42, "Integer parsing");
        check(((Number) Json.object("{\"n\":1.5}").get("n")).doubleValue() == 1.5, "Decimal parsing");
        String[] failures = {"[]", "null", "{", "{\"x\":1,}", "{\"x\":1,\"x\":2}",
            "{\"n\":01}", "{\"n\":+1}", "{\"n\":1e999}", "{\"x\":true} extra", "{\"x\":\"\\q\"}"};
        for (String failure : failures) invalid(failure);
        String first = Passwords.hash("test-password-123");
        String second = Passwords.hash("test-password-123");
        check(!first.equals(second), "Independent random password salts");
        check(Passwords.verify("test-password-123", first), "Valid password verifies");
        check(!Passwords.verify("wrong-password", first), "Wrong password rejected");
        check(!Passwords.verify("test-password-123", "broken"), "Malformed stored hash rejected");
        try {
            AppException.require(false, 409, "PROFILE_REQUIRED");
            throw new AssertionError("Missing expected exception");
        } catch (AppException exception) {
            check(exception.status == 409 && exception.code.equals("PROFILE_REQUIRED"), "Custom exception carries status/code");
        }
        System.out.println("Core checks passed: " + checks);
    }
}
