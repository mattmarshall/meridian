// PanelContainerView — the top-level renderer view: a sidebar listing the
// bundle's panels and a detail area rendering the active one. This is the
// SwiftUI analog of the TUI's tab strip + active PanelView.

import SwiftUI

public struct PanelContainerView: View {
    @ObservedObject var state: PanelState

    public init(state: PanelState) {
        self.state = state
    }

    public var body: some View {
        NavigationSplitView {
            List(selection: selectionBinding) {
                ForEach(Array(state.panels.enumerated()), id: \.offset) { index, panel in
                    Text(panel.title).tag(index)
                }
            }
            .navigationSplitViewColumnWidth(min: 160, ideal: 180)
        } detail: {
            if let panel = state.activePanel {
                PanelBodyView(state: state, panel: panel)
                    .navigationTitle(panel.title)
            } else {
                ContentUnavailableView("No panels", systemImage: "rectangle.dashed")
            }
        }
    }

    private var selectionBinding: Binding<Int?> {
        Binding(get: { state.active }, set: { if let v = $0 { state.select(v) } })
    }
}
