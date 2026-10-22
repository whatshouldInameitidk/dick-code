package top.niunaijun.blackbox.app;

import android.content.Context;
import android.os.Build;
import android.os.Process;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.utils.Slog;

/**
 * Loads a SHA-256-pinned native module only while its configured guest is binding.
 * The module directory belongs to the host, so it is reachable from every virtual process.
 */
public final class NativeLoader {
    private static final String TAG = "NativeLoader";
    private static final String DIRECTORY = "authorized-native-modules";
    private static final String CONFIG = "module.properties";
    private static final String MODULE = "module.so";
    private static final Set<String> ATTEMPTED = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private static final ThreadLocal<String> LAST_FAILURE = new ThreadLocal<>();

    public enum PrepareResult { NOT_CONFIGURED, READY, FAILED }

    private NativeLoader() {
    }

    /**
     * Persists the sole module that may be loaded for {@code packageName}.
     * Call from the host process before launch, never from a guest Application.
     */
    public static void configure(Context context, String packageName, String httpsUrl, String sha256) throws IOException {
        configure(context, packageName, packageName, httpsUrl, sha256);
    }

    /** Persists the module allow-list for exactly one guest process. */
    public static void configure(Context context, String packageName, String processName,
                                 String httpsUrl, String sha256) throws IOException {
        if (packageName == null || !packageName.matches("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")) {
            throw new IllegalArgumentException("Invalid package name");
        }
        if (processName == null || !processName.matches("[A-Za-z0-9_.:]+")) {
            throw new IllegalArgumentException("Invalid process name");
        }
        if (httpsUrl == null || !httpsUrl.startsWith("https://")) {
            throw new IllegalArgumentException("Only HTTPS module URLs are accepted");
        }
        if (!isSha256(sha256)) {
            throw new IllegalArgumentException("A lowercase SHA-256 digest is required");
        }

        File directory = moduleDirectory(context, packageName);
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException("Could not create " + directory);
        }
        Properties properties = new Properties();
        properties.setProperty("package", packageName);
        properties.setProperty("process", processName);
        properties.setProperty("url", httpsUrl);
        properties.setProperty("sha256", sha256);
        writeProperties(new File(directory, CONFIG), properties);
    }

    /** Downloads and verifies the configured module for each launch. This must run off the UI thread. */
    public static synchronized PrepareResult prepare(Context context, String packageName) {
        LAST_FAILURE.remove();
        try {
            Properties properties = readProperties(moduleDirectory(context, packageName));
            if (properties == null) {
                return PrepareResult.NOT_CONFIGURED;
            }
            if (!packageName.equals(properties.getProperty("package"))) {
                String failure = "Module configuration does not match " + packageName;
                LAST_FAILURE.set(failure);
                Slog.e(TAG, failure);
                return PrepareResult.FAILED;
            }
            File module = new File(moduleDirectory(context, packageName), MODULE);
            download(properties.getProperty("url"), module, properties.getProperty("sha256"));
            Slog.i(TAG, "Verified module download for " + packageName + " at " + module);
            return PrepareResult.READY;
        } catch (Throwable error) {
            LAST_FAILURE.set(error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
            Slog.e(TAG, "Module preparation failed for " + packageName, error);
            return PrepareResult.FAILED;
        }
    }

    /** Returns the failure from this thread's most recent {@link #prepare} call. */
    public static String getLastFailureMessage() {
        String failure = LAST_FAILURE.get();
        return failure == null ? "Unknown module preparation failure" : failure;
    }

    /** Called only from BActivityThread after the guest LoadedApk is available. */
    static void loadForGuest(String packageName, String processName, ClassLoader guestClassLoader) {
        Slog.i(TAG, "Guest load check package=" + packageName + " process=" + processName
                + " pid=" + Process.myPid());
        if (!ATTEMPTED.add(packageName + '\u0000' + processName)) {
            Slog.d(TAG, "Already handled " + packageName + " in pid=" + Process.myPid());
            return;
        }
        try {
            Context hostContext = BlackBoxCore.getContext();
            Properties properties = readProperties(moduleDirectory(hostContext, packageName));
            if (properties == null) {
                Slog.i(TAG, "No module configuration for guest=" + packageName);
                return;
            }
            String configuredProcess = properties.getProperty("process", packageName);
            if (!packageName.equals(properties.getProperty("package"))
                    || !processName.equals(configuredProcess)) {
                Slog.i(TAG, "Guest module configuration did not match package/process");
                return;
            }
            File module = new File(moduleDirectory(hostContext, packageName), MODULE);
            if (!module.isFile() || !properties.getProperty("sha256").equals(sha256(module))) {
                throw new IOException("Module is missing or its SHA-256 changed");
            }
            if (Build.SUPPORTED_64_BIT_ABIS.length == 0 || !isElf64(module)) {
                throw new UnsatisfiedLinkError("BlackBox64 requires an ELF64 module");
            }
            Slog.i(TAG, "Loading " + module + " for guest=" + packageName
                    + " process=" + processName + " pid=" + Process.myPid()
                    + " guestLoader=" + guestClassLoader);
            System.load(module.getCanonicalPath());
            Slog.i(TAG, "JNI_OnLoad completed for guest=" + packageName + " pid=" + Process.myPid());
        } catch (Throwable error) {
            // A test module must not prevent the guest's normal Application lifecycle.
            Slog.e(TAG, "Native module was not loaded for " + packageName, error);
        }
    }

    private static File moduleDirectory(Context context, String packageName) {
        File dir = new File(new File(context.getNoBackupFilesDir(), DIRECTORY), packageName);
        if (new File(dir, CONFIG).isFile()) {
            return dir;
        }
        // Fallback: Check explicit host data directory if IO redirection mapped getNoBackupFilesDir()
        File hostDir = new File("/data/user/0/" + context.getPackageName() + "/no_backup/" + DIRECTORY, packageName);
        if (new File(hostDir, CONFIG).isFile()) {
            return hostDir;
        }
        return dir;
    }

    private static Properties readProperties(File directory) throws IOException {
        File config = new File(directory, CONFIG);
        if (!config.isFile()) {
            return null;
        }
        Properties properties = new Properties();
        try (FileInputStream input = new FileInputStream(config)) {
            properties.load(input);
        }
        String digest = properties.getProperty("sha256");
        if (!isSha256(digest) || properties.getProperty("url") == null
                || properties.getProperty("process", properties.getProperty("package")) == null) {
            throw new IOException("Invalid module configuration");
        }
        return properties;
    }

    private static void writeProperties(File config, Properties properties) throws IOException {
        File temporary = new File(config.getParentFile(), config.getName() + ".part");
        try (FileOutputStream output = new FileOutputStream(temporary)) {
            properties.store(output, "Authorized virtual-app native module");
            output.getFD().sync();
        }
        if (!temporary.renameTo(config)) {
            throw new IOException("Could not replace " + config);
        }
    }

    private static void download(String urlText, File destination, String expectedSha256) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(urlText).openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(30_000);
        connection.setRequestMethod("GET");
        if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
            throw new IOException("Module server returned HTTP " + connection.getResponseCode());
        }
        File temporary = new File(destination.getParentFile(), destination.getName() + ".part");
        try (BufferedInputStream input = new BufferedInputStream(connection.getInputStream());
             FileOutputStream output = new FileOutputStream(temporary)) {
            byte[] buffer = new byte[16 * 1024];
            for (int read; (read = input.read(buffer)) != -1;) {
                output.write(buffer, 0, read);
            }
            output.getFD().sync();
        } finally {
            connection.disconnect();
        }
        if (!expectedSha256.equals(sha256(temporary))) {
            temporary.delete();
            throw new IOException("Downloaded module SHA-256 mismatch");
        }
        if (destination.exists() && !destination.delete()) {
            temporary.delete();
            throw new IOException("Could not replace existing module");
        }
        if (!temporary.renameTo(destination)) {
            throw new IOException("Could not install downloaded module");
        }
    }

    private static boolean isElf64(File file) throws IOException {
        byte[] header = new byte[5];
        try (FileInputStream input = new FileInputStream(file)) {
            if (input.read(header) != header.length) return false;
        }
        return header[0] == 0x7f && header[1] == 'E' && header[2] == 'L'
                && header[3] == 'F' && header[4] == 2;
    }

    private static boolean isSha256(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    private static String sha256(File file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[16 * 1024];
            try (FileInputStream input = new FileInputStream(file)) {
                for (int read; (read = input.read(buffer)) != -1;) digest.update(buffer, 0, read);
            }
            StringBuilder hex = new StringBuilder(64);
            for (byte value : digest.digest()) hex.append(String.format("%02x", value));
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
