// PanelBodyView — renders one PanelDescriptor's body (table / lro / prompt /
// adhoc / llm_prompt / gallery) without any surrounding navigation chrome.
//
// Extracted from PanelContainerView so a host can render a single panel on its
// own — the macOS Dashboard nests it under its NavigationSplitView detail pane,
// and the iOS console drives it directly from a server-fetched single-panel
// bundle. Table is the one fully-native shape today; the rest render a
// placeholder until their views land.

import SwiftUI

public struct PanelBodyView: View {
    @ObservedObject var state: PanelState
    let panel: PanelDescriptor

    public init(state: PanelState, panel: PanelDescriptor) {
        self.state = state
        self.panel = panel
    }

    public var body: some View {
        switch panel.body {
        case let .table(table):
            // Identity by panel id so switching panels resets table state.
            TablePanelView(state: state, table: table).id(panel.panelID)
        case let .unsupported(label):
            PlaceholderPanelView(shape: label)
        case .prompt:
            PlaceholderPanelView(shape: "PromptPanel")
        case .lro:
            PlaceholderPanelView(shape: "LroPanel")
        case .adhoc:
            PlaceholderPanelView(shape: "AdhocPanel")
        case .llmPrompt:
            PlaceholderPanelView(shape: "LlmPromptPanel")
        }
    }
}
