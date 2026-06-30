import java.io.*;
public class TestPMD {
    public static void main(String[] args) throws Exception {
        String mvnCmd = System.getProperty("os.name").toLowerCase().contains("win") ? "mvn.cmd" : "mvn";
        ProcessBuilder pb = new ProcessBuilder(mvnCmd, "org.apache.maven.plugins:maven-pmd-plugin:3.21.0:pmd", "-Dpmd.failOnViolation=false");
        pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
        pb.redirectError(ProcessBuilder.Redirect.INHERIT);
        Process p = pb.start();
        int exit = p.waitFor();
        System.out.println("Exit code: " + exit);
    }
}