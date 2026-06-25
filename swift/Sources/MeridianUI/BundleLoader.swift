// BundleLoader — decode a PanelBundle .binpb (emitted by the
// meridian_panel_bundle Bazel rule) into the renderer's plain Swift structs.
//
// This is the ONLY file that references the swift-protobuf generated types
// (the `MeridianProto` module from //proto:meridian_swift_proto). Everything
// else in MeridianUI uses the structs in Descriptors.swift, so if the
// generated symbol/field names differ from the conventions assumed here, the
// fixups stay contained to this mapping.

import Foundation
import MeridianProto

public enum BundleLoader {
    /// Decode a wire-encoded meridian.ui.v1.PanelBundle.
    public static func decode(_ data: Data) throws -> PanelBundle {
        let pb = try Meridian_Ui_V1_PanelBundle(serializedBytes: data)
        return PanelBundle(version: pb.version, panels: pb.panels.map(mapDescriptor))
    }

    // MARK: - descriptor

    private static func mapDescriptor(_ d: Meridian_Ui_V1_PanelDescriptor) -> PanelDescriptor {
        let body: PanelDescriptor.Body
        switch d.body {
        case let .table(t): body = .table(mapTable(t))
        case let .adhoc(a): body = .adhoc(AdhocPanel(handlerID: a.handlerID))
        case let .prompt(p):
            body = .prompt(PromptPanel(
                description: p.description_p,
                fields: [],
                isConfirmation: p.isConfirmation,
                acceptLabel: p.acceptLabel,
                cancelLabel: p.cancelLabel,
                detail: p.detail
            ))
        case let .lro(l):
            body = .lro(LroPanel(
                start: mapRpc(l.start),
                runButtonLabel: l.runButtonLabel,
                inputs: [],
                result: l.hasResult ? mapTable(l.result) : nil
            ))
        case let .llmPrompt(m):
            body = .llmPrompt(LlmPromptPanel(userTemplate: m.userTemplate, description: m.description_p))
        case .none:
            body = .unsupported("empty")
        }
        return PanelDescriptor(panelID: d.panelID, title: d.title, body: body)
    }

    // MARK: - table

    private static func mapTable(_ t: Meridian_Ui_V1_TablePanel) -> TablePanel {
        TablePanel(
            populate: mapRpc(t.populate),
            rowsField: t.rowsField,
            itemNoun: t.itemNoun,
            placeholder: t.placeholder,
            columns: t.columns.map(mapColumn),
            actions: t.actions.map(mapAction)
        )
    }

    private static func mapColumn(_ c: Meridian_Ui_V1_TableColumn) -> TableColumn {
        TableColumn(
            header: c.header,
            fieldPath: c.fieldPath,
            format: mapFormat(c.format),
            prefWidth: Int(c.prefWidth)
        )
    }

    private static func mapFormat(_ f: Meridian_Ui_V1_ColumnFormat) -> ColumnFormat {
        switch f {
        case .string: return .string
        case .float2Dp: return .float2dp
        case .integer: return .integer
        case .enumName: return .enumName
        case .stringList: return .stringList
        case .timestamp: return .timestamp
        case .unspecified, .UNRECOGNIZED: return .unspecified
        }
    }

    private static func mapAction(_ a: Meridian_Ui_V1_RowAction) -> RowAction {
        RowAction(
            label: a.label,
            rpc: a.hasRpc ? mapRpc(a.rpc) : nil,
            enabledWhen: a.hasEnabledWhen
                ? RowFilter(fieldPath: a.enabledWhen.fieldPath, equals: a.enabledWhen.equals)
                : nil,
            refreshOnSuccess: a.refreshOnSuccess
        )
    }

    // MARK: - rpc

    private static func mapRpc(_ r: Meridian_Ui_V1_RpcCall) -> RpcCall {
        RpcCall(service: r.service, method: r.method, bindings: r.bindings.map(mapBinding))
    }

    private static func mapBinding(_ b: Meridian_Ui_V1_FieldBinding) -> FieldBinding {
        let source: FieldBinding.Source
        switch b.source {
        case let .context(c): source = .context(mapContextSource(c))
        case let .rowField(s): source = .rowField(s)
        case let .formField(s): source = .formField(s)
        case let .literal(s): source = .literal(s)
        case let .nested(n): source = .nested(n.fields.map(mapBinding))
        case .none: source = .none
        }
        return FieldBinding(requestField: b.requestField, source: source)
    }

    private static func mapContextSource(_ c: Meridian_Ui_V1_ContextSource) -> ContextSource {
        switch c {
        case .currentResourcePath: return .currentResourcePath
        case .uiIdentity: return .uiIdentity
        case .unspecified, .UNRECOGNIZED: return .unspecified
        }
    }
}
