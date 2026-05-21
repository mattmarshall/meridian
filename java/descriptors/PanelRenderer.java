package meridian.ui.descriptors;

import meridian.ui.UiCard;
import meridian.ui.v1.PanelDescriptor;

// One-stop entry point for instantiating a UiCard from a
// PanelDescriptor. JavaFX has its own implementation; a future TUI
// host would write its own consuming the same descriptors.
public interface PanelRenderer {
  /**
   * Builds and returns the host's UiCard for the given descriptor.
   * Implementations dispatch on the body oneof: TABLE bodies become a
   * generic table renderer; LRO bodies become a generic LRO driver;
   * ADHOC bodies look up a host-side factory keyed by handler_id.
   */
  UiCard render(PanelDescriptor descriptor);
}
