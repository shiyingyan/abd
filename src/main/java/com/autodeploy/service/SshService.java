package com.autodeploy.service;

import java.io.ByteArrayOutputStream;
import java.util.concurrent.TimeUnit;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.scp.client.ScpClient;
import org.apache.sshd.scp.client.ScpClientCreator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class SshService {

  private static final Logger log = LoggerFactory.getLogger(SshService.class);

  private final SshClient client = SshClient.setUpDefaultClient();

  {
    client.start();
  }

  /** Execute a remote command and return output. */
  public String executeCommand(String host, int port, String user, String password, String command)
      throws Exception {
    log.info("SSH executing on {}@{}:{} - {}", user, host, port, command);
    try (ClientSession session =
        client.connect(user, host, port).verify(30, TimeUnit.SECONDS).getSession()) {
      session.addPasswordIdentity(password);
      session.auth().verify(30, TimeUnit.SECONDS);

      ByteArrayOutputStream out = new ByteArrayOutputStream();
      ByteArrayOutputStream err = new ByteArrayOutputStream();
      session.executeRemoteCommand(command, out, err, null);
      String result = out.toString();
      String errorOutput = err.toString();
      if (errorOutput != null && !errorOutput.isEmpty()) {
        result = result + "\n" + errorOutput;
      }
      return result;
    }
  }

  /** Upload a file to a remote server via SCP. */
  public void uploadFile(
      String host, int port, String user, String password, String localPath, String remotePath)
      throws Exception {
    log.info("SCP upload {} to {}@{}:{}", localPath, user, host, remotePath);
    try (ClientSession session =
        client.connect(user, host, port).verify(30, TimeUnit.SECONDS).getSession()) {
      session.addPasswordIdentity(password);
      session.auth().verify(30, TimeUnit.SECONDS);

      ScpClient scp = ScpClientCreator.instance().createScpClient(session);
      scp.upload(localPath, remotePath, ScpClient.Option.Recursive);
    }
  }

  /** Download a file from a remote server via SCP. */
  public void downloadFile(
      String host, int port, String user, String password, String remotePath, String localPath)
      throws Exception {
    log.info("SCP download {} from {}@{}:{}", remotePath, user, host, localPath);
    try (ClientSession session =
        client.connect(user, host, port).verify(30, TimeUnit.SECONDS).getSession()) {
      session.addPasswordIdentity(password);
      session.auth().verify(30, TimeUnit.SECONDS);

      ScpClient scp = ScpClientCreator.instance().createScpClient(session);
      scp.download(remotePath, localPath, ScpClient.Option.Recursive);
    }
  }

  /** Test SSH connection to a server. */
  public boolean testConnection(String host, int port, String user, String password) {
    try (ClientSession session =
        client.connect(user, host, port).verify(30, TimeUnit.SECONDS).getSession()) {
      session.addPasswordIdentity(password);
      session.auth().verify(30, TimeUnit.SECONDS);
      return true;
    } catch (Exception e) {
      log.warn("SSH connection test failed to {}@{}:{} - {}", user, host, port, e.getMessage());
      return false;
    }
  }
}
