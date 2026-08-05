package com.autodeploy.service;

import com.autodeploy.model.LanguageType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Service
public class LanguageRuntimeService {

    private static final Logger log = LoggerFactory.getLogger(LanguageRuntimeService.class);

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private static boolean isMac() {
        return System.getProperty("os.name", "").toLowerCase().contains("mac");
    }

    /**
     * Build a ProcessBuilder with OS-appropriate shell command.
     * Windows: PowerShell with UTF-8 output encoding
     * macOS/Linux: bash -c "command" (fallback to sh -c)
     */
    private ProcessBuilder createShellProcess(String command) {
        if (isWindows()) {
            // Use PowerShell for better Unicode support on Windows
            return new ProcessBuilder("powershell", "-Command", 
                    "[Console]::OutputEncoding = [System.Text.Encoding]::UTF8; " + command);
        } else {
            // Try bash first (available on macOS and Linux), fallback to sh
            return new ProcessBuilder("bash", "-c", command);
        }
    }

    /**
     * Check if a language version is installed.
     */
    public boolean isVersionInstalled(LanguageType language, String version) {
        if (language == null || version == null) return false;
        try {
            List<String> installed = listInstalledVersions(language);
            return installed.stream().anyMatch(v -> v.contains(version));
        } catch (Exception e) {
            log.warn("Failed to check installed versions for {} {}", language, version, e);
            return false;
        }
    }

    /**
     * List installed versions for a language.
     * Uses OS-appropriate shell and commands.
     */
    public List<String> listInstalledVersions(LanguageType language) {
        List<String> versions = new ArrayList<>();
        try {
            String command = language.getListCommand();
            log.debug("Listing {} versions with command: {}", language, command);

            ProcessBuilder pb = createShellProcess(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.trim();
                    if (!trimmed.isEmpty()) {
                        versions.add(trimmed);
                    }
                }
            }
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                log.warn("Command '{}' exited with code {} for {}", command, exitCode, language);
            }
        } catch (Exception e) {
            log.warn("Failed to list versions for {}: {}", language, e.getMessage());
        }
        return versions;
    }

    /**
     * Generate the version switch command prefix for a build.
     */
    public String getSwitchCommand(LanguageType language, String version) {
        if (language == null || version == null) return "";
        return language.getUseCommand(version);
    }

    /**
     * Generate the full build command with version switching prepended.
     * Uses OS-appropriate syntax (export vs set for PATH).
     */
    public String buildFullCommand(LanguageType language, String version, String buildCommand, String customInstallDir) {
        StringBuilder sb = new StringBuilder();
        if (language != null && version != null) {
            if (customInstallDir != null && !customInstallDir.trim().isEmpty()) {
                if (isWindows()) {
                    // Windows: set PATH=customDir;...
                    sb.append("set PATH=").append(customInstallDir).append("\\bin;%PATH% && ");
                } else {
                    // Unix: export PATH=customDir/bin:$PATH
                    sb.append("export PATH=").append(customInstallDir).append("/bin:$PATH && ");
                }
            }
            sb.append(language.getUseCommand(version)).append(isWindows() ? " && " : " && ");
        }
        sb.append(buildCommand);
        return sb.toString();
    }

    /**
     * Install a language version. Returns the Process for log streaming.
     * Uses OS-appropriate shell.
     */
    public Process installVersion(LanguageType language, String version) throws Exception {
        String cmd = language.getInstallCommand(version);
        log.info("Installing {} version {} with command: {}", language, version, cmd);
        ProcessBuilder pb = createShellProcess(cmd);
        pb.redirectErrorStream(true);
        return pb.start();
    }
}
