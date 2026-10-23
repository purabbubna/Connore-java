package connore;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import static connore.AppException.require;

/**
 * Connore's ordinary account, profile and community operations.
 *
 * Each public method follows the same simple pattern:
 * validate input, run a prepared SQL statement, and return ordinary Java data.
 * Connection-request transitions are in ConnectionManager because they need
 * several SQL statements in one transaction.
 */
public class Connore {
    private final String collegeDomain;
    private final String dummyPasswordHash;

    public Connore(String collegeDomain) {
        this.collegeDomain = collegeDomain.toLowerCase(Locale.ROOT);
        this.dummyPasswordHash = Passwords.hash("missing-account-password-check");
    }

    // ---------- Registration and login ----------

    public int register(String rawEmail, String password)
            throws SQLException, AppException {
        String email = normalEmail(rawEmail);
        require(email.length() <= 100
                && email.matches("^[^\\s@]+@" + Pattern.quote(collegeDomain) + "$"),
                400, "NOT_COLLEGE_EMAIL");
        require(password.length() >= 8 && password.length() <= 200,
                400, "PASSWORD_TOO_SHORT");
        require(Database.query("SELECT account_id FROM account WHERE college_email=?", email).isEmpty(),
                409, "EMAIL_EXISTS");

        try {
            return Database.insert(
                    "INSERT INTO account(college_email,password_hash) VALUES(?,?)",
                    email, Passwords.hash(password));
        } catch (SQLException exception) {
            // The unique key also protects simultaneous registrations.
            if (exception.getErrorCode() == 1062) {
                throw new AppException(409, "EMAIL_EXISTS");
            }
            throw exception;
        }
    }

    public int login(String rawEmail, String password)
            throws SQLException, AppException {
        require(rawEmail.length() <= 100 && password.length() <= 200,
                401, "BAD_CREDENTIALS");
        ArrayList<Map<String, Object>> rows = Database.query(
                "SELECT account_id,password_hash FROM account WHERE college_email=?",
                normalEmail(rawEmail));
        String hash = dummyPasswordHash;
        if (!rows.isEmpty()) {
            hash = Database.text(rows.get(0).get("password_hash"));
        }
        boolean valid = Passwords.verify(password, hash);
        require(!rows.isEmpty() && valid, 401, "BAD_CREDENTIALS");
        return Database.number(rows.get(0).get("account_id"));
    }

    private String normalEmail(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }

    // ---------- Profile and visibility ----------

    public boolean hasProfile(int student) throws SQLException {
        return !Database.query(
                "SELECT student_id FROM student_profile WHERE student_id=?", student).isEmpty();
    }

    public void requireProfile(int student) throws SQLException, AppException {
        require(hasProfile(student), 409, "PROFILE_REQUIRED");
    }

    /** Only the owner receives private values and privacy settings. */
    public Map<String, Object> profile(int student, boolean owner)
            throws SQLException, AppException {
        ArrayList<Map<String, Object>> rows = Database.query(
                "SELECT * FROM student_profile WHERE student_id=?", student);
        require(!rows.isEmpty(), 404, "PROFILE_NOT_FOUND");
        Map<String, Object> row = rows.get(0);
        Map<String, Object> result = Json.map(
                "studentId", student,
                "fullName", Database.text(row.get("full_name")),
                "bio", Database.text(row.get("bio")));
        if (owner || Database.flag(row.get("show_department"))) {
            result.put("department", Database.text(row.get("department")));
        }
        if (owner || Database.flag(row.get("show_year"))) {
            result.put("yearOfStudy", row.get("year_of_study"));
        }
        if (owner) {
            result.put("suggestible", Database.flag(row.get("suggestible")));
            result.put("showDepartment", Database.flag(row.get("show_department")));
            result.put("showYear", Database.flag(row.get("show_year")));
        }
        result.put("interests", selectedInterests(student));
        return result;
    }

    public Map<String, Object> saveProfile(int student, String name, String department,
            Integer year, String bio) throws SQLException, AppException {
        require(name.trim().length() > 0 && name.length() <= 100, 400, "INVALID_NAME");
        require(department.length() <= 60, 400, "INVALID_DEPARTMENT");
        require(year == null || (year.intValue() >= 1 && year.intValue() <= 6), 400, "INVALID_YEAR");
        require(bio.length() <= 500, 400, "BIO_TOO_LONG");
        Database.update(
                "INSERT INTO student_profile(student_id,full_name,department,year_of_study,bio) "
                + "VALUES(?,?,?,?,?) ON DUPLICATE KEY UPDATE "
                + "full_name=?,department=?,year_of_study=?,bio=?",
                student, name.trim(), department, year, bio,
                name.trim(), department, year, bio);
        return profile(student, true);
    }

    public void privacy(int student, boolean suggestible, boolean showDepartment, boolean showYear)
            throws SQLException, AppException {
        requireProfile(student);
        Database.update(
                "UPDATE student_profile SET suggestible=?,show_department=?,show_year=? "
                + "WHERE student_id=?",
                suggestible, showDepartment, showYear, student);
    }

    // ---------- Interests ----------

    /** Build category lists using loops instead of streams or nested callbacks. */
    public ArrayList<Map<String, Object>> interests() throws SQLException {
        ArrayList<Map<String, Object>> rows = Database.query(
                "SELECT c.category_id,c.name AS category_name,i.interest_id,i.name AS interest_name "
                + "FROM category c JOIN interest i ON i.category_id=c.category_id "
                + "WHERE i.is_approved=TRUE ORDER BY c.name,i.name");
        ArrayList<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        int previousId = -1;
        ArrayList<Map<String, Object>> group = null;
        for (Map<String, Object> row : rows) {
            int category = Database.number(row.get("category_id"));
            if (category != previousId) {
                group = new ArrayList<Map<String, Object>>();
                result.add(Json.map("categoryId", category,
                        "name", Database.text(row.get("category_name")), "interests", group));
                previousId = category;
            }
            group.add(Json.map("interestId", Database.number(row.get("interest_id")),
                    "name", Database.text(row.get("interest_name"))));
        }
        return result;
    }

    private ArrayList<Map<String, Object>> selectedInterests(int student) throws SQLException {
        ArrayList<Map<String, Object>> rows = Database.query(
                "SELECT i.interest_id,i.name FROM interest i "
                + "JOIN student_interest s ON s.interest_id=i.interest_id "
                + "WHERE s.student_id=? AND i.is_approved=TRUE ORDER BY i.interest_id", student);
        ArrayList<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> row : rows) {
            result.add(Json.map("interestId", Database.number(row.get("interest_id")),
                    "name", Database.text(row.get("name"))));
        }
        return result;
    }

    public ArrayList<Integer> interestIds(int student) throws SQLException, AppException {
        requireProfile(student);
        ArrayList<Integer> result = new ArrayList<Integer>();
        for (Map<String, Object> row : selectedInterests(student)) {
            result.add(Integer.valueOf(Database.number(row.get("interestId"))));
        }
        return result;
    }

    public boolean interestExists(int interest) throws SQLException {
        return !Database.query(
                "SELECT interest_id FROM interest WHERE interest_id=? AND is_approved=TRUE",
                interest).isEmpty();
    }

    public void addInterest(int student, int interest) throws SQLException, AppException {
        requireProfile(student);
        require(interestExists(interest), 404, "INTEREST_NOT_FOUND");
        Database.update("INSERT INTO student_interest(student_id,interest_id) VALUES(?,?) "
                + "ON DUPLICATE KEY UPDATE interest_id=VALUES(interest_id)", student, interest);
    }

    public void removeInterest(int student, int interest) throws SQLException, AppException {
        requireProfile(student);
        Database.update("DELETE FROM student_interest WHERE student_id=? AND interest_id=?",
                student, interest);
    }

    // ---------- Social handles ----------

    public ArrayList<Map<String, Object>> socialLinks(int student, boolean sharedOnly)
            throws SQLException, AppException {
        requireProfile(student);
        String sql = "SELECT link_id,platform,handle,shared FROM social_link WHERE student_id=?";
        if (sharedOnly) {
            sql += " AND shared=TRUE";
        }
        ArrayList<Map<String, Object>> rows = Database.query(sql + " ORDER BY link_id", student);
        ArrayList<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> row : rows) {
            result.add(Json.map("linkId", Database.number(row.get("link_id")),
                    "platform", Database.text(row.get("platform")),
                    "handle", Database.text(row.get("handle")),
                    "shared", Database.flag(row.get("shared"))));
        }
        return result;
    }

    public Map<String, Object> addSocialLink(int student, String platform, String handle)
            throws SQLException, AppException {
        requireProfile(student);
        boolean validPlatform = platform.equals("Instagram") || platform.equals("GitHub")
                || platform.equals("LinkedIn") || platform.equals("Discord") || platform.equals("Other");
        require(validPlatform && handle.trim().length() > 0 && handle.length() <= 100,
                400, "INVALID_HANDLE");
        int link = Database.insert(
                "INSERT INTO social_link(student_id,platform,handle,shared) VALUES(?,?,?,FALSE)",
                student, platform, handle.trim());
        return Json.map("linkId", link, "platform", platform, "handle", handle.trim(), "shared", false);
    }

    public void shareSocialLink(int link, int student, boolean shared)
            throws SQLException, AppException {
        requireProfile(student);
        require(!Database.query("SELECT link_id FROM social_link WHERE link_id=? AND student_id=?",
                link, student).isEmpty(), 403, "FORBIDDEN");
        Database.update("UPDATE social_link SET shared=? WHERE link_id=? AND student_id=?",
                shared, link, student);
    }

    // ---------- Suggested people ----------

    /** Filter candidates in SQL, then rank shared interests with ordinary loops. */
    public ArrayList<Map<String, Object>> matches(int student, int requestedLimit)
            throws SQLException, AppException {
        requireProfile(student);
        int limit = Math.max(1, Math.min(50, requestedLimit));
        ArrayList<Map<String, Object>> candidates = Database.query(
                "SELECT p.student_id FROM student_profile p "
                + "WHERE p.student_id<>? AND p.suggestible=TRUE "
                + "AND EXISTS (SELECT 1 FROM student_interest a "
                + "JOIN student_interest b ON a.interest_id=b.interest_id "
                + "JOIN interest i ON i.interest_id=a.interest_id AND i.is_approved=TRUE "
                + "WHERE a.student_id=? AND b.student_id=p.student_id) "
                + "AND NOT EXISTS (SELECT 1 FROM `connection` c "
                + "WHERE c.student_low_id=LEAST(?,p.student_id) "
                + "AND c.student_high_id=GREATEST(?,p.student_id) "
                + "AND c.status IN ('PENDING','ACCEPTED','BLOCKED')) "
                + "ORDER BY p.student_id", student, student, student, student);
        ArrayList<Map<String, Object>> mine = selectedInterests(student);
        ArrayList<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        ArrayList<Integer> scores = new ArrayList<Integer>();

        for (Map<String, Object> candidate : candidates) {
            int other = Database.number(candidate.get("student_id"));
            ArrayList<Map<String, Object>> theirs = selectedInterests(other);
            ArrayList<Map<String, Object>> shared = new ArrayList<Map<String, Object>>();
            StringBuffer reason = new StringBuffer("You both enjoy ");
            for (Map<String, Object> otherInterest : theirs) {
                for (Map<String, Object> myInterest : mine) {
                    if (Database.number(otherInterest.get("interestId"))
                            == Database.number(myInterest.get("interestId"))) {
                        if (!shared.isEmpty()) {
                            reason.append(", ");
                        }
                        shared.add(otherInterest);
                        reason.append(Database.text(otherInterest.get("name")));
                        break;
                    }
                }
            }
            if (shared.isEmpty()) {
                continue;
            }
            Map<String, Object> item = profile(other, false);
            item.put("sharedInterests", shared);
            item.put("reason", reason.toString());

            // Insert before the first smaller score (stable descending order).
            int position = 0;
            while (position < scores.size() && scores.get(position).intValue() >= shared.size()) {
                position++;
            }
            result.add(position, item);
            scores.add(position, Integer.valueOf(shared.size()));
            if (result.size() > limit) {
                result.remove(limit);
                scores.remove(limit);
            }
        }
        return result;
    }

    // ---------- Communities ----------

    public ArrayList<Map<String, Object>> communities(int student) throws SQLException {
        ArrayList<Map<String, Object>> rows = Database.query(
                "SELECT c.community_id AS communityId,c.interest_id AS interestId,c.description,"
                + "i.name,cat.name AS category,"
                + "(SELECT COUNT(*) FROM community_membership m WHERE m.community_id=c.community_id) AS memberCount,"
                + "EXISTS(SELECT 1 FROM community_membership m WHERE m.community_id=c.community_id AND m.student_id=?) AS joined "
                + "FROM community c JOIN interest i ON i.interest_id=c.interest_id AND i.is_approved=TRUE "
                + "JOIN category cat ON cat.category_id=i.category_id ORDER BY cat.name,i.name", student);
        for (Map<String, Object> row : rows) {
            row.put("joined", Database.flag(row.get("joined")));
            row.put("communityId", Database.number(row.get("communityId")));
            row.put("interestId", Database.number(row.get("interestId")));
            row.put("memberCount", Database.number(row.get("memberCount")));
        }
        return rows;
    }

    private boolean communityExists(int community, boolean activeOnly) throws SQLException {
        String sql = "SELECT c.community_id FROM community c";
        if (activeOnly) {
            sql += " JOIN interest i ON i.interest_id=c.interest_id AND i.is_approved=TRUE";
        }
        return !Database.query(sql + " WHERE c.community_id=?", community).isEmpty();
    }

    public void joinCommunity(int student, int community) throws SQLException, AppException {
        requireProfile(student);
        require(communityExists(community, true), 404, "COMMUNITY_NOT_FOUND");
        Database.update("INSERT INTO community_membership(student_id,community_id) VALUES(?,?) "
                + "ON DUPLICATE KEY UPDATE community_id=VALUES(community_id)", student, community);
    }

    public void leaveCommunity(int student, int community) throws SQLException, AppException {
        requireProfile(student);
        require(communityExists(community, false), 404, "COMMUNITY_NOT_FOUND");
        Database.update("DELETE FROM community_membership WHERE student_id=? AND community_id=?",
                student, community);
    }

    public ArrayList<Map<String, Object>> communityMembers(int community, int viewer)
            throws SQLException, AppException {
        require(communityExists(community, true), 404, "COMMUNITY_NOT_FOUND");
        ArrayList<Map<String, Object>> members = Database.query(
                "SELECT student_id FROM community_membership WHERE community_id=? ORDER BY joined_at,student_id",
                community);
        ArrayList<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> member : members) {
            result.add(profile(Database.number(member.get("student_id")), false));
        }
        return result;
    }
}
