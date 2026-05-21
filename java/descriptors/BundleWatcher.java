package meridian.ui.descriptors;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
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
    this.bundlePath = bundlePath.toAbsolutePath();
    this.listener = listener;
    this.watcher = FileSystems.getDefault().newWatchService();
    Path dir = this.bundlePath.getParent();
    if (dir == null) {
      throw new IOException("Bundle path has no parent directory: " + bundlePath);
    }
    dir.register(
        watcher,
        StandardWatchEventKinds.ENTRY_CREATE,
        StandardWatchEventKinds.ENTRY_MODIFY,
        StandardWatchEventKinds.ENTRY_DELETE);
    this.thread =
        new Thread(this::loop, "meridian-bundle-watcher:" + this.bundlePath.getFileName());
    this.thread.setDaemon(true);
  }

  /** Parses the bundle once and starts the background watch thread. */
  public PanelBundle start() throws IOException {
    PanelBundle initial = BundleLoader.parse(bundlePath);
    listener.accept(initial);
    thread.start();
    return initial;
  }

  private void loop() {
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
      boolean fileTouched =
          key.pollEvents().stream()
              .anyMatch(
                  ev -> {
                    Object ctx = ev.context();
                    if (!(ctx instanceof Path)) {
                      return false;
                    }
                    Path entry = (Path) ctx;
                    return entry.getFileName().equals(bundlePath.getFileName());
                  });
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
