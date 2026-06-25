// Render — turn an RPC response (JSON) + a TablePanel descriptor into rows of
// formatted cells. Port of `render_table` / `format_cell` / `format_value`
// (rust/uiview/src/render.rs).

import Foundation

/// One rendered table row: the formatted cell strings (1:1 with the panel's
/// columns) plus the raw row JSON (kept so row actions can resolve bindings).
public struct RenderedRow: Identifiable {
    public let index: Int
    public let raw: JSONValue
    public let cells: [String]
    public var id: Int { index }
}

public enum Render {
    /// Resolve `table.rowsField` against `response` and format each row's
    /// cells per its columns.
    public static func renderTable(_ response: JSONValue, _ table: TablePanel) -> [RenderedRow] {
        response.rows(table.rowsField).enumerated().map { idx, row in
            RenderedRow(
                index: idx,
                raw: row,
                cells: table.columns.map { formatCell(row, $0) }
            )
        }
    }

    /// Format one cell: resolve the column's field-path within `row`, then
    /// apply its ColumnFormat.
    public static func formatCell(_ row: JSONValue, _ column: TableColumn) -> String {
        formatValue(row.get(column.fieldPath), column.format)
    }

    public static func formatValue(_ value: JSONValue, _ format: ColumnFormat) -> String {
        switch format {
        case .float2dp:
            if let d = value.asDouble { return String(format: "%.2f", d) }
            return scalar(value)
        case .integer:
            if let d = value.asDouble { return String(Int(d.rounded())) }
            return scalar(value)
        case .stringList:
            if let arr = value.asArray {
                return arr.map(scalar).joined(separator: ", ")
            }
            return scalar(value)
        case .timestamp, .string, .enumName, .unspecified:
            return scalar(value)
        }
    }

    /// Render a scalar JSON value as a display string (the default formatter).
    private static func scalar(_ value: JSONValue) -> String {
        switch value {
        case .null: return ""
        case let .string(s): return s
        case let .bool(b): return b ? "true" : "false"
        case let .number(n):
            // Integers print without a trailing ".0".
            if n == n.rounded() && abs(n) < 1e15 { return String(Int(n)) }
            return String(n)
        case let .array(a): return a.map(scalar).joined(separator: ", ")
        case .object: return ""
        }
    }
}
