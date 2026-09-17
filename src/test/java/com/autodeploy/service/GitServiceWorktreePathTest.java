package com.autodeploy.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.autodeploy.model.ProjectConfig;
import java.io.File;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.util.UUID;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.PersonIdent;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for "~"-prefixed projectDir handling: createWorktree must expand the path via
 * {@link BuildService#expandPath} instead of passing it raw to java.io.File (which cannot resolve
 * "~"), otherwise a valid repo is reported as missing ("项目 Git 仓库不存在").
 */
public class GitServiceWorktreePathTest {

  @Test
  public void expandPathExpandsTildePrefix() {
    String home = System.getProperty("user.home");
    assertEquals(home, BuildService.expandPath("~"));
    assertEquals(home + "/Documents/repo", BuildService.expandPath("~/Documents/repo"));
    assertEquals(home + "/Documents/repo", BuildService.expandPath("  ~/Documents/repo  "));
    assertEquals("/abs/path", BuildService.expandPath("/abs/path"));
    assertEquals("/abs/path", BuildService.expandPath("  /abs/path "));
    assertNull(BuildService.expandPath(null));
    assertEquals("", BuildService.expandPath(""));
  }

  @Test
  public void createWorktreeAcceptsTildeProjectDir() throws Exception {
    String home = System.getProperty("user.home");
    File repoDir =
        new File(home, ".autodeploy-test-repo-" + UUID.randomUUID().toString().substring(0, 8));
    String worktreePath = null;
    try {
      try (Git git = Git.init().setDirectory(repoDir).call()) {
        File readme = new File(repoDir, "README.md");
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(readme.toPath()))) {
          w.println("init");
        }
        git.add().addFilepattern("README.md").call();
        git.commit()
            .setMessage("init")
            .setAuthor(new PersonIdent("test", "test@example.com"))
            .setCommitter(new PersonIdent("test", "test@example.com"))
            .call();
      }

      ProjectConfig config = new ProjectConfig();
      config.setProjectName("test-repo");
      config.setProjectDir("~/" + repoDir.getName());

      GitService gitService = new GitService();
      try {
        worktreePath = gitService.createWorktree(config, "master", "tester");
      } catch (Exception e) {
        // Later steps (fetch / git worktree add) may fail on a repo without remotes, but the
        // existence check must have passed for the tilde path.
        assertFalse(
            String.valueOf(e.getMessage()).contains("项目 Git 仓库不存在"),
            "createWorktree must expand '~' before the repo existence check: " + e.getMessage());
      }
    } finally {
      if (worktreePath != null) {
        new GitService().removeWorktree(worktreePath);
      }
      deleteRecursively(repoDir);
    }
  }

  private static void deleteRecursively(File dir) {
    File[] children = dir.listFiles();
    if (children != null) {
      for (File child : children) {
        if (child.isDirectory()) {
          deleteRecursively(child);
        } else {
          child.delete();
        }
      }
    }
    dir.delete();
  }
}
