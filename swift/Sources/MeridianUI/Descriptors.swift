// Descriptors — meridian.ui.v1 panel descriptors as plain Swift structs.
//
// These mirror the proto schema (proto/*.proto) but are decoupled from the
// swift-protobuf generated types: `BundleLoader` decodes the binpb with
// swift-protobuf and maps into these. Keeping the renderer's model in plain
// structs means the views/helpers never touch generated symbol names, and any
// codegen-naming fixups stay localized to BundleLoader.
//
// v1 fully supports the Table shape (the fastverk Dashboard is all tables);
// the other shapes are carried so the model is complete and render as a
// placeholder until implemented.

import Foundation

public struct PanelBundle {
    public var version: String
    public var panels: [PanelDescriptor]
    public init(version: String, panels: [PanelDescriptor]) {
        self.version = version
        self.panels = panels
    }
}

public struct PanelDescriptor: Identifiable {
    public var panelID: String
    public var title: String
    public var body: Body

    public var id: String { panelID }

    public enum Body {
        case table(TablePanel)
        case prompt(PromptPanel)
        case lro(LroPanel)
        case adhoc(AdhocPanel)
        case llmPrompt(LlmPromptPanel)
        case unsupported(String) // carries a label for the placeholder view
    }

    public init(panelID: String, title: String, body: Body) {
        self.panelID = panelID
        self.title = title
        self.body = body
    }
}

// MARK: - Table

public struct TablePanel {
    public var populate: RpcCall
    public var rowsField: String
    public var itemNoun: String
    public var placeholder: String
    public var columns: [TableColumn]
    public var actions: [RowAction]
}

public struct TableColumn: Identifiable {
    public var header: String
    public var fieldPath: String
    public var format: ColumnFormat
    public var prefWidth: Int

    public var id: String { header + "\u{1}" + fieldPath }
}

public enum ColumnFormat {
    case unspecified
    case string
    case float2dp
    case integer
    case enumName
    case stringList
    case timestamp
}

public struct RowAction: Identifiable {
    public var label: String
    public var rpc: RpcCall?
    public var enabledWhen: RowFilter?
    public var refreshOnSuccess: Bool

    public var id: String { label }
}

public struct RowFilter {
    public var fieldPath: String
    public var equals: String
}

// MARK: - RPC

public struct RpcCall {
    public var service: String
    public var method: String
    public var bindings: [FieldBinding]
}

public struct FieldBinding {
    public var requestField: String
    public var source: Source

    public enum Source {
        case context(ContextSource)
        case rowField(String)
        case formField(String)
        case literal(String)
        case nested([FieldBinding])
        case none
    }
}

public enum ContextSource {
    case unspecified
    case currentResourcePath
    case uiIdentity
}

// MARK: - Other shapes (carried, placeholder-rendered in v1)

public struct PromptPanel {
    public var description: String
    public var fields: [FormField]
    public var isConfirmation: Bool
    public var acceptLabel: String
    public var cancelLabel: String
    public var detail: String
}

public struct LroPanel {
    public var start: RpcCall
    public var runButtonLabel: String
    public var inputs: [FormField]
    public var result: TablePanel?
}

public struct AdhocPanel {
    public var handlerID: String
}

public struct LlmPromptPanel {
    public var userTemplate: String
    public var description: String
}

// MARK: - Form fields

public struct FormField: Identifiable {
    public var fieldID: String
    public var label: String
    public var requestField: String
    public var description: String
    public var kind: Kind

    public var id: String { fieldID }

    public enum Kind {
        case text(TextInput)
        case integer(IntegerSpinner)
        case enumSelection(EnumSelection)
        case masked(MaskedInput)
        case none
    }
}

public struct TextInput {
    public var defaultValue: String
    public var pattern: String
}

public struct IntegerSpinner {
    public var min: Int
    public var max: Int
    public var defaultValue: Int
    public var step: Int
}

public struct EnumSelection {
    public var allowedValues: [String]
    public var defaultValue: String
}

public struct MaskedInput {
    public var defaultValue: String
    public var pattern: String
}
