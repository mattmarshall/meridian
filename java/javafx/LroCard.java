package meridian.ui.javafx;

import com.google.longrunning.Operation;
import com.google.longrunning.OperationsGrpc;
import com.google.longrunning.WaitOperationRequest;
import com.google.protobuf.Duration;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import io.grpc.ManagedChannel;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import meridian.ui.UiBusy;
import meridian.ui.UiCard;

// Reusable scaffold for hand-coded LRO cards that don't fit the
// generic DescribedLroCard shape (typically because they post-process
// the LRO response in a way the descriptor can't yet express —
// joined parallel arrays, multi-pass rendering, etc.). Generic over
// the metadata message type (M) and the response message type (R).
//
// Most LRO panels should use DescribedLroCard via a PanelDescriptor
// instead; this base is the escape hatch.
//
// Subclasses supply:
//   - panelId()       — stable identifier (matches a PanelDescriptor.panel_id)
//   - title()         — header
//   - body()          — a JavaFX node rendered above the action button
//                       (inputs, spinners, or empty)
//   - resultNode()    — a JavaFX node rendered below the button for
//                       displaying the final response (typically a
//                       TableView the subclass owns)
//   - actionLabel()   — what the run button says
//   - metadataType()  — proto class for the LRO metadata
//   - responseType()  — proto class for the final response
//   - startLro(resourcePath) — fires the start RPC; returns the Operation
//   - renderMetadata(meta) — turns a metadata message into status text
//   - renderResponse(resp) — populates the result node from the final
//                            response
public abstract class LroCard<M extends Message, R extends Message> extends VBox implements UiCard {
  protected final Label header = new Label();
  protected final Label meta = new Label();
  protected final Button runButton = new Button();
  protected final HBox actions = new HBox(8);

  private final OperationsGrpc.OperationsBlockingStub opsStub;
  private String currentResourcePath;

  protected LroCard(ManagedChannel channel) {
    this.opsStub = OperationsGrpc.newBlockingStub(channel);
    header.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");
    meta.setStyle("-fx-text-fill: #555;");
    runButton.setText(actionLabel());
    runButton.setOnAction(e -> startRun());
    actions.getChildren().add(runButton);
    actions.setPadding(new Insets(4, 0, 4, 0));
    setPadding(new Insets(8));
    setSpacing(4);
  }

  /** Subclasses MUST call this from their constructor after wiring widgets. */
  protected final void init() {
    header.setText(title());
    Node body = body();
    Node result = resultNode();
    if (body != null) {
      getChildren().setAll(header, meta, body, actions, result);
    } else {
      getChildren().setAll(header, meta, actions, result);
    }
    if (result instanceof javafx.scene.control.TableView<?>) {
      VBox.setVgrow(result, Priority.ALWAYS);
    }
  }

  protected abstract String title();
  protected abstract String actionLabel();
  protected abstract Node body();
  protected abstract Node resultNode();
  protected abstract Class<M> metadataType();
  protected abstract Class<R> responseType();
  protected abstract Operation startLro(String resourcePath);
  protected abstract String renderMetadata(M m);
  protected abstract void renderResponse(R r);

  /** Optional: idle message when no resource is active. */
  protected String idleMessage() { return "No active resource."; }

  /** Optional: ready message when a resource is loaded but nothing has run yet. */
  protected String readyMessage() { return "Click the button to run."; }

  @Override public Node node() { return this; }

  @Override public void onSelected(Object context, Object treeNodeData) {
    this.currentResourcePath = context instanceof String ? (String) context : null;
    if (currentResourcePath == null) {
      meta.setText(idleMessage());
      runButton.setDisable(true);
      return;
    }
    runButton.setDisable(false);
    if (meta.getText() == null || meta.getText().isEmpty()) {
      meta.setText(readyMessage());
    }
  }

  private void startRun() {
    if (currentResourcePath == null) return;
    runButton.setDisable(true);
    meta.setText("Submitting…");
    UiBusy.acquire();
    new Thread(() -> {
      try {
        drive(currentResourcePath);
      } finally {
        UiBusy.release();
      }
    }, "meridian-ui-" + getClass().getSimpleName()).start();
  }

  private void drive(String resourcePath) {
    try {
      Operation op = startLro(resourcePath);
      long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(30);
      while (!op.getDone() && System.nanoTime() < deadline) {
        // Short server-side wait so metadata streams live in the UI.
        // WaitOperation returns early on completion.
        op = opsStub.waitOperation(WaitOperationRequest.newBuilder()
            .setName(op.getName())
            .setTimeout(Duration.newBuilder().setSeconds(5).build())
            .build());
        if (op.hasMetadata()) {
          try {
            M md = op.getMetadata().unpack(metadataType());
            String status = renderMetadata(md);
            Platform.runLater(() -> meta.setText(status));
          } catch (InvalidProtocolBufferException ignored) {}
        }
      }
      if (!op.getDone()) {
        Platform.runLater(() -> {
          meta.setText("Timed out before completion");
          runButton.setDisable(false);
        });
        return;
      }
      if (op.hasError()) {
        String msg = op.getError().getMessage();
        Platform.runLater(() -> {
          meta.setText("Failed: " + msg);
          runButton.setDisable(false);
        });
        return;
      }
      R resp = op.getResponse().unpack(responseType());
      Platform.runLater(() -> {
        renderResponse(resp);
        runButton.setDisable(false);
      });
    } catch (Throwable t) {
      Platform.runLater(() -> {
        meta.setText("Failed: " + t.getMessage());
        runButton.setDisable(false);
      });
    }
  }
}
