package meridian.ui;

import javafx.scene.Node;

// One pane in the host's right-side detail area. Each PanelDescriptor
// materializes into exactly one UiCard; the host dispatches by
// panelId (the same string the descriptor carries).
//
// Two selection lifecycles share this interface:
//   * Service-oriented panels (PanelDescriptor body = TablePanel /
//     LroPanel) lazy-load against the currently active context (an
//     opaque Object the host supplies — typically a document path or
//     resource id).
//   * Structural panels (host's tree leaves carrying their own data)
//     read their payload from the second argument to onSelected.
//
// `onSelected` receives both — implementations use whichever fits.
//
// `panelId` is the dispatch key. Reserved values used by Meridian's
// stock structural panels:
//   "group"        — non-interactive label (root, section headers)
//   "part_detail"  — leaf carrying tree-node data
//   "page_detail"  — header carrying tree-node data
// All other panelIds are host-defined.
public interface UiCard {
  /** Stable identifier matching the originating PanelDescriptor.panel_id. */
  String panelId();

  /** The card's root JavaFX node (typically a VBox). */
  Node node();

  /**
   * Called when this card becomes the active right-pane card.
   *
   * @param context      host-supplied runtime context (typically the
   *                     active document identifier — a file path,
   *                     resource URI, etc.). Renderers pass it to
   *                     RpcCall bindings sourced from
   *                     CONTEXT_CURRENT_PDF_PATH.
   * @param treeNodeData arbitrary payload from the selected tree node
   *                     (PartDetail for part_detail, PageGroup for
   *                     page_detail, null for synthetic top-level
   *                     entries).
   */
  void onSelected(Object context, Object treeNodeData);
}
