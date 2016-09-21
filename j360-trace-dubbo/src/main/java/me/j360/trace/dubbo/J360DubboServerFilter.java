package me.j360.trace.dubbo;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.extension.Activate;
import com.alibaba.dubbo.rpc.*;
import me.j360.trace.collector.core.*;
import me.j360.trace.collector.core.internal.Nullable;
import me.j360.trace.http.BraveHttpHeaders;

import java.net.SocketAddress;
import java.util.Collection;
import java.util.Collections;

import static com.google.common.base.Preconditions.checkNotNull;

@Activate(group = {Constants.CONSUMER})
public class J360DubboServerFilter implements Filter {

    private final ServerRequestInterceptor serverRequestInterceptor;
    private final ServerResponseInterceptor serverResponseInterceptor;

    public J360DubboServerFilter(Brave brave) {
        this.serverRequestInterceptor = checkNotNull(brave.serverRequestInterceptor());
        this.serverResponseInterceptor = checkNotNull(brave.serverResponseInterceptor());
    }


    public Result invoke(Invoker<?> invoker, Invocation invocation) throws RpcException {

        if ("com.alibaba.dubbo.monitor.MonitorService".equals(invoker.getInterface().getName())) {
            return invoker.invoke(invocation);
        }

        RpcContext context = RpcContext.getContext();
        serverRequestInterceptor.handle(new DubboServerRequestAdapter(context, invocation));
        Result result = invoker.invoke(invocation);
        serverResponseInterceptor.handle(new DubboServerResponseAdapter(result));

        /*Endpoint endpoint = new Endpoint(context.getLocalHost(), context.getLocalPort());

        String traceId = invocation.getAttachment(Header.TRACE_ID);
        String spanId = invocation.getAttachment(Header.SPAN_ID);
        boolean sampled = Boolean.parseBoolean(invocation.getAttachment(Header.SAMPLED));
        Tracer.setRootSpan(traceId, spanId, sampled);

        // 没有跟踪头不采样
        if (Tracer.lastSpan() == null) {
            return invoker.invoke(invocation);
        }

        Span span = Tracer.begin(context.getMethodName());

        try {
            span.addEvent(Event.SERVER_RECV, endpoint);

            Result result = invoker.invoke(invocation);

            if (result.getException() != null) {
                span.addBinaryEvent("dubbo.exception", result.getException().getMessage(), endpoint);
            }

            return result;
        } catch (RpcException e) {
            span.addBinaryEvent("dubbo.exception", e.getMessage(), endpoint);
            throw e;
        } finally {
            span.addEvent(Event.SERVER_SEND, endpoint);
            Tracer.commit(span);
        }*/
        return null;
    }


    static final class DubboServerRequestAdapter<ReqT, RespT> implements ServerRequestAdapter {

        private RpcContext context;
        private Invocation invocation;

        public DubboServerRequestAdapter(RpcContext context,Invocation invocation) {
            this.context = checkNotNull(context);
            this.invocation = checkNotNull(invocation);
        }

        @Override
        public TraceData getTraceData() {
            final String  sampled = invocation.getAttachment(BraveHttpHeaders.Sampled.getName());
            if (sampled != null) {
                if (sampled.equals("0") || sampled.toLowerCase().equals("false")) {
                    return TraceData.builder().sample(false).build();
                } else {
                    final String parentSpanId = invocation.getAttachment(BraveHttpHeaders.ParentSpanId.getName());
                    final String traceId = invocation.getAttachment(BraveHttpHeaders.TraceId.getName());
                    final String spanId = invocation.getAttachment(BraveHttpHeaders.SpanId.getName());
                    if (traceId != null && spanId != null) {
                        SpanId span = getSpanId(traceId, spanId, parentSpanId);
                        return TraceData.builder().sample(true).spanId(span).build();
                    }
                }
            }
            return TraceData.builder().build();
        }

        @Override
        public String getSpanName() {
            return context.getMethodName();
        }

        @Override
        public Collection<KeyValueAnnotation> requestAnnotations() {

            SocketAddress socketAddress = context.getRemoteAddress();
            if (socketAddress != null) {
                KeyValueAnnotation remoteAddrAnnotation = KeyValueAnnotation.create(
                        DubboKeys.DUBBO_REMOTE_ADDR, socketAddress.toString());
                return Collections.singleton(remoteAddrAnnotation);
            } else {
                return Collections.emptyList();
            }
        }
    }

    static final class DubboServerResponseAdapter implements ServerResponseAdapter {

        final Status status;

        public GrpcServerResponseAdapter(Status status) {
            this.status = status;
        }

        @Override
        @SuppressWarnings("unchecked")
        public Collection<KeyValueAnnotation> responseAnnotations() {
            Code statusCode = status.getCode();
            return statusCode == Code.OK
                    ? Collections.<KeyValueAnnotation>emptyList()
                    : Collections.singletonList(KeyValueAnnotation.create(GRPC_STATUS_CODE, statusCode.name()));
        }

    }

    static SpanId getSpanId(String traceId, String spanId, String parentSpanId) {
        return SpanId.builder()
                .traceId(convertToLong(traceId))
                .spanId(convertToLong(spanId))
                .parentId(parentSpanId == null ? null : convertToLong(parentSpanId)).build();
    }


}
