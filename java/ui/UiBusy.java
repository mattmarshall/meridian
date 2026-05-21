package meridian.ui;

import javafx.application.Platform;
import javafx.beans.property.ReadOnlyIntegerProperty;
import javafx.beans.property.SimpleIntegerProperty;

// Process-wide busy counter for the JavaFX renderer. Cards call
// UiBusy.acquire() before kicking off async work (background-thread
// RPC, LRO polling loop, server-streaming) and UiBusy.release() in
// the finally branch. A host-level toolbar progress indicator can
// bind its visibility to (UiBusy.count > 0) so the user gets a
// single, persistent signal that something is happening — independent
// of which specific panel is loading.
//
// acquire / release are safe to call from any thread; updates are
// marshalled onto the JavaFX application thread.
public final class UiBusy {
  private static final SimpleIntegerProperty COUNT = new SimpleIntegerProperty(0);

  private UiBusy() {}

  /** Increment the busy counter. Safe from any thread. */
  public static void acquire() {
    runOnFx(() -> COUNT.set(COUNT.get() + 1));
  }

  /** Decrement the busy counter. Safe from any thread. */
  public static void release() {
    runOnFx(() -> COUNT.set(Math.max(0, COUNT.get() - 1)));
  }

  /** The live counter; UI bindings should observe this. */
  public static ReadOnlyIntegerProperty countProperty() { return COUNT; }

  private static void runOnFx(Runnable r) {
    if (Platform.isFxApplicationThread()) r.run();
    else Platform.runLater(r);
  }
}
