import java.io.File;
import java.util.ArrayList;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

/** Compile with the JDK; no Maven, Gradle, IDE, streams or lambdas. */
public class Build {
    private static void collect(File folder, ArrayList<String> files) {
        File[] children = folder.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (child.isDirectory()) collect(child, files);
            else if (child.getName().endsWith(".java")) files.add(child.getPath());
        }
    }

    public static void main(String[] args) {
        new File("out").mkdirs();
        ArrayList<String> files = new ArrayList<String>();
        files.add("-encoding"); files.add("UTF-8");
        files.add("--release"); files.add("17");
        files.add("-d"); files.add("out");
        collect(new File("src/main/java"), files);
        if (args.length > 0 && args[0].equals("test")) collect(new File("test"), files);
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IllegalStateException("Install a JDK, not only a JRE.");
        int status = compiler.run(null, null, null, files.toArray(new String[files.size()]));
        if (status != 0) System.exit(status);
        System.out.println("Connore Lab compiled successfully.");
    }
}
