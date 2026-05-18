package com.example.backup;

import com.ibm.jzos.ZFile;
import org.apache.log4j.Logger;
import org.apache.log4j.PropertyConfigurator;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class DatasetBackup {

    private static final Logger log = Logger.getLogger(DatasetBackup.class);

    private static final String DEFAULT_LOG4J_CONFIG = "log4j.properties";

    private DatasetBackup() {
    }

    public static void main(String[] args) {
        initLog4j();

        if (args.length < 2) {
            log.error("Usage: java -cp dataset-backup-1.0.0.jar "
                    + "com.example.backup.DatasetBackup <sourceDsn> <targetDsn>");
            log.error("Example: java -cp dataset-backup-1.0.0.jar "
                    + "com.example.backup.DatasetBackup "
                    + "BDX53.QW.FREESPCE.MEF BDX53.MEF.D260518.T130000");
            System.exit(8);
        }

        String sourceDsn = normalizeDsn(args[0]);
        String targetDsn = normalizeDsn(args[1]);

        try {
            validateDsn(sourceDsn, "sourceDsn");
            validateDsn(targetDsn, "targetDsn");
        } catch (Exception ex) {
            log.error("DSN validation failed: " + ex.getMessage(), ex);
            System.exit(8);
        }

        boolean ok = copyDatasetUsingDynalloc(sourceDsn, targetDsn);
        System.exit(ok ? 0 : 12);
    }

    private static void initLog4j() {
        Path log4jPath = Paths.get(DEFAULT_LOG4J_CONFIG);

        if (Files.exists(log4jPath)) {
            PropertyConfigurator.configure(log4jPath.toAbsolutePath().toString());
        } else {
            org.apache.log4j.BasicConfigurator.configure();
            log.warn("log4j.properties not found at " + log4jPath.toAbsolutePath()
                    + ", using basic console logging");
        }
    }

    private static boolean copyDatasetUsingDynalloc(String sourceDsn, String targetDsn) {
        String sourceDd = null;
        String targetDd = null;

        ZFile input = null;
        ZFile output = null;

        try {
            log.info("=======================================================");
            log.info("  Dynalloc Dataset Copy");
            log.info("=======================================================");
            log.info("Source DSN: " + sourceDsn);
            log.info("Target DSN: " + targetDsn);

            sourceDd = ZFile.allocDummyDDName();
            targetDd = ZFile.allocDummyDDName();

            log.info("Allocated dummy DD names: source=" + sourceDd + ", target=" + targetDd);

            /*
             * Same basic approach as IBM DynallocCopyDataset sample:
             * - allocate source as SHR
             * - allocate target NEW/CATALOG LIKE(source)
             * - open both by DD name and copy in record mode
             *
             * We use quoted DSN to avoid prefixing/qualification surprises.
             */
            log.info("BPXWDYN allocate source...");
            ZFile.bpxwdyn(
                    "alloc fi(" + sourceDd + ") "
                            + "da('" + sourceDsn + "') "
                            + "shr reuse msg(2)");
            log.info("Source allocated successfully.");

            log.info("BPXWDYN allocate target using LIKE(source)...");
            ZFile.bpxwdyn(
                    "alloc fi(" + targetDd + ") "
                            + "da('" + targetDsn + "') "
                            + "like('" + sourceDsn + "') "
                            + "reuse new catalog msg(2)");
            log.info("Target allocated successfully.");

            input = new ZFile("//DD:" + sourceDd, "rb,type=record,noseek");
            output = new ZFile("//DD:" + targetDd, "wb,type=record,noseek");

            int lrecl = input.getLrecl();

            if (lrecl <= 0) {
                throw new IllegalStateException("Invalid source LRECL: " + lrecl);
            }

            log.info("Source LRECL: " + lrecl);
            log.info("Source DSORG: " + input.getDsorg());

            byte[] buffer = new byte[lrecl];
            long recordCount = 0;
            int bytesRead;

            while ((bytesRead = input.read(buffer)) >= 0) {
                output.write(buffer, 0, bytesRead);
                recordCount++;
            }

            output.close();
            output = null;

            input.close();
            input = null;

            log.info("Copy completed successfully.");
            log.info("Records copied: " + recordCount);
            log.info("Target DSN: " + targetDsn);

            return true;

        } catch (Exception ex) {
            log.error("Dynalloc dataset copy failed.", ex);
            log.error("Source DSN: " + sourceDsn);
            log.error("Target DSN: " + targetDsn);

            closeQuietly(output);
            output = null;

            closeQuietly(input);
            input = null;

            /*
             * If target allocation partially succeeded, this may delete it.
             * If it did not exist, delete will simply log a warning.
             */
            deleteDatasetQuietly(targetDsn);

            return false;

        } finally {
            closeQuietly(output);
            closeQuietly(input);
            freeDdQuietly(sourceDd);
            freeDdQuietly(targetDd);
        }
    }

    private static String normalizeDsn(String dsn) {
        if (dsn == null) {
            return "";
        }

        String value = dsn.trim();

        if (value.startsWith("//'") && value.endsWith("'")) {
            value = value.substring(3, value.length() - 1);
        } else if (value.startsWith("'") && value.endsWith("'")) {
            value = value.substring(1, value.length() - 1);
        } else if (value.startsWith("//")) {
            value = value.substring(2);
        }

        while (value.endsWith(".")) {
            value = value.substring(0, value.length() - 1);
        }

        return value.toUpperCase();
    }

    private static void validateDsn(String dsn, String fieldName) {
        if (dsn == null || dsn.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " is empty.");
        }

        if (dsn.length() > 44) {
            throw new IllegalArgumentException(
                    fieldName + " exceeds 44 characters. DSN=" + dsn);
        }

        String[] qualifiers = dsn.split("\\.");

        for (String qualifier : qualifiers) {
            validateQualifier(qualifier, fieldName, dsn);
        }
    }

    private static void validateQualifier(String qualifier, String fieldName, String fullDsn) {
        if (qualifier == null || qualifier.isEmpty()) {
            throw new IllegalArgumentException(
                    fieldName + " contains empty qualifier. DSN=" + fullDsn);
        }

        if (qualifier.length() > 8) {
            throw new IllegalArgumentException(
                    fieldName + " qualifier exceeds 8 characters. Qualifier=" + qualifier + ", DSN=" + fullDsn);
        }

        char first = qualifier.charAt(0);

        if (!isValidFirstDsnCharacter(first)) {
            throw new IllegalArgumentException(
                    fieldName + " qualifier has invalid first character. Qualifier=" + qualifier + ", DSN=" + fullDsn);
        }

        for (int i = 1; i < qualifier.length(); i++) {
            char ch = qualifier.charAt(i);

            if (!isValidDsnCharacter(ch)) {
                throw new IllegalArgumentException(
                        fieldName + " qualifier contains invalid character '" + ch + "'. "
                                + "Qualifier=" + qualifier + ", DSN=" + fullDsn);
            }
        }
    }

    private static boolean isValidFirstDsnCharacter(char ch) {
        return Character.isLetter(ch) || ch == '@' || ch == '#' || ch == '$';
    }

    private static boolean isValidDsnCharacter(char ch) {
        return Character.isLetterOrDigit(ch) || ch == '@' || ch == '#' || ch == '$';
    }

    private static void closeQuietly(ZFile file) {
        if (file != null) {
            try {
                file.close();
            } catch (Exception ex) {
                log.warn("Failed to close ZFile", ex);
            }
        }
    }

    private static void freeDdQuietly(String ddName) {
        if (ddName != null && !ddName.isEmpty()) {
            try {
                ZFile.bpxwdyn("free fi(" + ddName + ") msg(2)");
            } catch (Exception ex) {
                log.warn("Failed to free DD name: " + ddName, ex);
            }
        }
    }

    private static void deleteDatasetQuietly(String dsn) {
        if (dsn == null || dsn.trim().isEmpty()) {
            return;
        }

        String deleteDd = null;

        try {
            deleteDd = ZFile.allocDummyDDName();

            ZFile.bpxwdyn(
                    "alloc fi(" + deleteDd + ") "
                            + "da('" + dsn + "') "
                            + "old msg(2)");

            ZFile.bpxwdyn(
                    "free fi(" + deleteDd + ") delete msg(2)");

            deleteDd = null;

            log.debug("Deleted target dataset after failure: " + dsn);

        } catch (Exception ex) {
            log.warn("Failed to delete target dataset after failure: " + dsn, ex);
        } finally {
            freeDdQuietly(deleteDd);
        }
    }
}