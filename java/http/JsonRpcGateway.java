package meridian.ui.http;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
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
  private final JsonFormat.Parser parser;
  private final JsonFormat.Printer printer;

  private JsonRpcGateway(
      HttpServer server,
      RpcRegistry registry,
      JsonFormat.TypeRegistry typeRegistry) {
    this.server = server;
    this.registry = registry;
    this.parser = JsonFormat.parser()
        .usingTypeRegistry(typeRegistry)
        .ignoringUnknownFields();
    this.printer = JsonFormat.printer()
        .usingTypeRegistry(typeRegistry)
        .preservingProtoFieldNames()
        .omittingInsignificantWhitespace();
  }

  /** Binds on the given port and registers the `/rpc/*` handler. */
  public static JsonRpcGateway start(int port, RpcRegistry registry) throws IOException {
    return start(port, registry, JsonFormat.TypeRegistry.getEmptyTypeRegistry());
  }

  /**
   * Same as {@link #start(int, RpcRegistry)}, plus a TypeRegistry the
   * JsonFormat parser+printer use to expand `google.protobuf.Any`
   * fields inline (instead of emitting/parsing raw `value` bytes).
   * Pass descriptors for every Message that can appear inside an Any
   * in your service's request/response graph — e.g. for LROs, the
   * metadata + response types of every registered start RPC.
   */
  public static JsonRpcGateway start(
      int port,
      RpcRegistry registry,
      JsonFormat.TypeRegistry typeRegistry) throws IOException {
    HttpServer http = HttpServer.create(new InetSocketAddress(port), 0);
    JsonRpcGateway gateway = new JsonRpcGateway(http, registry, typeRegistry);
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
      // Browsers building requests through the wasm RequestBuilder
      // produce well-known types in their "structural" object form
      // (Duration as `{seconds, nanos}`); JsonFormat expects the
      // canonical string form. Walk the JSON against the request
      // descriptor and convert in place before parsing.
      try {
        JsonElement parsed = JsonParser.parseString(requestJson);
        if (parsed.isJsonObject()) {
          coerceWellKnownTypes(parsed.getAsJsonObject(),
              requestBuilder.getDescriptorForType());
          requestJson = parsed.toString();
        }
      } catch (RuntimeException e) {
        sendError(exchange, 400, "request body is not valid JSON: " + e.getMessage());
        return;
      }
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

  // ---------------------------------------------------------------------------
  // Well-known type coercion for request bodies (browser → proto).
  // Walks the JsonObject in parallel with the descriptor; for any
  // field whose target type is one of the supported well-known types
  // (Duration today), converts the structural object form to the
  // canonical string form JsonFormat's parser expects.
  // ---------------------------------------------------------------------------

  private static final String DURATION_TYPE = "google.protobuf.Duration";

  private static void coerceWellKnownTypes(JsonObject obj, Descriptor descriptor) {
    for (FieldDescriptor field : descriptor.getFields()) {
      JsonElement el = obj.get(field.getName());
      if (el == null || el.isJsonNull()) continue;
      if (field.getJavaType() != FieldDescriptor.JavaType.MESSAGE) continue;
      Descriptor child = field.getMessageType();
      if (DURATION_TYPE.equals(child.getFullName()) && el.isJsonObject()) {
        obj.add(field.getName(), durationObjectToString(el.getAsJsonObject()));
        continue;
      }
      if (field.isRepeated() && el.isJsonArray()) {
        for (JsonElement item : el.getAsJsonArray()) {
          if (item.isJsonObject()) coerceWellKnownTypes(item.getAsJsonObject(), child);
        }
      } else if (el.isJsonObject()) {
        coerceWellKnownTypes(el.getAsJsonObject(), child);
      }
    }
  }

  private static JsonElement durationObjectToString(JsonObject duration) {
    long seconds = duration.has("seconds") && !duration.get("seconds").isJsonNull()
        ? duration.get("seconds").getAsLong() : 0L;
    int nanos = duration.has("nanos") && !duration.get("nanos").isJsonNull()
        ? duration.get("nanos").getAsInt() : 0;
    String s = nanos == 0
        ? seconds + "s"
        : String.format("%d.%09ds", seconds, nanos);
    return new JsonPrimitive(s);
  }
}
