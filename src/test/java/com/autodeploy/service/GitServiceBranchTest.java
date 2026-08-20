package com.autodeploy.service;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import org.junit.jupiter.api.Test;

public class GitServiceBranchTest {

  @Test
  public void testGetCurrentBranchOnMacOS() {
    GitService gitService = new GitService();

    // Test with AIMS project directory
    String projectDir = System.getProperty("user.home") + "/Documents/nstc/code/bdg/AIMS";
    File repoDir = new File(projectDir);

    System.out.println("Testing branch detection for: " + repoDir.getAbsolutePath());
    System.out.println("Directory exists: " + repoDir.exists());
    System.out.println("Is directory: " + repoDir.isDirectory());
    System.out.println("Has .git: " + new File(repoDir, ".git").isDirectory());

    String branch = gitService.getCurrentBranch(repoDir);
    System.out.println("Detected branch: " + branch);

    assertNotNull(branch, "Branch should not be null on macOS");
    assertFalse(branch.isEmpty(), "Branch should not be empty");
    System.out.println("SUCCESS: Branch detection works on macOS! Detected: " + branch);
  }
}
