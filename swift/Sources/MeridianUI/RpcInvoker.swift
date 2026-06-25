// RpcInvoker — the host-supplied transport. meridian itself never speaks gRPC;
// a renderer host plugs in a concrete invoker (gRPC, REST, a subprocess shim,
// a mock). Mirrors the `RpcInvoker` trait (rust/tui/src/invoker.rs) and the
// TypeScript `RpcInvoker` interface.

import Foundation

public protocol RpcInvoker: Sendable {
    /// Invoke `service`/`method` with a JSON request, returning the JSON
    /// response. The descriptor's `field_path`s resolve against the response.
    func invoke(service: String, method: String, request: JSONValue) async throws -> JSONValue
}

public enum RpcError: Error, CustomStringConvertible {
    case transport(String)
    case unknownMethod(service: String, method: String)
    case decode(String)

    public var description: String {
        switch self {
        case let .transport(m): return m
        case let .unknownMethod(s, m): return "unknown method: \(s)/\(m)"
        case let .decode(m): return "decode error: \(m)"
        }
    }
}
