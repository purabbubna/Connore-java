# JDBC driver

Place the MySQL Connector/J JAR in this directory. The application loads it with
`-cp "out;lib/*"` on Windows or `-cp 'out:lib/*'` on macOS/Linux.

Get the platform-independent package from the [official MySQL Connector/J download](https://dev.mysql.com/downloads/connector/j/).
Extract `mysql-connector-j-*.jar` here. JARs are excluded from Git. No servlet API
or Tomcat installation is needed.
