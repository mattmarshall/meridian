package meridian.ui.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

// Tiny static-file handler for serving a web app's HTML/JS/wasm
// alongside the JsonRpcGateway's /rpc/ endpoints. Resolves request
// paths under a configured root directory. Symlinks are followed
// (Bazel exposes `bazel-bin` via symlink, and the wasm bundle lives
// there during dev).
//
// Path-traversal defense: every resolved path is checked to start
// with the canonical root before being served.
public final class StaticFileHandler implements HttpHandler {
  private final Path root;

  public StaticFileHandler(Path root) {
    this.root = root.toAbsolutePath().normalize();
  }

  @Override
  public void handle(HttpExchange exchange) throws IOException {
    try (HttpExchange ex = exchange) {
      if (!"GET".equals(ex.getRequestMethod())) {
        ex.sendResponseHeaders(405, -1);
        return;
      }
      // Strip the context's prefix so a handler mounted at "/foo/"
      // serving from root /some/dir/ doesn't double-include "foo" in
      // the resolved path.
      String contextPath = ex.getHttpContext().getPath();
      String urlPath = ex.getRequestURI().getPath();
      String relative = urlPath.startsWith(contextPath)
          ? urlPath.substring(contextPath.length())
          : urlPath;
      if (relative.startsWith("/")) relative = relative.substring(1);
      if (relative.isEmpty()) relative = "index.html";
      Path requested = root.resolve(relative).normalize();
      if (!requested.startsWith(root)) {
        ex.sendResponseHeaders(403, -1);
        return;
      }
      if (!Files.exists(requested, LinkOption.NOFOLLOW_LINKS)
          && !Files.exists(requested)) {
        ex.sendResponseHeaders(404, -1);
        return;
      }
      if (Files.isDirectory(requested)) {
        ex.sendResponseHeaders(403, -1);
        return;
      }
      byte[] bytes = Files.readAllBytes(requested);
      ex.getResponseHeaders().set("Content-Type", contentType(urlPath));
      // Disable caching during dev so wasm rebuilds + edits show up.
      ex.getResponseHeaders().set("Cache-Control", "no-store");
      ex.sendResponseHeaders(200, bytes.length);
      try (OutputStream out = ex.getResponseBody()) {
        out.write(bytes);
      }
    }
  }

  private static String contentType(String urlPath) {
    int dot = urlPath.lastIndexOf('.');
    String ext = dot < 0 ? "" : urlPath.substring(dot + 1).toLowerCase();
    switch (ext) {
      case "html": return "text/html; charset=utf-8";
      case "js":   return "application/javascript; charset=utf-8";
      case "css":  return "text/css; charset=utf-8";
      case "json": return "application/json; charset=utf-8";
      case "wasm": return "application/wasm";
      case "svg":  return "image/svg+xml";
      case "png":  return "image/png";
      case "jpg":
      case "jpeg": return "image/jpeg";
      case "binpb": return "application/octet-stream";
      default:     return "application/octet-stream";
    }
  }
}
