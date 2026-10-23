package connore;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/** Assignment 13: read ordinary text configuration with file handling. */
public final class Config {
    private Config() { }

    public static Properties load() throws IOException {
        Properties values = new Properties();
        values.setProperty("DB_URL", "jdbc:mysql://localhost:3306/connore?serverTimezone=UTC&characterEncoding=UTF-8");
        values.setProperty("DB_USER", "root");
        values.setProperty("DB_PASSWORD", "");
        values.setProperty("COLLEGE_DOMAIN", "sitpune.edu.in");
        values.setProperty("SERVER_HOST", "127.0.0.1");
        values.setProperty("SERVER_PORT", "8082");
        values.setProperty("SESSION_MINUTES", "30");
        File file = new File(".env");
        if (file.isFile()) {
            BufferedReader reader = null;
            try {
                reader = new BufferedReader(new FileReader(file, StandardCharsets.UTF_8));
                String line;
                int lineNumber = 0;
                while ((line = reader.readLine()) != null) {
                    lineNumber++;
                    line = line.replace("\ufeff", "").trim();
                    if (line.length() == 0 || line.startsWith("#")) continue;
                    if (line.startsWith("export ")) line = line.substring(7).trim();
                    int equals = line.indexOf('=');
                    if (equals < 1) throw new IOException("Invalid .env line " + lineNumber);
                    String key = line.substring(0, equals).trim();
                    if (values.containsKey(key)) values.setProperty(key, value(line.substring(equals + 1).trim()));
                }
            } finally {
                if (reader != null) reader.close();
            }
        }
        for (String key : values.stringPropertyNames()) {
            String override = System.getenv(key);
            if (override != null) values.setProperty(key, override);
        }
        checkNumber(values, "SERVER_PORT", 65535);
        checkNumber(values, "SESSION_MINUTES", 525600);
        if (values.getProperty("DB_URL").trim().length() == 0
                || values.getProperty("COLLEGE_DOMAIN").trim().length() == 0) {
            throw new IOException("Required configuration is empty");
        }
        return values;
    }

    private static String value(String text) throws IOException {
        if (text.length() == 0 || text.startsWith("#")) return "";
        char quote = text.charAt(0);
        if (quote != '\'' && quote != '"') {
            int comment = text.indexOf(" #");
            return (comment < 0 ? text : text.substring(0, comment)).trim();
        }
        StringBuffer result = new StringBuffer();
        for (int index = 1; index < text.length(); index++) {
            char current = text.charAt(index);
            if (current == quote) {
                String rest = text.substring(index + 1).trim();
                if (rest.length() > 0 && !rest.startsWith("#")) throw new IOException("Invalid quoted configuration value");
                return result.toString();
            }
            if (quote == '"' && current == '\\' && index + 1 < text.length()) {
                current = text.charAt(++index);
                if (current == 'n') result.append('\n');
                else if (current == 'r') result.append('\r');
                else if (current == 't') result.append('\t');
                else if (current == '"' || current == '\\') result.append(current);
                else { result.append('\\'); result.append(current); }
            } else result.append(current);
        }
        throw new IOException("Unclosed quoted configuration value");
    }

    private static void checkNumber(Properties values, String key, int maximum) throws IOException {
        try {
            int number = Integer.parseInt(values.getProperty(key));
            if (number < 1 || number > maximum) throw new NumberFormatException();
        } catch (NumberFormatException exception) {
            throw new IOException("Invalid setting: " + key);
        }
    }
}
