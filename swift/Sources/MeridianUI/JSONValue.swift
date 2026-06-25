// JSONValue — the JSON interchange model every meridian renderer works over.
//
// meridian is reflection-free: RPC responses arrive as JSON (here, from the
// fvd-json shim), and descriptors address into them with dot-separated,
// snake_case proto field-paths (e.g. "spec.name"). This mirrors the Rust
// `ProtoPaths` (rust/uiview/src/paths.rs) and Java `ProtoPaths`.

import Foundation

/// A parsed JSON value. `Codable` so responses decode directly; the
/// path helpers (`get`/`rows`) are the Swift twin of meridian's ProtoPaths.
public enum JSONValue: Equatable {
    case null
    case bool(Bool)
    case number(Double)
    case string(String)
    case array([JSONValue])
    case object([String: JSONValue])

    /// Parse a UTF-8 JSON document (what the fvd-json shim prints).
    public static func parse(_ data: Data) throws -> JSONValue {
        let obj = try JSONSerialization.jsonObject(with: data, options: [.fragmentsAllowed])
        return JSONValue(any: obj)
    }

    public static func parse(_ text: String) throws -> JSONValue {
        try parse(Data(text.utf8))
    }

    init(any: Any) {
        switch any {
        case is NSNull:
            self = .null
        case let n as NSNumber:
            // Distinguish Bool from numeric NSNumber (Foundation bridges both).
            if CFGetTypeID(n) == CFBooleanGetTypeID() {
                self = .bool(n.boolValue)
            } else {
                self = .number(n.doubleValue)
            }
        case let s as String:
            self = .string(s)
        case let a as [Any]:
            self = .array(a.map(JSONValue.init(any:)))
        case let d as [String: Any]:
            self = .object(d.mapValues(JSONValue.init(any:)))
        default:
            self = .null
        }
    }
}

// MARK: - ProtoPaths (field-path access)

public extension JSONValue {
    /// Resolve a dot-separated field-path (e.g. "spec.name"), returning
    /// `.null` if any segment is missing — never throws. Port of
    /// `ProtoPaths::get` (rust/uiview/src/paths.rs).
    func get(_ path: String) -> JSONValue {
        guard !path.isEmpty else { return self }
        var cur = self
        for segment in path.split(separator: ".") {
            guard case let .object(map) = cur, let next = map[String(segment)] else {
                return .null
            }
            cur = next
        }
        return cur
    }

    /// Resolve `path` and return its elements if it's an array, else `[]`.
    /// Port of `ProtoPaths::rows`.
    func rows(_ path: String) -> [JSONValue] {
        if case let .array(items) = get(path) { return items }
        return []
    }

    // Convenience accessors for leaf reads.
    var asString: String? { if case let .string(s) = self { return s }; return nil }
    var asBool: Bool? { if case let .bool(b) = self { return b }; return nil }
    var asDouble: Double? { if case let .number(n) = self { return n }; return nil }
    var asArray: [JSONValue]? { if case let .array(a) = self { return a }; return nil }
    var isNull: Bool { self == .null }

    subscript(key: String) -> JSONValue {
        if case let .object(map) = self { return map[key] ?? .null }
        return .null
    }
}

// MARK: - Encoding (request JSON → text, for the RpcInvoker transport)

public extension JSONValue {
    /// Convert back to a Foundation object graph for JSONSerialization.
    func toFoundation() -> Any {
        switch self {
        case .null: return NSNull()
        case let .bool(b): return b
        case let .number(n): return n
        case let .string(s): return s
        case let .array(a): return a.map { $0.toFoundation() }
        case let .object(o): return o.mapValues { $0.toFoundation() }
        }
    }

    /// Serialize to compact UTF-8 JSON (what the fvd-json shim expects as its
    /// request argument).
    func serialized() throws -> String {
        let data = try JSONSerialization.data(
            withJSONObject: toFoundation(),
            options: [.fragmentsAllowed, .sortedKeys]
        )
        return String(decoding: data, as: UTF8.self)
    }
}
