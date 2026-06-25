// Context — the runtime values a RequestBuilder pulls from when assembling an
// RPC request. Mirrors `meridian_uiview::Context` (rust/uiview/src/request.rs).

import Foundation

public struct Context {
    /// The active resource path (CONTEXT_SOURCE → CURRENT_RESOURCE_PATH).
    public var currentResourcePath: String?
    /// The UI's representative identity (CONTEXT_SOURCE → UI_IDENTITY).
    public var uiIdentity: JSONValue?
    /// The currently-selected table row (FieldBinding row_field source).
    public var selectedRow: JSONValue?
    /// Form inputs keyed by FormField.field_id (FieldBinding form_field source).
    public var formValues: [String: JSONValue]

    public init(
        currentResourcePath: String? = nil,
        uiIdentity: JSONValue? = nil,
        selectedRow: JSONValue? = nil,
        formValues: [String: JSONValue] = [:]
    ) {
        self.currentResourcePath = currentResourcePath
        self.uiIdentity = uiIdentity
        self.selectedRow = selectedRow
        self.formValues = formValues
    }
}
