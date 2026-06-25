// PanelState — the renderer's observable state: the catalog of panels, the
// active selection, the runtime context, and the invoker. SwiftUI views
// observe it. Mirrors `PanelAppState` (rust/tui/src/state.rs); the per-panel
// row cache/selection lives in the table view's own @State (like the TUI's
// PanelView).

import Foundation
import SwiftUI

@MainActor
public final class PanelState: ObservableObject {
    @Published public var panels: [PanelDescriptor]
    @Published public var active: Int
    public var context: Context
    public let invoker: any RpcInvoker

    public init(panels: [PanelDescriptor], context: Context = Context(), invoker: any RpcInvoker) {
        self.panels = panels
        self.active = 0
        self.context = context
        self.invoker = invoker
    }

    public var activePanel: PanelDescriptor? {
        panels.indices.contains(active) ? panels[active] : nil
    }

    public func select(_ index: Int) {
        if panels.indices.contains(index) { active = index }
    }

    public func nextPanel() {
        guard !panels.isEmpty else { return }
        active = (active + 1) % panels.count
    }

    public func prevPanel() {
        guard !panels.isEmpty else { return }
        active = (active - 1 + panels.count) % panels.count
    }

    /// Build a populate request and invoke it, returning rendered rows. Used by
    /// the table view. `selectedRow` lets row-action requests bind row fields.
    public func loadRows(for table: TablePanel, selectedRow: JSONValue? = nil) async throws -> [RenderedRow] {
        var ctx = context
        ctx.selectedRow = selectedRow ?? context.selectedRow
        let request = RequestBuilder.build(table.populate, ctx)
        let response = try await invoker.invoke(
            service: table.populate.service,
            method: table.populate.method,
            request: request
        )
        return Render.renderTable(response, table)
    }

    /// Fire a row action's RPC with the selected row bound into the context.
    public func runAction(_ action: RowAction, selectedRow: JSONValue) async throws {
        guard let rpc = action.rpc else { return }
        var ctx = context
        ctx.selectedRow = selectedRow
        let request = RequestBuilder.build(rpc, ctx)
        _ = try await invoker.invoke(service: rpc.service, method: rpc.method, request: request)
    }
}
