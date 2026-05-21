package meridian.ui.http;

import com.google.protobuf.Message;
import com.google.protobuf.util.JsonFormat;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.logging.Level;
import java.util.logging.Logger;
import meridian.ui.descriptors.RpcRegistry;
import meridian.ui.descriptors.RpcRegistry.ResolvedMethod;

// HTTP gateway in front of an existing RpcRegistry. Exposes
// `POST /rpc/<service>/<method>` endpoints whose request + response
// bodies are proto3 JSON (snake_case field names, matching what the
// Meridian wasm renderer emits via `buildPopulateRequest`).
//
// This is the dev/desktop transport for a browser-side renderer
// talking to a local gRPC server: no Envoy sidecar, no gRPC-Web
// framing, no Connect-RPC runtime. Browsers POST JSON, the gateway
// translates JSON→proto→gRPC, returns proto→JSON.
//
// Streaming RPCs are out of scope here — list-style methods only.
// Anything that needs streaming should use a real gRPC-Web stack.
public final class JsonRpcGateway {

  private static final Logger LOG = Logger.getLogger(JsonRpcGateway.class.getName());

  private final HttpServer server;
  private final RpcRegistry registry;
  private final JsonFormat.Parser parser =
      JsonFormat.parser().ignoringUnknownFields();
  private final JsonFormat.Printer printer =
      JsonFormat.printer().preservingProtoFieldNames().omittingInsignificantWhitespace();

  private JsonRpcGateway(HttpServer server, RpcRegistry registry) {
    this.server = server;
    this.registry = registry;
  }

  /** Binds on the given port and registers the `/rpc/*` handler. */
  public static JsonRpcGateway start(int port, RpcRegistry registry) throws IOException {
    HttpServer http = HttpServer.create(new InetSocketAddress(port), 0);
    JsonRpcGateway gateway = new JsonRpcGateway(http, registry);
    http.createContext("/rpc/", gateway::handleRpc);
    http.start();
    LOG.info("JsonRpcGateway listening on http://localhost:" + http.getAddress().getPort()
        + "/rpc/<service>/<method>");
    return gateway;
  }

  /** Exposes the underlying HttpServer so callers can register more
   *  contexts (e.g. static file serving) on the same port. */
  public HttpServer httpServer() {
    return server;
  }

  public int port() {
    return server.getAddress().getPort();
  }

  public void stop() {
    server.stop(0);
  }

  // ---------------------------------------------------------------------------
  // Internals.
  // ---------------------------------------------------------------------------

  private void handleRpc(HttpExchange ex) throws IOException {
    try (HttpExchange exchange = ex) {
      if (!"POST".equals(exchange.getRequestMethod())) {
        sendError(exchange, 405, "POST only");
        return;
      }
      // /rpc/<service>/<method>
      String path = exchange.getRequestURI().getPath();
      if (!path.startsWith("/rpc/")) {
        sendError(exchange, 404, "expected /rpc/<service>/<method>");
        return;
      }
      String tail = path.substring("/rpc/".length());
      int slash = tail.lastIndexOf('/');
      if (slash <= 0 || slash == tail.length() - 1) {
        sendError(exchange, 400, "expected /rpc/<service>/<method>, got " + path);
        return;
      }
      String service = tail.substring(0, slash);
      String method = tail.substring(slash + 1);

      ResolvedMethod resolved = registry.resolve(service, method);
      if (resolved == null) {
        sendError(exchange, 404, "no such rpc: " + service + "/" + method);
        return;
      }

      byte[] body = exchange.getRequestBody().readAllBytes();
      String requestJson = body.length == 0 ? "{}" : new String(body, StandardCharsets.UTF_8);

      Message.Builder requestBuilder = resolved.requestPrototype.newBuilderForType();
      try {
        parser.merge(requestJson, requestBuilder);
      } catch (RuntimeException | com.google.protobuf.InvalidProtocolBufferException e) {
        sendError(exchange, 400, "bad request json: " + e.getMessage());
        return;
      }

      Message response;
      try {
        response = registry.call(resolved, requestBuilder.build());
      } catch (RuntimeException e) {
        LOG.log(Level.WARNING, "RPC dispatch failed: " + service + "/" + method, e);
        sendError(exchange, 502, "upstream failure: " + e.getMessage());
        return;
      }

      String responseJson = printer.print(response);
      byte[] payload = responseJson.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
      // The web app fetches from the same origin, so CORS is moot in
      // the canonical setup; allow * to keep `python3 -m http.server`
      // workflows alive too.
      exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
      exchange.sendResponseHeaders(200, payload.length);
      try (OutputStream out = exchange.getResponseBody()) {
        out.write(payload);
      }
    }
  }

  private static void sendError(HttpExchange exchange, int status, String message)
      throws IOException {
    byte[] body = ("{\"error\":\"" + escape(message) + "\"}").getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
    exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
    exchange.sendResponseHeaders(status, body.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(body);
    }
  }

  private static String escape(String s) {
    return s.replace("\\", "\\\\").replace("\"", "\\\"");
  }
}
