package meridian.ui.javafx;

import com.google.protobuf.Message;
import io.grpc.ManagedChannel;
import java.util.HashMap;
import java.util.Map;
import meridian.ui.UiCard;
import meridian.ui.descriptors.PanelRenderer;
import meridian.ui.descriptors.RpcRegistry;
import meridian.ui.v1.PanelDescriptor;

// JavaFX implementation of PanelRenderer. Dispatches each descriptor
// to the appropriate JavaFX widget tree:
//
//   TABLE body  → DescribedTableCard
//   LRO body    → DescribedLroCard
//   ADHOC body  → factory keyed by handler_id, registered by the host
//
// Adhoc factories are how the framework accommodates one-off layouts
// (SPARQL editor, image viewer, document tree, parallel-array tables)
// without forcing them into the generic shapes. Each hand-coded card
// gets a descriptor with an AdhocPanel body and a factory closure
// owning whatever channel / state it needs.
//
// Card identity flows directly from PanelDescriptor.panel_id — the
// host dispatches selections by the same string the descriptor
// carries, so adding a new panel is one descriptor entry on the host
// side (plus, for adhoc panels, a registerAdhoc() factory).
public final class JavaFxPanelRenderer implements PanelRenderer {

  /** Factory for an AdhocPanel: PanelDescriptor → UiCard. */
  @FunctionalInterface
  public interface AdhocFactory {
    UiCard create(PanelDescriptor descriptor);
  }

  private final RpcRegistry registry;
  private final Message uiIdentity;
  private final ManagedChannel channel;
  private final Map<String, AdhocFactory> adhocFactories = new HashMap<>();

  public JavaFxPanelRenderer(RpcRegistry registry, Message uiIdentity, ManagedChannel channel) {
    this.registry = registry;
    this.uiIdentity = uiIdentity;
    this.channel = channel;
  }

  /** Registers an adhoc factory keyed by handler_id. */
  public JavaFxPanelRenderer registerAdhoc(String handlerId, AdhocFactory factory) {
    adhocFactories.put(handlerId, factory);
    return this;
  }

  @Override public UiCard render(PanelDescriptor descriptor) {
    switch (descriptor.getBodyCase()) {
      case TABLE:
        return new DescribedTableCard(descriptor, registry, uiIdentity);
      case LRO:
        return new DescribedLroCard(descriptor, registry, uiIdentity, channel);
      case ADHOC: {
        String id = descriptor.getAdhoc().getHandlerId();
        AdhocFactory f = adhocFactories.get(id);
        if (f == null) {
          throw new IllegalArgumentException(
              "No adhoc factory registered for handler_id=" + id);
        }
        return f.create(descriptor);
      }
      case BODY_NOT_SET:
      default:
        throw new IllegalArgumentException(
            "PanelDescriptor " + descriptor.getPanelId() + " has no body set");
    }
  }
}
