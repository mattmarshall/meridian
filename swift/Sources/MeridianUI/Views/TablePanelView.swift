// TablePanelView — renders one TablePanel: populate on appear, show a table of
// formatted cells with dynamic columns, support refresh and row actions. The
// SwiftUI analog of the TUI's PanelView (rust/tui/src/widget.rs): it owns the
// row cache + selection; PanelState owns the catalog + invoker.

import SwiftUI

public struct TablePanelView: View {
    @ObservedObject var state: PanelState
    let table: TablePanel

    @Environment(\.meridianTheme) private var theme

    #if os(iOS)
    // A multi-column SwiftUI `Table` collapses to a single column at compact
    // width (iPhone portrait), so fall back to a stacked `List` there. The
    // horizontalSizeClass key only exists on iOS — hence the #if os guard.
    @Environment(\.horizontalSizeClass) private var hSize
    #endif

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
                banner(errorText, system: "exclamationmark.triangle.fill", tint: theme.danger)
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

    @ViewBuilder
    private var tableView: some View {
        #if os(iOS)
        if hSize == .compact {
            compactList
        } else {
            wideTable
        }
        #else
        wideTable
        #endif
    }

    private var wideTable: some View {
        // Note: `TableColumn` is qualified to SwiftUI's — our descriptor type is
        // also named TableColumn (Descriptors.swift) and would otherwise shadow it.
        Table(rows, selection: $selection) {
            TableColumnForEach(Array(table.columns.enumerated()), id: \.element.id) { item in
                SwiftUI.TableColumn(item.element.header) { (row: RenderedRow) in
                    Text(row.cells.indices.contains(item.offset) ? row.cells[item.offset] : "")
                        .lineLimit(1)
                        .truncationMode(.middle)
                }
                .width(min: 60, ideal: CGFloat(max(item.element.prefWidth, 6)) * 7)
            }
        }
    }

    // Compact (iPhone) fallback: one List row per record, columns stacked as
    // `header: value` lines. The first column is the emphasized title; empty
    // cells are omitted. Selection binds to RenderedRow.id (its index), the same
    // key the wide Table uses, so row actions work identically.
    private var compactList: some View {
        List(rows, selection: $selection) { row in
            VStack(alignment: .leading, spacing: 3) {
                ForEach(Array(table.columns.enumerated()), id: \.element.id) { index, column in
                    let cell = row.cells.indices.contains(index) ? row.cells[index] : ""
                    if index == 0 {
                        Text(cell.isEmpty ? "—" : cell)
                            .font(.body)
                            .lineLimit(1)
                    } else if !cell.isEmpty {
                        HStack(spacing: 8) {
                            Text(column.header)
                                .font(.caption)
                                .foregroundStyle(theme.muted)
                            Spacer(minLength: 8)
                            Text(cell)
                                .font(.caption)
                                .lineLimit(1)
                                .truncationMode(.middle)
                        }
                    }
                }
            }
            .padding(.vertical, 2)
        }
    }

    private var footer: some View {
        HStack {
            Text("\(rows.count) \(table.itemNoun)")
                .font(.caption)
                .foregroundStyle(theme.muted)
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
