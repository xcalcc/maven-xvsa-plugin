package io.xc5;

import org.apache.maven.plugin.MojoExecutionException;

public class XvsaPluginException extends MojoExecutionException {
  public XvsaPluginException(Object source, String shortMessage, String longMessage) {
    super(source, shortMessage, longMessage);
  }

  public XvsaPluginException(String message, Exception cause) {
    super(message, cause);
  }

  public XvsaPluginException(String message, Throwable cause) {
    super(message, cause);
  }

  public XvsaPluginException(String message) {
    super(message);
  }
}
