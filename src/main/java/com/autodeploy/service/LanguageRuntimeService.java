package com.autodeploy.service;

import com.autodeploy.model.LanguageType;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class LanguageRuntimeService {

  private static final Logger log = LoggerFactory.getLogger(LanguageRuntimeService.class);

  @Autowired private WindowsRuntimeScanner windowsScanner;

  private final Map<LanguageType, String> systemVersionCache = new ConcurrentHashMap<>();

  /** Cached result of shell detection: "zsh" or "bash". */
  private static volatile String detectedShell;

  private static boolean isWindows() {
    return System.getProperty("os.name", "").toLowerCase().contains("win");
  }

  private static boolean isMac() {
    return System.getProperty("os.name", "").toLowerCase().contains("mac");
  }

  /**
   * Detect the user's login shell on macOS/Linux. Returns "zsh" or "bash". Falls back to "bash" if
   * undetectable.
   */
  public static String detectShell() {
    if (detectedShell != null) return detectedShell;
    String shell = System.getenv("SHELL");
    if (shell != null && shell.endsWith("/zsh")) {
      detectedShell = "zsh";
    } else if (shell != null && shell.endsWith("/bash")) {
      detectedShell = "bash";
    } else {
      // Fallback: check which shell binary exists
      if (new java.io.File("/bin/zsh").canExecute()) {
        detectedShell = "zsh";
      } else {
        detectedShell = "bash";
      }
    }
    log.info("Detected shell: {} (SHELL={})", detectedShell, shell);
    return detectedShell;
  }

  /**
   * Build a ProcessBuilder with OS-appropriate shell command. Windows: PowerShell with UTF-8 output
   * encoding macOS/Linux: use detected shell (zsh or bash) with login mode
   */
  private ProcessBuilder createShellProcess(String command) {
    if (isWindows()) {
      return new ProcessBuilder(
          "powershell",
          "-Command",
          "[Console]::OutputEncoding = [System.Text.Encoding]::UTF8; " + command);
    } else {
      String shell = detectShell();
      String fullCommand = buildShellPreamble(shell) + " && " + command;
      ProcessBuilder pb = new ProcessBuilder(shell, "-l", "-c", fullCommand);
      appendMacPaths(pb);
      return pb;
    }
  }

  /**
   * Build a shell preamble that sources common SDK version managers (nvm, sdkman, gvm, etc.) so
   * their shell functions are available. Sources shell-specific rc files and SDK init scripts.
   */
  public static String buildShellPreamble(String shell) {
    String home = System.getProperty("user.home", "");
    StringBuilder sb = new StringBuilder();
    sb.append("export LANG=en_US.UTF-8");

    // Source shell-specific rc/profile to load user's environment
    if ("zsh".equals(shell)) {
      sb.append(" && { [ -f \"")
          .append(home)
          .append("/.zshrc\" ] && source \"")
          .append(home)
          .append("/.zshrc\"; } || true");
      sb.append(" && { [ -f \"")
          .append(home)
          .append("/.zprofile\" ] && source \"")
          .append(home)
          .append("/.zprofile\"; } || true");
    } else {
      sb.append(" && { [ -f \"")
          .append(home)
          .append("/.bash_profile\" ] && source \"")
          .append(home)
          .append("/.bash_profile\"; } || true");
      sb.append(" && { [ -f \"")
          .append(home)
          .append("/.bashrc\" ] && source \"")
          .append(home)
          .append("/.bashrc\"; } || true");
    }

    // Source SDK version manager init scripts (works in both zsh and bash)
    String[] initScripts = {
      home + "/.nvm/nvm.sh",
      home + "/.sdkman/bin/sdkman-init.sh",
      home + "/.gvm/scripts/gvm",
      home + "/.gvm/scripts/gvm_default",
    };
    for (String script : initScripts) {
      sb.append(" && { [ -f \"")
          .append(script)
          .append("\" ] && source \"")
          .append(script)
          .append("\"; } || true");
    }
    return sb.toString();
  }

  /** Overload: build preamble using detected shell. */
  public static String buildShellPreamble() {
    return buildShellPreamble(detectShell());
  }

  /**
   * Append common macOS/Homebrew/SDK paths to the process PATH so that tools installed via
   * Homebrew, SDKMAN, nvm, gvm, etc. are discoverable even when running in a non-interactive login
   * shell.
   */
  public static void appendMacPaths(ProcessBuilder pb) {
    if (!isMac()) return;
    String home = System.getProperty("user.home", "");
    Map<String, String> env = pb.environment();
    String path = env.getOrDefault("PATH", "");
    StringBuilder extra = new StringBuilder();
    String[] dirs = {
      "/opt/homebrew/bin",
      "/opt/homebrew/sbin",
      "/usr/local/bin",
      "/usr/local/sbin",
      home + "/.sdkman/candidates/java/current/bin",
      home + "/.sdkman/candidates/maven/current/bin",
      home + "/.sdkman/candidates/gradle/current/bin",
      home + "/.nvm/versions/node",
      home + "/.gvm/gos/current/bin",
      home + "/.cargo/bin",
      home + "/.local/bin",
      home + "/.uv/bin",
    };
    for (String d : dirs) {
      if (!d.isEmpty() && new java.io.File(d).isDirectory() && !path.contains(d)) {
        extra.append(":").append(d);
      }
    }
    if (isMac()) {
      String nvmDefaultDir = home + "/.nvm/versions/node";
      java.io.File nvmDir = new java.io.File(nvmDefaultDir);
      if (nvmDir.isDirectory()) {
        java.io.File[] versions = nvmDir.listFiles();
        if (versions != null) {
          for (java.io.File v : versions) {
            String binDir = v.getAbsolutePath() + "/bin";
            if (!path.contains(binDir) && !extra.toString().contains(binDir)) {
              extra.append(":").append(binDir);
            }
          }
        }
      }
    }
    if (extra.length() > 0) {
      env.put("PATH", path + extra.toString());
    }
  }

  /**
   * Detect the system-installed runtime version for Java or Go by running the version command
   * directly (e.g. java -version, go version). Returns null if not found or not applicable.
   */
  private String detectSystemVersion(LanguageType language) {
    if (isWindows()) return null;
    if (language != LanguageType.JAVA && language != LanguageType.GO) return null;

    String cached = systemVersionCache.get(language);
    if (cached != null) return cached;

    try {
      String cmd = (language == LanguageType.JAVA) ? "java -version" : "go version";
      ProcessBuilder pb = createShellProcess(cmd);
      pb.redirectErrorStream(true);
      Process process = pb.start();

      String version = null;
      try (BufferedReader reader =
          new BufferedReader(new InputStreamReader(process.getInputStream(), "UTF-8"))) {
        String line;
        while ((line = reader.readLine()) != null) {
          if (language == LanguageType.JAVA) {
            Matcher m = Pattern.compile("(\\d+\\.\\d+(?:\\.\\d+)?)").matcher(line);
            if (m.find()) {
              version = m.group(1);
              break;
            }
          } else {
            Matcher m = Pattern.compile("go(\\d+\\.\\d+(?:\\.\\d+)?)").matcher(line);
            if (m.find()) {
              version = m.group(1);
              break;
            }
          }
        }
      }
      process.waitFor();

      if (version != null) {
        systemVersionCache.put(language, version);
        log.info("Detected system {} version: {}", language, version);
      }
      return version;
    } catch (Exception e) {
      log.debug("Failed to detect system {} version: {}", language, e.getMessage());
      return null;
    }
  }

  /** Get the cached system version for a language, or detect it if not yet cached. */
  public String getSystemVersion(LanguageType language) {
    String cached = systemVersionCache.get(language);
    if (cached != null) return cached;
    return detectSystemVersion(language);
  }

  /** Clear the system version cache, forcing re-detection on next access. */
  public void clearSystemVersionCache() {
    systemVersionCache.clear();
  }

  /** List versions via the language's version manager tool (sdkman, gvm, nvm, uv). */
  private List<String> listToolManagedVersions(LanguageType language) {
    List<String> versions = new ArrayList<>();
    try {
      String command = language.getListCommand();
      log.debug("Listing {} tool-managed versions with command: {}", language, command);

      ProcessBuilder pb = createShellProcess(command);
      pb.redirectErrorStream(true);
      Process process = pb.start();

      Pattern versionPattern = Pattern.compile("v?(\\d+\\.\\d+(?:\\.\\d+)?(?:[+\\-]\\w+)?)");

      try (BufferedReader reader =
          new BufferedReader(new InputStreamReader(process.getInputStream(), "UTF-8"))) {
        String line;
        while ((line = reader.readLine()) != null) {
          // For SDKMAN (Java) and GVM (Go), only match lines marked as installed
          if (language == LanguageType.JAVA) {
            // SDKMAN output: installed versions have "installed" in Status column or ">>>" in Use
            // column
            if (!line.contains("installed") && !line.contains(">>>")) {
              continue;
            }
          } else if (language == LanguageType.GO) {
            // GVM output: installed versions have "installed" suffix
            if (!line.contains("installed")) {
              continue;
            }
          }

          Matcher matcher = versionPattern.matcher(line);
          if (matcher.find()) {
            String version = matcher.group();
            if (!versions.contains(version)) {
              versions.add(version);
            }
          }
        }
      }
      int exitCode = process.waitFor();
      if (exitCode != 0) {
        log.warn("Command '{}' exited with code {} for {}", command, exitCode, language);
      }
    } catch (Exception e) {
      log.warn("Failed to list tool-managed versions for {}: {}", language, e.getMessage());
    }
    return versions;
  }

  /** Check if a language version is installed. */
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
   * List installed versions for a language. On Mac/Linux for Java/Go, first checks the system
   * runtime, then checks version manager (sdkman/gvm) managed runtimes.
   */
  public List<String> listInstalledVersions(LanguageType language) {
    if (isWindows() && (language == LanguageType.JAVA || language == LanguageType.GO)) {
      Map<String, String> installations =
          (language == LanguageType.JAVA)
              ? windowsScanner.getJavaInstallations()
              : windowsScanner.getGoInstallations();
      return new ArrayList<>(installations.keySet());
    }

    List<String> versions = new ArrayList<>();

    if (!isWindows() && (language == LanguageType.JAVA || language == LanguageType.GO)) {
      String systemVersion = detectSystemVersion(language);
      if (systemVersion != null) {
        versions.add(systemVersion);
      }
    }

    versions.addAll(listToolManagedVersions(language));

    return versions;
  }

  /**
   * Returns a map of version string to installation directory path. On Windows for Java/Go, uses
   * directory scanning. On Unix or for other languages, falls back to command-based detection
   * (paths will be null).
   */
  public Map<String, String> listInstalledVersionsWithPaths(LanguageType language) {
    if (isWindows() && (language == LanguageType.JAVA || language == LanguageType.GO)) {
      return (language == LanguageType.JAVA)
          ? windowsScanner.getJavaInstallations()
          : windowsScanner.getGoInstallations();
    }
    Map<String, String> result = new LinkedHashMap<>();

    if (!isWindows() && (language == LanguageType.JAVA || language == LanguageType.GO)) {
      String systemVersion = getSystemVersion(language);
      if (systemVersion != null) {
        String binary = (language == LanguageType.JAVA) ? "java" : "go";
        try {
          ProcessBuilder pb = createShellProcess("which " + binary);
          pb.redirectErrorStream(true);
          Process process = pb.start();
          try (BufferedReader reader =
              new BufferedReader(new InputStreamReader(process.getInputStream(), "UTF-8"))) {
            String path = reader.readLine();
            if (path != null && !path.isEmpty()) {
              result.put(systemVersion, path.trim());
            }
          }
          process.waitFor();
        } catch (Exception e) {
          result.put(systemVersion, null);
        }
      }
    }

    for (String v : listToolManagedVersions(language)) {
      if (!result.containsKey(v)) {
        result.put(v, null);
      }
    }
    return result;
  }

  /** Generate the version switch command prefix for a build. */
  public String getSwitchCommand(LanguageType language, String version) {
    if (language == null || version == null || version.trim().isEmpty()) return "";
    return language.getUseCommand(version);
  }

  /**
   * Generate the full build command with version switching prepended. Uses OS-appropriate syntax
   * (export vs set for PATH). Skips sdkman/gvm switch command when the version matches the system
   * runtime.
   */
  public String buildFullCommand(
      LanguageType language, String version, String buildCommand, String customInstallDir) {
    StringBuilder sb = new StringBuilder();
    if (language != null && version != null && !version.trim().isEmpty()) {
      String effectiveDir = customInstallDir;

      if (isWindows()
          && (effectiveDir == null || effectiveDir.trim().isEmpty())
          && (language == LanguageType.JAVA || language == LanguageType.GO)) {
        Map<String, String> installations =
            (language == LanguageType.JAVA)
                ? windowsScanner.getJavaInstallations()
                : windowsScanner.getGoInstallations();
        effectiveDir = installations.get(version);
      }

      if (effectiveDir != null && !effectiveDir.trim().isEmpty()) {
        if (isWindows()) {
          sb.append("set PATH=").append(effectiveDir).append("\\bin;%PATH% && ");
        } else {
          sb.append("export PATH=").append(effectiveDir).append("/bin:$PATH && ");
        }
      }

      if (!isWindows()) {
        String systemVersion = getSystemVersion(language);
        if (!version.equals(systemVersion)) {
          sb.append(language.getUseCommand(version)).append(" && ");
        }
      }
    }
    sb.append(buildCommand);
    return sb.toString();
  }

  /**
   * Install a language version. Returns the Process for log streaming. Uses OS-appropriate shell.
   */
  public Process installVersion(LanguageType language, String version) throws Exception {
    String cmd = language.getInstallCommand(version);
    log.info("Installing {} version {} with command: {}", language, version, cmd);
    ProcessBuilder pb = createShellProcess(cmd);
    pb.redirectErrorStream(true);
    return pb.start();
  }
}
