import com.mk15.portinspector.ReportTools;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class ReportToolsSelfTest {
    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        File dir = Files.createTempDirectory("mk15-report-test").toFile();

        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("summary.txt", ReportTools.utf8("summary=ok\n"));
        entries.put("state/dynamic.txt", ReportTools.utf8("sa=1500\n"));

        File zip = ReportTools.createZip(dir, "MK15 Test Report.zip", entries);
        require(zip.exists(), "ZIP must exist");
        require(zip.length() > 0, "ZIP must not be empty");

        boolean summaryFound = false;
        boolean stateFound = false;
        try (ZipInputStream in = new ZipInputStream(new FileInputStream(zip))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                byte[] data = in.readAllBytes();
                String text = new String(data, StandardCharsets.UTF_8);
                if ("summary.txt".equals(entry.getName())) {
                    summaryFound = text.contains("summary=ok");
                }
                if ("state/dynamic.txt".equals(entry.getName())) {
                    stateFound = text.contains("sa=1500");
                }
            }
        }

        require(summaryFound, "summary.txt must be present");
        require(stateFound, "state/dynamic.txt must be present");
        System.out.println("ALL REPORT TOOLS SELF-TESTS PASSED");
    }
}
