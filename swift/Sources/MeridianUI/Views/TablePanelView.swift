// TablePanelView — renders one TablePanel: populate on appear, show a table of
// formatted cells with dynamic columns, support refresh and row actions. The
// SwiftUI analog of the TUI's PanelView (rust/tui/src/widget.rs): it owns the
// row cache + selection; PanelState owns the catalog + invoker.

import SwiftUI

public struct TablePanelView: View {
    @ObservedObject var state: PanelState
    let table: TablePanel

    @State private var rows: [RenderedRow] = []
    @State private var selection: Int?
    @State private var loading = false
    @State private var errorText: String?

    public init(state: PanelState, table: TablePanel) {
        self.state = state
        self.table = table
    }

    public var body: some View {
        VStack(spacing: 0) {
            if let errorText {
                banner(errorText, system: "exclamationmark.triangle.fill", tint: .orange)
            }
            content
        }
        .toolbar {
            ToolbarItemGroup {
                ForEach(table.actions) { action in
                    Button(action.label) { run(action) }
                        .disabled(!isEnabled(action))
                }
                Button { Task { await load() } } label: {
                    Image(systemName: "arrow.clockwise")
                }
                .help("Refresh")
                .disabled(loading)
            }
        }
        .task { await load() }
    }

    @ViewBuilder
    private var content: some View {
        if loading && rows.isEmpty {
            ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
        } else if rows.isEmpty {
            ContentUnavailableView(
                placeholderTitle,
                systemImage: "tray",
                description: Text(table.placeholder)
            )
        } else {
            tableView
            footer
        }
    }

    private var tableView: some View {
        Table(rows, selection: $selection) {
            TableColumnForEach(Array(table.columns.enumerated()), id: \.element.id) { item in
                TableColumn(item.element.header) { (row: RenderedRow) in
                    Text(row.cells.indices.contains(item.offset) ? row.cells[item.offset] : "")
                        .lineLimit(1)
                        .truncationMode(.middle)
                }
                .width(min: 60, ideal: CGFloat(max(item.element.prefWidth, 6)) * 7)
            }
        }
    }

    private var footer: some View {
        HStack {
            Text("\(rows.count) \(table.itemNoun)")
                .font(.caption)
                .foregroundStyle(.secondary)
            Spacer()
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 6)
    }

    private func banner(_ text: String, system: String, tint: Color) -> some View {
        HStack(spacing: 8) {
            Image(systemName: system).foregroundStyle(tint)
            Text(text).font(.callout).lineLimit(2)
            Spacer()
        }
        .padding(8)
        .background(tint.opacity(0.12))
    }

    private var placeholderTitle: String {
        "No \(table.itemNoun)"
    }

    // MARK: - Behavior

    private func load() async {
        loading = true
        errorText = nil
        defer { loading = false }
        do {
            rows = try await state.loadRows(for: table)
        } catch {
            errorText = "\(error)"
            rows = []
        }
    }

    private func selectedRow() -> JSONValue? {
        guard let sel = selection, let row = rows.first(where: { $0.index == sel }) else {
            return nil
        }
        return row.raw
    }

    /// A row action is enabled when a row is selected and its optional
    /// `enabled_when` filter matches that row (RowFilter: field == value).
    private func isEnabled(_ action: RowAction) -> Bool {
        guard let row = selectedRow() else { return false }
        guard let filter = action.enabledWhen else { return true }
        return Render.formatValue(row.get(filter.fieldPath), .string) == filter.equals
    }

    private func run(_ action: RowAction) {
        guard let row = selectedRow() else { return }
        Task {
            do {
                try await state.runAction(action, selectedRow: row)
                if action.refreshOnSuccess { await load() }
            } catch {
                errorText = "\(error)"
            }
        }
    }
}
