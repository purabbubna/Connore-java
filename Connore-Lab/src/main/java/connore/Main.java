package connore;

import java.awt.Desktop;
import java.net.URI;
import java.util.Properties;

/** Start here: read settings, check MySQL, then start the web adapter. */
public class Main {
    public static void main(String[] args) throws Exception {
        Properties settings = Config.load();
        Database.configure(settings);
        Class.forName("com.mysql.cj.jdbc.Driver");
        Database.query("SELECT account_id FROM account LIMIT 1");

        Connore features = new Connore(settings.getProperty("COLLEGE_DOMAIN"));
        ConnectionManager connections = new ConnectionManager(features);
        WebServer server = new WebServer(settings, features, connections);
        server.start();
        String url = "http://localhost:" + server.port() + "/";
        System.out.println("Connore Lab is running at " + url);
        System.out.println("Uses the configured MySQL database. Press Ctrl+C to stop.");
        if (args.length > 0 && args[0].equals("--open-browser")) {
            try {
                if (Desktop.isDesktopSupported()) Desktop.getDesktop().browse(new URI(url));
            } catch (Exception exception) {
                System.out.println("Open the URL above in your browser.");
            }
        }
    }
}
