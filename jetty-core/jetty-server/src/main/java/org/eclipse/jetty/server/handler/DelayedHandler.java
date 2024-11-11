//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.server.handler;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpHeaderValue;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.http.MultiPartConfig;
import org.eclipse.jetty.http.MultiPartFormData;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.server.FormFields;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Attributes;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Fields;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.thread.Invocable;

/**
 * <p>A {@link Handler.Wrapper} that can delay calling {@link Handler#handle(Request, Response, Callback)} on the
 * {@link #getHandler() next Handler} until content is available, either entirely or in part.  This handler is fully
 * asynchronous and will not block waiting for content.   Furthermore, for known content types, the content may be
 * parsed into {@link FormFields} or {@link MultiPartFormData.Parts} prior to handling. Thus, this handler can allow a
 * blocking application to run without blocking on input, as the content is asynchronously read before the application
 * is called.
 * </p>
 * <p>To delay for {@link FormFields}, the request content must be {@link org.eclipse.jetty.http.MimeTypes.Type#FORM_ENCODED}.
 * Once read by this handler, the fields are available via {@link FormFields#getFields(Request)}.
 * </p>
 * <p>To delay for {@link MultiPartFormData} content, a {@link org.eclipse.jetty.http.MultiPartConfig} instance must be set as
 * a {@link org.eclipse.jetty.server.Context} or {@link org.eclipse.jetty.server.Server} attribute with the class name
 * as they attribute name. Once read by this handler, the parts are available via
 * {@link MultiPartFormData#getParts(Attributes)}, passing in the {@link Request} as the {@link Attributes} instance.
 * </p>
 * <p> To delay for arbitrary content, the {@link #setMaxRetainedContentBytes(long)} configuration must
 * be non zero.  Once read, the data is made available via the standard {@link Request#read()} API.
 * </p>
 */
public class DelayedHandler extends Handler.Wrapper
{
    private long _maxRetainedContentBytes = -1;

    public DelayedHandler()
    {
        this(null);
    }

    public DelayedHandler(Handler handler)
    {
        super(handler);
    }

    public long getMaxRetainedContentBytes()
    {
        return _maxRetainedContentBytes;
    }

    /**
     * @param maxRetainedContentBytes The maximum bytes to {@link RetainableByteBuffer#retain() retain} whilst delaying content;
     *                           or 0 to never delay for content;
     *                           or -1 (default) for a heuristic value.
     */
    public void setMaxRetainedContentBytes(long maxRetainedContentBytes)
    {
        _maxRetainedContentBytes = maxRetainedContentBytes;
    }

    @Override
    public boolean handle(Request request, Response response, Callback callback) throws Exception
    {
        Handler next = getHandler();
        if (next == null)
            return false;

        boolean contentExpected = false;
        String contentType = null;
        MimeTypes.Type mimeType = null;
        loop: for (HttpField field : request.getHeaders())
        {
            HttpHeader header = field.getHeader();
            if (header == null)
                continue;
            switch (header)
            {
                case CONTENT_TYPE:
                    contentType = field.getValue();
                    mimeType = MimeTypes.getMimeTypeFromContentType(field);
                    break;

                case CONTENT_LENGTH:
                    contentExpected = field.getLongValue() > 0;
                    break;

                case TRANSFER_ENCODING:
                    contentExpected = field.contains(HttpHeaderValue.CHUNKED.asString());
                    break;

                case EXPECT:
                    if (field.contains(HttpHeaderValue.CONTINUE.asString()))
                    {
                        contentExpected = false;
                        break loop;
                    }
                    break;
                default:
                    break;
            }
        }

        DelayedProcess delayed = newDelayedProcess(contentExpected, contentType, mimeType, next, request, response, callback);
        if (delayed == null)
            return next.handle(request, response, callback);

        delayed.delay();
        return true;
    }

    protected DelayedProcess newDelayedProcess(boolean contentExpected, String contentType, MimeTypes.Type mimeType, Handler handler, Request request, Response response, Callback callback)
    {
        // if no content is expected, then no delay
        if (!contentExpected)
            return null;

        // if no known mimeType, then only delay until content if configured
        if (mimeType == null)
            return _maxRetainedContentBytes != 0 ? new UntilContentDelayedProcess(handler, request, response, callback, _maxRetainedContentBytes) : null;

        // Otherwise, delay until a known content type is fully read; or if the type is not known then until the content is available
        return switch (mimeType)
        {
            case FORM_ENCODED -> new UntilFormDelayedProcess(handler, request, response, callback, contentType);
            case MULTIPART_FORM_DATA ->
            {
                if (request.getContext().getAttribute(MultiPartConfig.class.getName()) instanceof MultiPartConfig mpc)
                    yield new UntilMultipartDelayedProcess(handler, request, response, callback, contentType, mpc);
                if (getServer().getAttribute(MultiPartConfig.class.getName()) instanceof MultiPartConfig mpc)
                    yield new UntilMultipartDelayedProcess(handler, request, response, callback, contentType, mpc);
                yield null;
            }
            // if other mimeType, then only delay until content if configured
            default ->
                _maxRetainedContentBytes != 0 ? new UntilContentDelayedProcess(handler, request, response, callback, _maxRetainedContentBytes) : null;

        };
    }

    protected abstract static class DelayedProcess
    {
        private final Handler _handler;
        private final Request _request;
        private final Response _response;
        private final Callback _callback;

        protected DelayedProcess(Handler handler, Request request, Response response, Callback callback)
        {
            _handler = Objects.requireNonNull(handler);
            _request = Objects.requireNonNull(request);
            _response = Objects.requireNonNull(response);
            _callback = Objects.requireNonNull(callback);
        }

        protected Handler getHandler()
        {
            return _handler;
        }

        protected Request getRequest()
        {
            return _request;
        }

        protected Response getResponse()
        {
            return _response;
        }

        protected Callback getCallback()
        {
            return _callback;
        }

        protected void process()
        {
            process(getRequest(), getResponse(), getCallback());
        }

        protected boolean process(Request request, Response response, Callback callback)
        {
            try
            {
                if (getHandler().handle(request, response, callback))
                    return true;

                // The handle was rejected, so write the error using the original potentially unwrapped request/response/callback
                Response.writeError(getRequest(), getResponse(), getCallback(), HttpStatus.NOT_FOUND_404);
            }
            catch (Throwable t)
            {
                // The handle failed, so write the error using the original potentially unwrapped request/response/callback
                Response.writeError(getRequest(), getResponse(), getCallback(), t);
            }
            // return false to indicate the passed request/response/callback were not used.
            return false;
        }

        protected abstract void delay() throws Exception;
    }

    /**
     * Delay dispatch until all content or an effective buffer size is reached
     */
    protected static class UntilContentDelayedProcess extends DelayedProcess implements Invocable.Task
    {
        private final Deque<Content.Chunk> _chunks = new ArrayDeque<>();
        private final long _maxSize;
        private final int _chunkOverhead;
        private long _estimatedSize;

        /**
         * @param handler The next handler
         * @param request The delayed request
         * @param response The delayed response
         * @param callback The delayed callback
         * @param maxSize The maximum size to buffer before dispatching to the next handler;
         *                or -1 to use {@link HttpConnectionFactory#getInputBufferSize()}
         */
        public UntilContentDelayedProcess(Handler handler, Request request, Response response, Callback callback, long maxSize)
        {
            this(handler, request, response, callback, maxSize, -1);
        }

        /**
         * @param handler The next handler
         * @param request The delayed request
         * @param response The delayed response
         * @param callback The delayed callback
         * @param maxSize The maximum size to buffer before dispatching to the next handler;
         *                or -1 to use {@link HttpConnectionFactory#getInputBufferSize()}
         * @param chunkOverhead The bytes to account for per chunk when calculating the size; or -1 for a default.
         */
        public UntilContentDelayedProcess(Handler handler, Request request, Response response, Callback callback, long maxSize, int chunkOverhead)
        {
            super(handler, request, response, callback);
            _maxSize = maxSize < 0 ? request.getConnectionMetaData().getConnector().getConnectionFactory(HttpConnectionFactory.class).getInputBufferSize() : maxSize;
            _chunkOverhead = chunkOverhead < 0 ? 8 : chunkOverhead;
        }

        @Override
        protected void delay()
        {
            read(false);
        }

        protected void read(boolean execute)
        {
            while (true)
            {
                Content.Chunk chunk = super.getRequest().read();
                if (chunk == null)
                {
                    getRequest().demand(this);
                    break;
                }

                // retain the chunk in the queue
                if (!_chunks.add(chunk))
                {
                    getCallback().failed(new IllegalStateException());
                    break;
                }

                // Estimated size is 8 byte framing overhead per chunk plus the chunk size
                _estimatedSize += _chunkOverhead + chunk.remaining();

                if (chunk.isLast() || _estimatedSize >= _maxSize)
                {
                    if (execute)
                        getRequest().getContext().execute(this::doProcess);
                    else
                        doProcess();
                    break;
                }
            }
        }

        @Override
        public InvocationType getInvocationType()
        {
            return InvocationType.NON_BLOCKING;
        }

        /**
         * This is run when enough content has been received to dispatch to the next handler.
         */
        public void run()
        {
            read(true);
        }

        private void doProcess()
        {
            RewindChunksRequest request = new RewindChunksRequest(getRequest(), getCallback(), _chunks);
            if (!process(request, getResponse(), request))
                request.release();
        }

        private static class RewindChunksRequest extends Request.Wrapper implements Callback
        {
            private final Deque<Content.Chunk> _chunks;
            private final Callback _callback;

            public RewindChunksRequest(Request wrapped, Callback callback, Deque<Content.Chunk> chunks)
            {
                super(wrapped);
                _chunks = chunks;
                _callback = callback;
            }

            @Override
            public Content.Chunk read()
            {
                if (_chunks.isEmpty())
                    return super.read();
                return _chunks.removeFirst();
            }

            private void release()
            {
                _chunks.forEach(Content.Chunk::release);
                _chunks.clear();
            }

            @Override
            public void fail(Throwable failure, boolean last)
            {
                release();
                _callback.failed(failure);
            }

            @Override
            public void succeeded()
            {
                release();
                _callback.succeeded();
            }
        }
    }

    protected static class UntilFormDelayedProcess extends DelayedProcess
    {
        private final Charset _charset;

        public UntilFormDelayedProcess(Handler handler, Request request, Response response, Callback callback, String contentType)
        {
            super(handler, request, response, callback);

            String cs = MimeTypes.getCharsetFromContentType(contentType);
            _charset = StringUtil.isEmpty(cs) ? StandardCharsets.UTF_8 : Charset.forName(cs);
        }

        @Override
        protected void delay()
        {
            InvocationType invocationType = getHandler().getInvocationType();
            AtomicInteger done = new AtomicInteger(2);
            var onFields = new Promise.Invocable<Fields>()
            {
                @Override
                public void failed(Throwable x)
                {
                    succeeded(null);
                }

                @Override
                public void succeeded(Fields result)
                {
                    // If the handling thread has already exited, we must process without blocking from this callback
                    if (done.decrementAndGet() == 0)
                        invocationType.runWithoutBlocking(this::doProcess, getRequest().getContext());
                }

                private void doProcess()
                {
                    process();
                }

                @Override
                public InvocationType getInvocationType()
                {
                    return invocationType;
                }
            };

            // If the fields are already available, we can process from this handling thread
            FormFields.onFields(getRequest(), _charset, onFields);
            if (done.decrementAndGet() == 0)
                process();
        }
    }

    protected static class UntilMultipartDelayedProcess extends DelayedProcess
    {
        private final String _contentType;
        private final MultiPartConfig _config;

        public UntilMultipartDelayedProcess(Handler handler, Request request, Response response, Callback callback, String contentType, MultiPartConfig config)
        {
            super(handler, request, response, callback);
            _contentType = contentType;
            _config = config;
        }

        @Override
        protected void delay()
        {
            Request request = getRequest();
            InvocationType invocationType = getHandler().getInvocationType();
            AtomicInteger done = new AtomicInteger(2);

            Promise.Invocable<MultiPartFormData.Parts> onParts = new Promise.Invocable<>()
            {
                @Override
                public void failed(Throwable x)
                {
                    succeeded(null);
                }

                @Override
                public void succeeded(MultiPartFormData.Parts result)
                {
                    // If the handling thread has already exited, we must process without blocking from this callback
                    if (done.decrementAndGet() == 0)
                        invocationType.runWithoutBlocking(this::doProcess, getRequest().getContext());
                }

                private void doProcess()
                {
                    process();
                }

                @Override
                public InvocationType getInvocationType()
                {
                    return invocationType;
                }
            };

            MultiPartFormData.onParts(request, request, _contentType, _config, onParts);

            // If the parts are already available, we can process from this handling thread
            if (done.decrementAndGet() == 0)
                process();
        }
    }
}
