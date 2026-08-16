package com.autodeploy.service;

import com.autodeploy.model.LanguageType;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class LanguageRuntimeService {

  private static final Logger log = LoggerFactory.getLogger(LanguageRuntimeService.class);

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
      home + "/.gvm/scripts/gvm-default",
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

  /** List installed versions for a language. Uses OS-appropriate shell and commands. */
  public List<String> listInstalledVersions(LanguageType language) {
    List<String> versions = new ArrayList<>();
    try {
      String command = language.getListCommand();
      log.debug("Listing {} versions with command: {}", language, command);

      ProcessBuilder pb = createShellProcess(command);
      pb.redirectErrorStream(true);
      Process process = pb.start();

      // Regex to extract version numbers like 1.2.3, v18.16.0, 11.0.19+7, etc.
      java.util.regex.Pattern versionPattern =
          java.util.regex.Pattern.compile("v?(\\d+\\.\\d+(?:\\.\\d+)?(?:[+\\-]\\w+)?)");

      try (BufferedReader reader =
          new BufferedReader(new InputStreamReader(process.getInputStream(), "UTF-8"))) {
        String line;
        while ((line = reader.readLine()) != null) {
          java.util.regex.Matcher matcher = versionPattern.matcher(line);
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
      log.warn("Failed to list versions for {}: {}", language, e.getMessage());
    }
    return versions;
  }

  /** Generate the version switch command prefix for a build. */
  public String getSwitchCommand(LanguageType language, String version) {
    if (language == null || version == null || version.trim().isEmpty()) return "";
    return language.getUseCommand(version);
  }

  /**
   * Generate the full build command with version switching prepended. Uses OS-appropriate syntax
   * (export vs set for PATH).
   */
  public String buildFullCommand(
      LanguageType language, String version, String buildCommand, String customInstallDir) {
    StringBuilder sb = new StringBuilder();
    if (language != null && version != null && !version.trim().isEmpty()) {
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
