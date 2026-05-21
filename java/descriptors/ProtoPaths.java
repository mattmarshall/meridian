package meridian.ui.descriptors;

import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Message;
import java.util.ArrayList;
import java.util.List;

// Tiny field-path accessor for proto Message instances. Used by the
// descriptor-driven UI renderers to resolve TableColumn.field_path
// and FieldBinding.row_field expressions like
// "subject.claim.claim_text" into actual values.
//
// Intentionally minimal: dot-separated single-message walks, no
// indexing, no oneof traversal (the renderer handles repeated
// `rows_field` separately). When we need more (array indexing, map
// lookups, oneof case names) we extend here.
public final class ProtoPaths {
  private ProtoPaths() {}

  /**
   * Walks `message` along `path` (dot-separated) and returns the
   * value at the leaf field. Returns null if any intermediate field
   * isn't a Message or if a field is missing.
   *
   * For a scalar leaf, returns the boxed value (String, Long, Integer,
   * Float, Double, Boolean, EnumValueDescriptor, ByteString).
   * For a repeated leaf, returns the underlying java.util.List.
   * For a Message leaf, returns the sub-message.
   */
  public static Object get(Message message, String path) {
    if (path == null || path.isEmpty()) return message;
    String[] segments = path.split("\\.");
    Message current = message;
    for (int i = 0; i < segments.length - 1; i++) {
      FieldDescriptor fd = current.getDescriptorForType().findFieldByName(segments[i]);
      if (fd == null || fd.getJavaType() != FieldDescriptor.JavaType.MESSAGE) return null;
      current = (Message) current.getField(fd);
    }
    String leaf = segments[segments.length - 1];
    FieldDescriptor fd = current.getDescriptorForType().findFieldByName(leaf);
    if (fd == null) return null;
    return current.getField(fd);
  }

  /**
   * Reads the rows of a repeated field at `path`. Returns an empty
   * list if the path doesn't resolve or the field isn't repeated.
   * Each row is guaranteed to be a Message (since UI table rows are
   * always proto sub-messages in our framework).
   */
  @SuppressWarnings("unchecked")
  public static List<Message> rows(Message message, String path) {
    Object value = get(message, path);
    if (!(value instanceof List<?>)) return new ArrayList<>();
    List<?> raw = (List<?>) value;
    List<Message> out = new ArrayList<>(raw.size());
    for (Object item : raw) {
      if (item instanceof Message) out.add((Message) item);
    }
    return out;
  }
}
