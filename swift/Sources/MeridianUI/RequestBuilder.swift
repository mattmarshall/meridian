// RequestBuilder — assemble an RPC request (as JSON) from an RpcCall's field
// bindings + the runtime Context. Port of `RequestBuilder` (rust/uiview/
// src/request.rs) and the Java `RequestBuilder`.
//
// Each binding names a `request_field` (a dot-path within the request) and a
// source (context / selected row / form value / literal / nested). Unresolved
// bindings are written as null, matching the Rust behavior.

import Foundation

public enum RequestBuilder {
    /// Build the request JSON object for `call` given `ctx`.
    public static func build(_ call: RpcCall, _ ctx: Context) -> JSONValue {
        var root: JSONValue = .object([:])
        for binding in call.bindings {
            let value = resolve(binding.source, ctx)
            setPath(&root, binding.requestField, value)
        }
        return root
    }

    private static func resolve(_ source: FieldBinding.Source, _ ctx: Context) -> JSONValue {
        switch source {
        case let .context(cs):
            switch cs {
            case .currentResourcePath:
                return ctx.currentResourcePath.map(JSONValue.string) ?? .null
            case .uiIdentity:
                return ctx.uiIdentity ?? .null
            case .unspecified:
                return .null
            }
        case let .rowField(path):
            return ctx.selectedRow?.get(path) ?? .null
        case let .formField(fieldID):
            return ctx.formValues[fieldID] ?? .null
        case let .literal(text):
            return literalValue(text)
        case let .nested(fields):
            var obj: JSONValue = .object([:])
            for f in fields {
                setPath(&obj, f.requestField, resolve(f.source, ctx))
            }
            return obj
        case .none:
            return .null
        }
    }

    /// Interpret a literal in terms of the target field's likely proto type.
    /// "true"/"false" → bool, a bare integer/float → number, else string —
    /// mirroring how the Rust builder coerces literals.
    private static func literalValue(_ text: String) -> JSONValue {
        switch text {
        case "true": return .bool(true)
        case "false": return .bool(false)
        default:
            if let i = Int(text) { return .number(Double(i)) }
            if let d = Double(text) { return .number(d) }
            return .string(text)
        }
    }

    /// Set `value` at a dot-separated `path` within the `root` object,
    /// creating intermediate objects as needed.
    static func setPath(_ root: inout JSONValue, _ path: String, _ value: JSONValue) {
        let segments = path.split(separator: ".").map(String.init)
        guard !segments.isEmpty else { return }
        root = inserting(into: root, segments: segments[...], value: value)
    }

    private static func inserting(
        into node: JSONValue,
        segments: ArraySlice<String>,
        value: JSONValue
    ) -> JSONValue {
        guard let head = segments.first else { return value }
        var map: [String: JSONValue]
        if case let .object(existing) = node { map = existing } else { map = [:] }
        let rest = segments.dropFirst()
        if rest.isEmpty {
            map[head] = value
        } else {
            map[head] = inserting(into: map[head] ?? .object([:]), segments: rest, value: value)
        }
        return .object(map)
    }
}
