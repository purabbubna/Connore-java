package connore;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Map;

import static connore.AppException.require;

/** Connection rules and SQL together, using explicit JDBC transactions. */
public class ConnectionManager {
    private final Connore features;
    private static final String SELECT = "SELECT c.*,p.expires_at FROM `connection` c "
        + "LEFT JOIN pending_request p ON p.connection_id=c.connection_id ";

    public ConnectionManager(Connore features) {
        this.features = features;
    }

    /** Create, repeat, reverse-accept or reopen one student pair. */
    public Map<String, Object> send(int from, int to, Integer interest) throws SQLException, AppException {
        require(from != to, 403, "REFUSED");
        features.requireProfile(from);
        require(features.hasProfile(to), 403, "REFUSED");
        require(interest == null || features.interestExists(interest.intValue()), 400, "INVALID_INTEREST");

        for (int attempt = 0; attempt < 4; attempt++) {
            Connection database = null;
            try {
                database = Database.open();
                database.setAutoCommit(false);
                ArrayList<Map<String, Object>> rows = Database.query(database,
                    SELECT + "WHERE c.student_low_id=? AND c.student_high_id=? FOR UPDATE",
                    Math.min(from, to), Math.max(from, to));
                Map<String, Object> row = rows.isEmpty() ? null : rows.get(0);
                if (row != null && pendingExpired(row)) {
                    changeState(database, row, "CLOSED", "EXPIRED", 0);
                }

                int id;
                String result;
                if (row == null) {
                    id = Database.insert(database,
                        "INSERT INTO `connection`(requester_id,receiver_id,context_interest_id,status) "
                        + "VALUES(?,?,?,'PENDING')", from, to, interest);
                    Database.update(database,
                        "INSERT INTO pending_request(connection_id,expires_at) "
                        + "VALUES(?,DATE_ADD(UTC_TIMESTAMP(),INTERVAL 48 HOUR))", id);
                    result = "PENDING";
                } else {
                    id = Database.number(row.get("connection_id"));
                    String status = Database.text(row.get("status"));
                    if (status.equals("BLOCKED")) throw new AppException(403, "REFUSED");
                    if (status.equals("ACCEPTED")) {
                        result = "ALREADY_CONNECTED";
                    } else if (status.equals("PENDING")) {
                        if (Database.number(row.get("requester_id")) == from) result = "ALREADY_PENDING";
                        else {
                            changeState(database, row, "ACCEPTED", "", 0);
                            result = "CONNECTED";
                        }
                    } else {
                        Database.update(database,
                            "UPDATE `connection` SET requester_id=?,receiver_id=?,context_interest_id=?,"
                            + "created_at=UTC_TIMESTAMP() WHERE connection_id=?", from, to, interest, id);
                        changeState(database, row, "PENDING", "", 0);
                        result = "PENDING";
                    }
                }
                database.commit();
                return Json.map("result", result, "connectionId", id);
            } catch (SQLException exception) {
                rollback(database);
                // Another server may have inserted this pair at the same time.
                boolean retry = exception.getErrorCode() == 1062 || exception.getErrorCode() == 1213
                    || exception.getErrorCode() == 1205 || "40001".equals(exception.getSQLState());
                if (!retry || attempt == 3) throw exception;
            } catch (AppException exception) {
                rollback(database);
                throw exception;
            } catch (RuntimeException exception) {
                rollback(database);
                throw exception;
            } finally {
                Database.close(database);
            }
        }
        throw new SQLException("Connection request retries exhausted");
    }

    /** Only the receiver can accept or decline a pending request. */
    public void respond(int id, int student, boolean accept) throws SQLException, AppException {
        Connection database = Database.open();
        boolean expired = false;
        try {
            database.setAutoCommit(false);
            Map<String, Object> row = find(database, id);
            require(row != null && "PENDING".equals(row.get("status"))
                && Database.number(row.get("receiver_id")) == student, 403, "FORBIDDEN");
            if (pendingExpired(row)) {
                changeState(database, row, "CLOSED", "EXPIRED", 0);
                expired = true;
            } else if (accept) changeState(database, row, "ACCEPTED", "", 0);
            else changeState(database, row, "CLOSED", "DECLINED", 0);
            database.commit();
        } catch (SQLException exception) {
            rollback(database); throw exception;
        } catch (AppException exception) {
            rollback(database); throw exception;
        } catch (RuntimeException exception) {
            rollback(database); throw exception;
        } finally {
            Database.close(database);
        }
        // Keep the expiry change committed even though the requested reply is refused.
        require(!expired, 403, "FORBIDDEN");
    }

    /** Either participant may block an active pending or accepted pair. */
    public void block(int id, int student) throws SQLException, AppException {
        Connection database = Database.open();
        boolean expired = false;
        try {
            database.setAutoCommit(false);
            Map<String, Object> row = find(database, id);
            require(row != null && (Database.number(row.get("requester_id")) == student
                || Database.number(row.get("receiver_id")) == student), 403, "FORBIDDEN");
            String status = Database.text(row.get("status"));
            require(status.equals("PENDING") || status.equals("ACCEPTED"), 403, "FORBIDDEN");
            if (pendingExpired(row)) {
                changeState(database, row, "CLOSED", "EXPIRED", 0);
                expired = true;
            } else changeState(database, row, "BLOCKED", "", student);
            database.commit();
        } catch (SQLException exception) {
            rollback(database); throw exception;
        } catch (AppException exception) {
            rollback(database); throw exception;
        } catch (RuntimeException exception) {
            rollback(database); throw exception;
        } finally {
            Database.close(database);
        }
        require(!expired, 403, "FORBIDDEN");
    }

    /** Expire overdue requests on demand; no timer or additional thread is needed. */
    public void expire() throws SQLException {
        ArrayList<Map<String, Object>> overdue = Database.query(
            "SELECT connection_id FROM pending_request WHERE expires_at<=UTC_TIMESTAMP() ORDER BY connection_id");
        for (Map<String, Object> candidate : overdue) {
            Connection database = Database.open();
            try {
                database.setAutoCommit(false);
                Map<String, Object> row = find(database, Database.number(candidate.get("connection_id")));
                if (row != null && pendingExpired(row)) changeState(database, row, "CLOSED", "EXPIRED", 0);
                database.commit();
            } catch (SQLException exception) {
                rollback(database); throw exception;
            } catch (RuntimeException exception) {
                rollback(database); throw exception;
            } finally {
                Database.close(database);
            }
        }
    }

    public ArrayList<Map<String, Object>> list(int student, String view) throws SQLException, AppException {
        require(view.equals("incoming") || view.equals("outgoing") || view.equals("accepted"), 400, "INVALID_VIEW");
        expire();
        String sql = SELECT;
        ArrayList<Map<String, Object>> rows;
        if (view.equals("accepted")) {
            rows = Database.query(sql + "WHERE (c.requester_id=? OR c.receiver_id=?) "
                + "AND c.status='ACCEPTED' ORDER BY c.created_at DESC", student, student);
        } else if (view.equals("incoming")) {
            rows = Database.query(sql + "WHERE c.receiver_id=? AND c.status='PENDING' ORDER BY c.created_at DESC", student);
        } else {
            rows = Database.query(sql + "WHERE c.requester_id=? AND c.status IN ('PENDING','CLOSED') ORDER BY c.created_at DESC", student);
        }
        ArrayList<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> row : rows) {
            int from = Database.number(row.get("requester_id"));
            int other = from == student ? Database.number(row.get("receiver_id")) : from;
            Map<String, Object> person = features.profile(other, false);
            person.put("connectionId", Database.number(row.get("connection_id")));
            person.put("status", row.get("status"));
            person.put("contextInterestId", row.get("context_interest_id"));
            person.put("expiresAt", Database.iso(row.get("expires_at")));
            if (view.equals("accepted")) {
                ArrayList<Map<String, Object>> handles = new ArrayList<Map<String, Object>>();
                for (Map<String, Object> link : features.socialLinks(other, true)) {
                    handles.add(Json.map("platform", link.get("platform"), "handle", link.get("handle")));
                }
                person.put("socialLinks", handles);
            }
            result.add(person);
        }
        return result;
    }

    private Map<String, Object> find(Connection database, int id) throws SQLException {
        ArrayList<Map<String, Object>> rows = Database.query(database,
            SELECT + "WHERE c.connection_id=? FOR UPDATE", id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private boolean pendingExpired(Map<String, Object> row) {
        return "PENDING".equals(row.get("status")) && row.get("expires_at") != null
            && !Instant.parse(Database.iso(row.get("expires_at"))).isAfter(Instant.now());
    }

    /** Remove the old subtype, add one new subtype, and update the parent. */
    private void changeState(Connection database, Map<String, Object> row, String state,
            String outcome, int blockedBy) throws SQLException {
        int id = Database.number(row.get("connection_id"));
        String[] tables = {"pending_request", "accepted_connection", "closed_request", "block"};
        for (String table : tables) {
            Database.update(database, "DELETE FROM `" + table + "` WHERE connection_id=?", id);
        }
        if (state.equals("PENDING")) {
            Database.update(database, "INSERT INTO pending_request(connection_id,expires_at) "
                + "VALUES(?,DATE_ADD(UTC_TIMESTAMP(),INTERVAL 48 HOUR))", id);
        } else if (state.equals("ACCEPTED")) {
            Database.update(database, "INSERT INTO accepted_connection(connection_id,connected_at) VALUES(?,UTC_TIMESTAMP())", id);
        } else if (state.equals("CLOSED")) {
            Database.update(database, "INSERT INTO closed_request(connection_id,outcome,closed_at) VALUES(?,?,UTC_TIMESTAMP())", id, outcome);
        } else if (state.equals("BLOCKED")) {
            Database.update(database, "INSERT INTO `block`(connection_id,blocked_by,blocked_at) VALUES(?,?,UTC_TIMESTAMP())", id, blockedBy);
        } else throw new SQLException("Unknown connection state");
        Database.update(database, "UPDATE `connection` SET status=? WHERE connection_id=?", state, id);
        row.put("status", state);
    }

    private void rollback(Connection database) {
        if (database != null) {
            try { database.rollback(); } catch (SQLException ignored) { }
        }
    }
}
