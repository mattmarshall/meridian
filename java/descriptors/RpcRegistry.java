package meridian.ui.descriptors;

import com.google.protobuf.Message;
import io.grpc.ManagedChannel;
import io.grpc.MethodDescriptor;
import io.grpc.ServiceDescriptor;
import io.grpc.stub.ClientCalls;
import java.util.HashMap;
import java.util.Map;

// Registry of gRPC services callable by stable name. The descriptor-
// driven UI panels reference RPCs as ("some.example.Service",
// "ListClaims") strings; this registry maps that pair back to an
// io.grpc.MethodDescriptor for dispatch + a request prototype so the
// renderer can build a fresh request via newBuilderForType().
//
// Each Pinax gRPC service is registered once at UI startup. Adding a
// new service to the descriptor-driven path is one register() call.
public final class RpcRegistry {

  /** Builder convenience for registering a (service, method, prototype). */
  public static final class Entry {
    final ServiceDescriptor service;
    final Message requestPrototype;

    public Entry(ServiceDescriptor service, Message requestPrototype) {
      this.service = service;
      this.requestPrototype = requestPrototype;
    }
  }

  private final ManagedChannel channel;
  // Keyed by "service.fullName/methodName" e.g.
  // "some.example.Service/ListClaims".
  private final Map<String, ResolvedMethod> byKey = new HashMap<>();

  public RpcRegistry(ManagedChannel channel) {
    this.channel = channel;
  }

  /**
   * Registers one service method.
   *
   * @param serviceDescriptor   io.grpc.ServiceDescriptor from the
   *                            generated XxxServiceGrpc.getServiceDescriptor().
   * @param requestPrototype    a default-instance of the method's request
   *                            type, e.g. ListClaimsRequest.getDefaultInstance().
   *                            Used to build fresh requests reflectively.
   */
  public RpcRegistry register(ServiceDescriptor serviceDescriptor, String methodName,
      Message requestPrototype) {
    MethodDescriptor<?, ?> method = findMethod(serviceDescriptor, methodName);
    if (method == null) {
      throw new IllegalArgumentException(
          "Method " + methodName + " not found on service " + serviceDescriptor.getName());
    }
    String key = key(serviceDescriptor.getName(), methodName);
    byKey.put(key, new ResolvedMethod(method, requestPrototype));
    return this;
  }

  /**
   * Returns the resolved method for `service` + `method`, or null if
   * not registered.
   */
  public ResolvedMethod resolve(String service, String method) {
    return byKey.get(key(service, method));
  }

  /** Invokes a unary RPC against the registry's channel. */
  @SuppressWarnings("unchecked")
  public Message call(ResolvedMethod method, Message request) {
    return (Message) ClientCalls.blockingUnaryCall(
        channel,
        (MethodDescriptor<Message, Message>) method.descriptor,
        io.grpc.CallOptions.DEFAULT,
        request);
  }

  private static MethodDescriptor<?, ?> findMethod(ServiceDescriptor sd, String methodName) {
    String full = sd.getName() + "/" + methodName;
    for (MethodDescriptor<?, ?> md : sd.getMethods()) {
      if (md.getFullMethodName().equals(full) || methodName.equals(extractMethod(md.getFullMethodName()))) {
        return md;
      }
    }
    return null;
  }

  private static String extractMethod(String fullName) {
    int slash = fullName.indexOf('/');
    return slash < 0 ? fullName : fullName.substring(slash + 1);
  }

  private static String key(String service, String method) {
    return service + "/" + method;
  }

  /** One resolved (descriptor, request-prototype) pair. */
  public static final class ResolvedMethod {
    public final MethodDescriptor<?, ?> descriptor;
    public final Message requestPrototype;

    public ResolvedMethod(MethodDescriptor<?, ?> descriptor, Message requestPrototype) {
      this.descriptor = descriptor;
      this.requestPrototype = requestPrototype;
    }
  }
}
