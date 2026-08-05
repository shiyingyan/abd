package com.autodeploy.model;

public enum LanguageType {
  JAVA(
      "SDKMAN",
      // Unix: SDKMAN
      "sdk use java %s",
      "sdk list java",
      "sdk install java %s",
      // Windows: check java -version
      "java -version",
      "java -version",
      "echo Please install JDK manually on Windows"),
  GO(
      "gvm",
      // Unix: gvm
      "gvm use %s",
      "gvm list",
      "gvm install %s",
      // Windows: go version
      "go version",
      "go version",
      "echo Please install Go manually on Windows"),
  NODE(
      "nvm",
      // Unix: nvm
      "nvm use %s",
      "nvm ls",
      "nvm install %s",
      // Windows: nvm-windows (nvm list / nvm install)
      "nvm use %s",
      "nvm list",
      "nvm install %s"),
  PYTHON(
      "uv",
      // Unix: uv
      "uv venv",
      "uv python list",
      "uv python install %s",
      // Windows: uv (cross-platform)
      "uv venv",
      "uv python list",
      "uv python install %s");

  private final String toolName;
  private final String useCommandPattern;
  private final String listCommand;
  private final String installCommandPattern;
  private final String useCommandPatternWin;
  private final String listCommandWin;
  private final String installCommandPatternWin;

  LanguageType(
      String toolName,
      String useCommandPattern,
      String listCommand,
      String installCommandPattern,
      String useCommandPatternWin,
      String listCommandWin,
      String installCommandPatternWin) {
    this.toolName = toolName;
    this.useCommandPattern = useCommandPattern;
    this.listCommand = listCommand;
    this.installCommandPattern = installCommandPattern;
    this.useCommandPatternWin = useCommandPatternWin;
    this.listCommandWin = listCommandWin;
    this.installCommandPatternWin = installCommandPatternWin;
  }

  public String getToolName() {
    return toolName;
  }

  private static boolean isWindows() {
    return System.getProperty("os.name", "").toLowerCase().contains("win");
  }

  public String getUseCommand(String version) {
    String pattern = isWindows() ? useCommandPatternWin : useCommandPattern;
    return String.format(pattern, version);
  }

  public String getListCommand() {
    return isWindows() ? listCommandWin : listCommand;
  }

  public String getInstallCommand(String version) {
    String pattern = isWindows() ? installCommandPatternWin : installCommandPattern;
    return String.format(pattern, version);
  }

  public static LanguageType fromString(String name) {
    if (name == null) return null;
    try {
      return valueOf(name.toUpperCase());
    } catch (IllegalArgumentException e) {
      return null;
    }
  }
}
