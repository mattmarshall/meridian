package meridian.ui.descriptors;

import com.google.protobuf.Descriptors.EnumValueDescriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Message;
import java.util.Map;
import meridian.ui.v1.ContextSource;
import meridian.ui.v1.FieldBinding;
import meridian.ui.v1.NestedBinding;
import meridian.ui.v1.RpcCall;

// Builds a gRPC request Message from an RpcCall's FieldBinding set
// plus the runtime context (current PDF, UI identity, selected row
// fields, form input values). Uses proto Descriptors so the same
// builder works for every service without per-call code.
public final class RequestBuilder {
  private RequestBuilder() {}

  /** Renders a context-bag containing the values FieldBinding sources may pull from. */
  public static final class Context {
    public final String currentResourcePath;
    public final Message uiIdentity;
    public final Message selectedRow;
    public final Map<String, Object> formValues;

    public Context(String currentResourcePath, Message uiIdentity,
        Message selectedRow, Map<String, Object> formValues) {
      this.currentResourcePath = currentResourcePath;
      this.uiIdentity = uiIdentity;
      this.selectedRow = selectedRow;
      this.formValues = formValues == null ? Map.of() : formValues;
    }
  }

  /**
   * Builds the request for `call` against the prototype's type, with
   * each FieldBinding's value sourced from `context`.
   */
  public static Message build(RpcCall call, Message prototype, Context context) {
    Message.Builder builder = prototype.newBuilderForType();
    for (FieldBinding binding : call.getBindingsList()) {
      applyBinding(builder, binding, context);
    }
    return builder.build();
  }

  private static void applyBinding(Message.Builder builder, FieldBinding binding, Context context) {
    String path = binding.getRequestField();
    if (path.isEmpty()) return;

    switch (binding.getSourceCase()) {
      case NESTED:
        applyNested(builder, path, binding.getNested(), context);
        return;
      case CONTEXT:
        setScalar(builder, path, resolveContext(binding.getContext(), context));
        return;
      case ROW_FIELD:
        if (context.selectedRow != null) {
          setScalar(builder, path, ProtoPaths.get(context.selectedRow, binding.getRowField()));
        }
        return;
      case FORM_FIELD:
        setScalar(builder, path, context.formValues.get(binding.getFormField()));
        return;
      case LITERAL:
        setScalar(builder, path, binding.getLiteral());
        return;
      case SOURCE_NOT_SET:
      default:
        return;
    }
  }

  private static Object resolveContext(ContextSource source, Context context) {
    switch (source) {
      case CURRENT_RESOURCE_PATH: return context.currentResourcePath;
      case UI_IDENTITY:      return context.uiIdentity;
      default: return null;
    }
  }

  // Sets a scalar (or single Message) field along a dotted path. Walks
  // sub-message builders, creating them as needed.
  private static void setScalar(Message.Builder builder, String path, Object value) {
    if (value == null) return;
    String[] segments = path.split("\\.");
    Message.Builder current = builder;
    for (int i = 0; i < segments.length - 1; i++) {
      FieldDescriptor fd = current.getDescriptorForType().findFieldByName(segments[i]);
      if (fd == null || fd.getJavaType() != FieldDescriptor.JavaType.MESSAGE) return;
      current = current.getFieldBuilder(fd);
    }
    FieldDescriptor fd = current.getDescriptorForType().findFieldByName(segments[segments.length - 1]);
    if (fd == null) return;
    Object coerced = coerce(fd, value);
    if (coerced != null) current.setField(fd, coerced);
  }

  // Builds a sub-message at `path` from nested FieldBindings.
  private static void applyNested(Message.Builder builder, String path, NestedBinding nested,
      Context context) {
    String[] segments = path.split("\\.");
    Message.Builder current = builder;
    for (int i = 0; i < segments.length - 1; i++) {
      FieldDescriptor fd = current.getDescriptorForType().findFieldByName(segments[i]);
      if (fd == null || fd.getJavaType() != FieldDescriptor.JavaType.MESSAGE) return;
      current = current.getFieldBuilder(fd);
    }
    FieldDescriptor leaf = current.getDescriptorForType().findFieldByName(segments[segments.length - 1]);
    if (leaf == null || leaf.getJavaType() != FieldDescriptor.JavaType.MESSAGE) return;
    Message.Builder sub = current.getFieldBuilder(leaf);
    for (FieldBinding child : nested.getFieldsList()) {
      applyBinding(sub, child, context);
    }
  }

  // Best-effort coercion from binding-provided values to the proto
  // field's expected Java type.
  private static Object coerce(FieldDescriptor fd, Object value) {
    if (value == null) return null;
    switch (fd.getJavaType()) {
      case STRING:
        return value.toString();
      case INT:
        return toInt(value);
      case LONG:
        return toLong(value);
      case FLOAT:
        return value instanceof Number ? ((Number) value).floatValue()
            : Float.parseFloat(value.toString());
      case DOUBLE:
        return value instanceof Number ? ((Number) value).doubleValue()
            : Double.parseDouble(value.toString());
      case BOOLEAN:
        return value instanceof Boolean ? value : Boolean.parseBoolean(value.toString());
      case ENUM:
        if (value instanceof EnumValueDescriptor) return value;
        EnumValueDescriptor evd = fd.getEnumType().findValueByName(value.toString());
        return evd;
      case MESSAGE:
        return value instanceof Message ? value : null;
      case BYTE_STRING:
        return value;
      default:
        return value;
    }
  }

  private static Integer toInt(Object value) {
    if (value instanceof Number) return ((Number) value).intValue();
    return Integer.parseInt(value.toString());
  }

  private static Long toLong(Object value) {
    if (value instanceof Number) return ((Number) value).longValue();
    return Long.parseLong(value.toString());
  }
}
