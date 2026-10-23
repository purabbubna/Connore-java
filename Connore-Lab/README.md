# Connore Lab

A separate Connore backend written for learning the Java concepts in the supplied
lab manual, including JDBC and excluding Swing. The original projects in
`E:\Connore` and `E:\Connore-Simple` are preserved.

This version uses **9 backend source files**, explicit types, ordinary loops,
constructors, collections, checked exceptions and prepared JDBC statements. It
keeps the existing frontend and the existing 13-table MySQL schema. Database
changes are shared with other Connore versions using the same `.env` database.

## Run

On this laptop, double-click `start-local.bat` or run:

```powershell
cd E:\Connore\versions\Connore-Lab
.\start-local.bat
```

Then open **http://localhost:8082/**. Keep the terminal open. Press Ctrl+C to
stop. It uses a separate `CONNORE_LAB_SESSION` cookie, so sessions on ports 8080
and 8081 are independent.

Requirements: JDK 17 or later, MySQL 8.0.16 or later, and MySQL Connector/J in
`lib/`. This local copy already has the driver and a private `.env` copied from
the configured project. On another laptop, copy `.env.example` to `.env` and
enter the intended database settings. Environment variables override `.env`.

The launcher compiles source before starting. On macOS/Linux, run `bash run.sh`.

**Keep an existing Connore database.** Startup does not recreate its schema,
clear accounts, synchronize the catalogue or install stored programs.

## Structure and flow

```text
Connore-Lab/
  src/main/java/connore/
    Main.java                 Startup and database check
    WebServer.java            HTTP routes, sessions and static files
    Connore.java              Accounts, profiles, interests, matching, communities
    ConnectionManager.java    Connection state changes and transactions
    Database.java             Prepared JDBC queries, inserts and updates
    Config.java               Read .env using file handling
    Passwords.java            Compatible salted PBKDF2 password hashing
    Json.java                 Browser JSON support
    AppException.java         User-defined checked exception
  dist/                       Existing browser frontend
  sql/                        Schema, stored programs, interest catalogue
  lib/                        MySQL JDBC driver
  test/                       Unit, HTTP/database and frontend checks
  Build.java                  JDK compiler entry point
  start-local.bat / run.sh     Launchers
```

```text
Browser app.js
    -> api.js sends JSON to /api
    -> WebServer checks session and selects a route
    -> Connore or ConnectionManager validates and performs the operation
    -> Database binds parameters and executes JDBC statements
    -> MySQL returns rows
    -> WebServer sends JSON
    -> Browser refreshes its account data
```

There are no separate endpoint/service/DAO layers, connection pool, record
classes, stream pipelines, lambdas, callback-based transaction helper or
scheduled maintenance executor. Each feature method keeps its validation and
SQL together. `Database` only handles repetitive JDBC resource work.

Requests are handled serially for a small teaching deployment. The database's
unique keys and transaction row locks still protect shared data when another
Connore server uses the same database.

## A student's journey

1. `Connore.register()` validates a college email, hashes the password and creates
   only an account row.
2. `Connore.login()` checks the password. `WebServer` creates a random session
   token; the browser receives an HttpOnly, SameSite cookie.
3. `Connore.saveProfile()` creates or updates the account's profile.
4. `addInterest()` and `removeInterest()` maintain selected interests.
5. `privacy()` controls suggestions and optional profile visibility. Social
   handles are private until the owner enables sharing.
6. `matches()` uses SQL to find eligible students and ordinary loops to calculate
   and sort shared interests. Community joining is a separate operation.
7. `ConnectionManager.send()`, `respond()` and `block()` change a connection and
   its subtype rows in an explicit JDBC transaction.
8. Accepted connections can see only handles their owner chose to share.
9. Logout clears the session; accounts and other records remain in MySQL.

Pending requests last 48 hours. This version processes expiry when matching or
connection operations run, rather than using a background scheduler. A reverse
pending request accepts the existing pair. Closed requests reopen with the same
connection ID; blocks have no undo operation.

## Connection to the manual

The supplied manual lists basics through user-defined exceptions in assignments
1-16. The requested JDBC extension is covered in assignments 19-23. Assignments
17-18 and 24 involve GUI/Swing work and are not used here.

| Manual topics | Examples in this project |
| --- | --- |
| Variables, methods and command-line execution | `Main`, `Build` and ordinary feature methods |
| Constructors | `Connore`, `WebServer`, `ConnectionManager`, session helper objects |
| Static methods and method overloading | `Database.query/update/insert` overloads and utility methods |
| Wrapper classes | `Integer`, `Boolean`, parsing IDs and optional study years |
| ArrayList and StringBuffer | Query results, session lists, matching loops and JSON/config text |
| Inner classes | Session/attempt helpers in `WebServer`, parser in `Json` |
| Inheritance, super and interfaces | `AppException extends Exception`, `super(code)`, `WebServer implements HttpHandler` |
| final and packages | Constants, helper classes, and the user-defined `connore` package |
| File handling | `.env`, static frontend files and build source discovery |
| try, catch, finally, throw and throws | JDBC resource closure, validation, transaction rollback |
| User-defined exceptions | `AppException` carries an HTTP status and error code |
| JDBC SELECT and modifications | `Database`, `Connore` and `ConnectionManager` |

The JDK HTTP server and password cryptography are isolated support code needed
for a working website and compatible existing accounts. They are not claimed to
be exercises before assignment 16. Not every classroom construct is forced into
the application: unused abstract classes or copy constructors would add code
without simplifying the real workflow.

## SQL files

`sql/schema.sql` creates the 13 tables for a new database only.
`sql/stored-programs.sql` contains the report's function, procedure and single
insert trigger. They are not installed automatically; skip installation when
they already exist. The website uses its direct JDBC queries. Do not run schema
setup again against a populated Connore database.

`sql/interest-catalog.tsv` preserves the compact 48-interest catalogue. This
version uses the catalogue already stored in MySQL; it does not change approvals
or import the file at startup.

## Verification

```powershell
java Build.java test
java -cp out CoreTest
java -cp "out;lib/*" IntegrationTest
node --test test/ui.test.cjs
```

`CoreTest` needs no database. `IntegrationTest` creates its own uniquely named
temporary test database, exercises the real HTTP endpoints, and drops that test
database afterward. It does not run destructive checks on the shared Connore
database. The configured database user needs create/drop privileges for that
isolated test. Frontend tests use API stubs, so they supplement the real HTTP
tests rather than replacing them.

The project uses the normal schema and database rules. Simplifying Java syntax
does not remove password hashing, prepared parameters, server-side ownership
checks, profile privacy or atomic connection state transitions.
