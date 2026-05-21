package meridian.ui.descriptors;

import com.google.protobuf.InvalidProtocolBufferException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import meridian.ui.v1.PanelBundle;

// Reads a wire-encoded `meridian.ui.v1.PanelBundle` from disk. The
// .binpb is produced by the //bazel:panel_bundle.bzl rule (textproto
// authored, protoc-encoded at build time). Renderers call
// `BundleLoader.parse(path)` at startup and re-parse on every reload
// event.
//
// Why parse from bytes rather than load the .textproto directly:
// textproto needs runtime descriptors + Java's TextFormat parser
// would still work, but parsing .binpb keeps every renderer (Java,
// Rust, TS) on the exact same wire format — the textproto stays a
// build-time authoring artifact only.
public final class BundleLoader {
  private BundleLoader() {}

  public static PanelBundle parse(Path binpb) throws IOException {
    byte[] bytes = Files.readAllBytes(binpb);
    try {
      return PanelBundle.parseFrom(bytes);
    } catch (InvalidProtocolBufferException e) {
      throw new IOException(
          "Failed to parse PanelBundle from " + binpb + " (" + bytes.length + " bytes)", e);
    }
  }
}
