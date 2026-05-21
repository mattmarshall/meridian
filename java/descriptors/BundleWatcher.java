package meridian.ui.descriptors;

import com.sun.nio.file.SensitivityWatchEventModifier;
import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import meridian.ui.v1.PanelBundle;

// Watches a `.binpb` PanelBundle on disk and pushes a freshly-parsed
// bundle to its listener whenever the file changes. Pure dev-loop
// machinery: edit panels.textproto → bazel rebuilds the binpb → this
// watcher sees the new file → the renderer swaps in the new
// descriptor graph.
//
// In prod the same listener is driven by an admin `ReloadDescriptors`
// RPC instead of disk events; both code paths end at
// `listener.accept(bundle)`.
//
// Implementation note: java.nio.file's WatchService watches a *parent
// directory* and fires per-entry events. We filter to the bundle file
// only. Editors that swap-on-save (vim, IntelliJ) generate
// CREATE+DELETE+MODIFY sequences — we react to any of them as long as
// the target file currently exists and parses cleanly.
public final class BundleWatcher implements AutoCloseable {
  private static final Logger LOG = Logger.getLogger(BundleWatcher.class.getName());

  private final Path bundlePath;
  private final Consumer<PanelBundle> listener;
  private final WatchService watcher;
  private final Thread thread;
  private volatile boolean closed = false;

  public BundleWatcher(Path bundlePath, Consumer<PanelBundle> listener) throws IOException {
    // Resolve symlinks up-front. Bazel ships outputs in
    // `bazel-out/.../bin/...` and exposes them through the
    // `bazel-bin` workspace symlink — `WatchService` on macOS
    // doesn't traverse symlinks reliably, so we watch the real path.
    this.bundlePath = bundlePath.toRealPath();
    this.listener = listener;
    this.watcher = FileSystems.getDefault().newWatchService();
    Path dir = this.bundlePath.getParent();
    if (dir == null) {
      throw new IOException("Bundle path has no parent directory: " + bundlePath);
    }
    // macOS's WatchService implementation is polling-based; the
    // default sensitivity is 10s, which is unusable for a "save and
    // see" dev loop. HIGH = 2s polling. The
    // SensitivityWatchEventModifier API ships in `jdk.unsupported`
    // but is stable across OpenJDK 17 / 21.
    dir.register(
        watcher,
        new WatchEvent.Kind<?>[] {
          StandardWatchEventKinds.ENTRY_CREATE,
          StandardWatchEventKinds.ENTRY_MODIFY,
          StandardWatchEventKinds.ENTRY_DELETE,
        },
        SensitivityWatchEventModifier.HIGH);
    this.thread =
        new Thread(this::loop, "meridian-bundle-watcher:" + this.bundlePath.getFileName());
    this.thread.setDaemon(true);
  }

  /**
   * Parses the bundle once and starts the background watch thread.
   * The listener is NOT invoked with the initial bundle — caller is
   * responsible for using the returned value to set up initial state
   * (often on a specific thread that's hard to dispatch to from here,
   * e.g. JavaFX's Application thread). The listener fires on every
   * subsequent change.
   */
  public PanelBundle start() throws IOException {
    PanelBundle initial = BundleLoader.parse(bundlePath);
    thread.start();
    return initial;
  }

  private void loop() {
    LOG.info("Watching " + bundlePath + " for changes (polling-based on macOS).");
    while (!closed) {
      WatchKey key;
      try {
        key = watcher.take();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      } catch (ClosedWatchServiceException e) {
        return;
      }
      boolean fileTouched = false;
      for (WatchEvent<?> ev : key.pollEvents()) {
        Object ctx = ev.context();
        if (!(ctx instanceof Path)) continue;
        Path entry = (Path) ctx;
        if (entry.getFileName().equals(bundlePath.getFileName())) {
          fileTouched = true;
          LOG.fine("Bundle event " + ev.kind().name() + " on " + entry);
        }
      }
      key.reset();
      if (fileTouched) {
        reload();
      }
    }
  }

  private void reload() {
    try {
      PanelBundle bundle = BundleLoader.parse(bundlePath);
      listener.accept(bundle);
      LOG.info("Reloaded panel bundle '" + bundle.getVersion() + "' from " + bundlePath);
    } catch (IOException e) {
      // Editors stage saves through temp files; transient parse errors
      // are normal during a save. Log at FINE so the dev loop stays
      // quiet on the happy path.
      LOG.log(Level.FINE, "Bundle reload failed (likely mid-save): " + e.getMessage());
    }
  }

  @Override
  public void close() throws IOException {
    closed = true;
    watcher.close();
    thread.interrupt();
  }
}
