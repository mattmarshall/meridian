package meridian.ui.descriptors;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.TextFormat;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import meridian.ui.v1.PanelBundle;

// Reads a `meridian.ui.v1.PanelBundle` from disk. Dispatches on file
// extension so the same loader handles two scenarios:
//
//   `*.textproto` — source-authored bundle, parsed via protobuf's
//                   TextFormat. This is the dev path: the renderer
//                   watches the source file directly, no Bazel step
//                   between save and reload.
//   `*.binpb`     — wire-encoded bundle from
//                   //bazel:panel_bundle.bzl. This is the prod /
//                   shipped path; binary keeps every renderer on a
//                   reflection-free format (the Rust + TS renderers
//                   don't have a stock textproto parser).
//
// Renderers call `BundleLoader.parse(path)` at startup and re-parse
// on every reload event from BundleWatcher.
public final class BundleLoader {
  private BundleLoader() {}

  public static PanelBundle parse(Path path) throws IOException {
    String name = path.getFileName().toString();
    if (name.endsWith(".textproto")) {
      return parseTextproto(path);
    }
    if (name.endsWith(".binpb")) {
      return parseBinpb(path);
    }
    throw new IOException(
        "Unsupported PanelBundle file: " + path + " (expected .textproto or .binpb)");
  }

  private static PanelBundle parseTextproto(Path path) throws IOException {
    String content = Files.readString(path, StandardCharsets.UTF_8);
    PanelBundle.Builder builder = PanelBundle.newBuilder();
    try {
      TextFormat.merge(content, builder);
    } catch (TextFormat.ParseException e) {
      throw new IOException(
          "Failed to parse PanelBundle textproto " + path + ": " + e.getMessage(), e);
    }
    return builder.build();
  }

  private static PanelBundle parseBinpb(Path path) throws IOException {
    byte[] bytes = Files.readAllBytes(path);
    try {
      return PanelBundle.parseFrom(bytes);
    } catch (InvalidProtocolBufferException e) {
      throw new IOException(
          "Failed to parse PanelBundle binpb " + path + " (" + bytes.length + " bytes)", e);
    }
  }
}
