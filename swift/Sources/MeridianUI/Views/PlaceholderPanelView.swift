// PlaceholderPanelView — shown for descriptor shapes the Swift renderer does
// not yet implement (Prompt / Lro / Adhoc / LlmPrompt). v1 fully supports
// Table; the others render this so a bundle mixing shapes still loads cleanly.

import SwiftUI

public struct PlaceholderPanelView: View {
    let shape: String

    public init(shape: String) {
        self.shape = shape
    }

    public var body: some View {
        ContentUnavailableView(
            "\(shape) not yet supported",
            systemImage: "rectangle.on.rectangle.slash",
            description: Text("The Swift renderer implements TablePanel in v1. This panel shape is coming.")
        )
    }
}
