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
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpHeaderValue;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.http.MultiPartConfig;
import org.eclipse.jetty.http.MultiPartFormData;
import org.eclipse.jetty.io.Content;
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
 * <p>A {@link ConditionalHandler} that can eagerly load content asynchronously before calling the
 * {@link #getHandler() next handler}.  Typically this handler is deployed before an application that uses
 * blocking IO to read the request body. By using this handler, such an application can be run in a way so that it
 * never (or seldom) blocks on request content.  This gives many of the benefits of asynchronous IO without the
 * need to write an asynchronous application.
 * </p>
 * <p>The handler uses the configured {@link FormContentLoaderFactory} instances to eagerly load specific content types.
 * By default, this handler supports eager loading of:</p>
 * <dl>
 *     <dt>{@link FormFields}</dt><dd>Loaded and parsed in full by the {@link FormContentLoaderFactory}</dd>
 *     <dt>{@link MultiPartFormData}</dt><dd>Loaded and parsed in full by the {@link MultiPartContentLoaderFactory}</dd>
 *     <dt>{@link Content.Chunk}</dt><dd>Retained by the {@link RetainedContentLoaderFactory}</dd>
 * </dl>
 */
public class EagerContentHandler extends ConditionalHandler.ElseNext
{
    private final Map<String, ContentLoaderFactory> _factoriesByMimeType = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private final ContentLoaderFactory _defaultFactory;

    /**
     * Construct an {@code EagerContentHandler} with the default {@link ContentLoaderFactory} set
     */
    public EagerContentHandler()
    {
        this((Handler)null);
    }

    /**
     * Construct an {@code EagerContentHandler} with the default {@link ContentLoaderFactory} set
     * @param handler The next handler (also can be set with {@link #setHandler(Handler)}
     */
    public EagerContentHandler(Handler handler)
    {
        this(handler, new FormContentLoaderFactory(), new MultiPartContentLoaderFactory(), new RetainedContentLoaderFactory());
    }

    /**
     * Construct an {@code EagerContentHandler} with the specific {@link ContentLoaderFactory} instances
     * @param factories The {@link ContentLoaderFactory} instances used to eagerly load content.
     */
    public EagerContentHandler(ContentLoaderFactory... factories)
    {
        this(null, factories);
    }

    /**
     * Construct an {@code EagerContentHandler} with the specific {@link ContentLoaderFactory} instances
     * @param handler The next handler (also can be set with {@link #setHandler(Handler)}
     * @param factories The {@link ContentLoaderFactory} instances used to eagerly load content.
     */
    public EagerContentHandler(Handler handler, ContentLoaderFactory... factories)
    {
        super(handler);
        ContentLoaderFactory dft = null;
        for (ContentLoaderFactory factory : factories)
        {
            installBean(factory);
            if (factory.getApplicableMimeType() == null)
                dft = factory;
            else
                _factoriesByMimeType.put(factory.getApplicableMimeType(), factory);
        }
        _defaultFactory = dft;
    }

    @Override
    protected boolean onConditionsMet(Request request, Response response, Callback callback) throws Exception
    {
        Handler next = getHandler();
        if (next == null)
            return false;

        boolean contentExpected = false;
        String contentType = null;
        String mimeType = null;
        loop:
        for (HttpField field : request.getHeaders())
        {
            HttpHeader header = field.getHeader();
            if (header == null)
                continue;
            switch (header)
            {
                case CONTENT_TYPE:
                    contentType = field.getValue();
                    mimeType = MimeTypes.getMimeTypeAsStringFromContentType(field);
                    break;

                case CONTENT_LENGTH:
                    contentExpected = field.getLongValue() > 0;
                    break;

                case TRANSFER_ENCODING:
                    contentExpected = field.contains(HttpHeaderValue.CHUNKED.asString());
                    break;

                default:
                    break;
            }
        }

        if (!contentExpected)
            return next.handle(request, response, callback);

        ContentLoaderFactory factory = mimeType == null ? null :  _factoriesByMimeType.get(mimeType);
        if (factory == null)
            factory = _defaultFactory;
        if (factory == null)
            return next.handle(request, response, callback);

        ContentLoader contentLoader = factory.newContentLoader(contentType, mimeType, next, request, response, callback);
        if (contentLoader == null)
            return next.handle(request, response, callback);

        contentLoader.load();
        return true;
    }

    /**
     * A factory to create new {@link ContentLoader} instances for a specific mime type.
     */
    public interface ContentLoaderFactory
    {
        /**
         * @return The mimetype for which this factory is applicable to; or {@code null} if applicable to all types.
         */
        String getApplicableMimeType();

        /**
         * @param contentType The content type of the request
         * @param mimeType The mime type extracted from the request
         * @param handler The next handler to call
         * @param request The request
         * @param response The response
         * @param callback The callback
         * @return An {@link ContentLoader} instance if the content can be loaded eagerly, else {@code null}.
         */
        ContentLoader newContentLoader(String contentType, String mimeType, Handler handler, Request request, Response response, Callback callback);
    }

    /**
     * An eager content processor, created by a {@link ContentLoaderFactory} to asynchronous load content from a {@link Request}
     * before calling the {@link Handler#handle(Request, Response, Callback)} method of the passed {@link Handler}.
     */
    public abstract static class ContentLoader
    {
        private final Handler _handler;
        private final Request _request;
        private final Response _response;
        private final Callback _callback;

        protected ContentLoader(Handler handler, Request request, Response response, Callback callback)
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

        protected void handle()
        {
            handle(getRequest(), getResponse(), getCallback());
        }

        protected void handle(Request request, Response response, Callback callback)
        {
            try
            {
                if (getHandler().handle(request, response, callback))
                    return;

                // The handle was rejected, so write the error using the original potentially unwrapped request/response/callback
                Response.writeError(request, response, callback, HttpStatus.NOT_FOUND_404);
            }
            catch (Throwable t)
            {
                // The handle failed, so write the error using the original potentially unwrapped request/response/callback
                Response.writeError(request, response, callback, t);
            }
        }

        /**
         * Called to initiate eager loading of the content.  The content may be loaded within the scope
         * of this method, or within the scope of a callback as a result of a {@link Request#demand(Runnable)} call made by
         * this methhod.
         * @throws Exception If there is a problem
         */
        protected abstract void load() throws Exception;
    }

    /**
     * An {@link ContentLoaderFactory} for {@link MimeTypes.Type#FORM_ENCODED} content, that uses
     * {@link FormFields#onFields(Request, Charset, int, int, Promise.Invocable)} to asynchronously load and parse the content.
     */
    public static class FormContentLoaderFactory implements ContentLoaderFactory
    {
        private final int _maxFields;
        private final int _maxLength;

        public FormContentLoaderFactory()
        {
            this(-1, -1);
        }

        /**
         * @param maxFields The maximum number of fields to be eagerly loaded;
         *                  or -1 to use the default of {@link FormFields#onFields(Request, Charset, int, int, Promise.Invocable)}
         * @param maxLength The maximum length of all combined fields to be eagerly loaded;
         *                  or -1 to use the default of {@link FormFields#onFields(Request, Charset, int, int, Promise.Invocable)}
         */
        public FormContentLoaderFactory(int maxFields, int maxLength)
        {
            _maxFields = maxFields;
            _maxLength = maxLength;
        }

        @Override
        public String getApplicableMimeType()
        {
            return MimeTypes.Type.FORM_ENCODED.asString();
        }

        @Override
        public ContentLoader newContentLoader(String contentType, String mimeType, Handler handler, Request request, Response response, Callback callback)
        {
            String cs = MimeTypes.getCharsetFromContentType(contentType);
            Charset charset = StringUtil.isEmpty(cs) ? StandardCharsets.UTF_8 : Charset.forName(cs);

            return new ContentLoader(handler, request, response, callback)
            {
                @Override
                protected void load()
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
                            handle();
                        }

                        @Override
                        public InvocationType getInvocationType()
                        {
                            return invocationType;
                        }
                    };

                    // If the fields are already available, we can process from this handling thread
                    FormFields.onFields(getRequest(), charset, _maxFields, _maxLength, onFields);
                    if (done.decrementAndGet() == 0)
                        handle();
                }
            };
        }
    }

    /**
     * An {@link ContentLoaderFactory} for {@link MimeTypes.Type#MULTIPART_FORM_DATA} content, that uses
     * {@link MultiPartFormData#onParts(Content.Source, Attributes, String, MultiPartConfig, Promise.Invocable)}
     * to asynchronously load and parse the content.
     */
    public static class MultiPartContentLoaderFactory implements ContentLoaderFactory
    {
        private final MultiPartConfig _multiPartConfig;

        public MultiPartContentLoaderFactory()
        {
            this(null);
        }

        /**
         * @param multiPartConfig The {@link MultiPartConfig} to use for eagerly loading content;
         *                        or {@code null} to look for a {@link MultiPartConfig} as a
         *                        {@link org.eclipse.jetty.server.Context} or {@link org.eclipse.jetty.server.Server}
         *                        {@link Attributes attribute}, using the class name as the attribute name.
         */
        public MultiPartContentLoaderFactory(MultiPartConfig multiPartConfig)
        {
            _multiPartConfig = multiPartConfig;
        }

        @Override
        public String getApplicableMimeType()
        {
            return MimeTypes.Type.MULTIPART_FORM_DATA.asString();
        }

        @Override
        public ContentLoader newContentLoader(String contentType, String mimeType, Handler handler, Request request, Response response, Callback callback)
        {
            MultiPartConfig config = _multiPartConfig;
            if (config == null && request.getContext().getAttribute(MultiPartConfig.class.getName()) instanceof MultiPartConfig mpc)
                config = mpc;
            if (config == null && handler.getServer().getAttribute(MultiPartConfig.class.getName()) instanceof MultiPartConfig mpc)
                config = mpc;
            if (config == null)
                return null;

            MultiPartConfig multiPartConfig = config;

            return new ContentLoader(handler, request, response, callback)
            {
                @Override
                protected void load()
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
                            handle();
                        }

                        @Override
                        public InvocationType getInvocationType()
                        {
                            return invocationType;
                        }
                    };

                    MultiPartFormData.onParts(request, request, contentType, multiPartConfig, onParts);

                    // If the parts are already available, we can process from this handling thread
                    if (done.decrementAndGet() == 0)
                        handle();
                }
            };
        }
    }

    /**
     * An {@link ContentLoaderFactory} for any content, that uses {@link Content.Chunk#retain()} to
     * eagerly load content with zero copies, until all content is read or a maximum size is exceeded.
     */
    public static class RetainedContentLoaderFactory implements ContentLoaderFactory
    {
        private final long _maxRetainedBytes;
        private final int _framingOverhead;
        private final boolean _reject;

        public RetainedContentLoaderFactory()
        {
            this(-1, -1, true);
        }

        /**
         * @param maxRetainedBytes the maximum number bytes to retain whilst eagerly loading, which
         *                         includes the content bytes and any {@code framingOverhead} per chunk;
         *                         or -1 for a heuristically determined value that will not increase memory commitment.
         * @param framingOverhead the number of bytes to include in the estimated size per {@link Content.Chunk} to allow
         *                      for framing overheads in the transport. Since the content is retained rather than copied, any
         *                      framing data is also retained in the IO buffer.
         * @param reject if {@code true}, then if {@code maxRetainBytes} is exceeded, the request is rejected with a
         *               {@link HttpStatus#PAYLOAD_TOO_LARGE_413} response.
         */
        public RetainedContentLoaderFactory(long maxRetainedBytes, int framingOverhead, boolean reject)
        {
            _maxRetainedBytes = maxRetainedBytes;
            _framingOverhead = framingOverhead;
            _reject = reject;
        }

        @Override
        public String getApplicableMimeType()
        {
            return null;
        }

        @Override
        public ContentLoader newContentLoader(String contentType, String mimeType, Handler handler, Request request, Response response, Callback callback)
        {
            return new RetainedContentLoader(handler, request, response, callback, _maxRetainedBytes, _framingOverhead, _reject);
        }

        /**
         * Delay dispatch until all content or an effective buffer size is reached
         */
        public static class RetainedContentLoader extends ContentLoader implements Invocable.Task
        {
            private final Deque<Content.Chunk> _chunks = new ArrayDeque<>();
            private final long _maxRetainedBytes;
            private final int _framingOverhead;
            private final boolean _rejectWhenExceeded;
            private long _estimatedSize;

            /**
             * @param handler The next handler
             * @param request The delayed request
             * @param response The delayed response
             * @param callback The delayed callback
             * @param maxRetainedBytes The maximum size to buffer before dispatching to the next handler;
             *                or -1 for a heuristically determined default
             * @param framingOverhead The bytes to account for per chunk when calculating the size; or -1 for a heuristic.
             * @param rejectWhenExceeded If {@code true} then requests are rejected if the content is not complete before maxRetainedBytes.
             */
            public RetainedContentLoader(Handler handler, Request request, Response response, Callback callback, long maxRetainedBytes, int framingOverhead, boolean rejectWhenExceeded)
            {
                super(handler, request, response, callback);
                _maxRetainedBytes = maxRetainedBytes < 0
                    ? Math.max(1, request.getConnectionMetaData().getConnector().getConnectionFactory(HttpConnectionFactory.class).getInputBufferSize() - 1500)
                    : maxRetainedBytes;
                _framingOverhead = framingOverhead < 0
                    ? (request.getConnectionMetaData().getHttpVersion().getVersion() <= HttpVersion.HTTP_1_1.getVersion() ? 8 : 9)
                    : framingOverhead;
                _rejectWhenExceeded = rejectWhenExceeded;
            }

            @Override
            protected void load()
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
                    _estimatedSize += _framingOverhead + chunk.remaining();

                    boolean oversize = _estimatedSize >= _maxRetainedBytes;

                    if (_rejectWhenExceeded && oversize && !chunk.isLast())
                    {
                        Response.writeError(getRequest(), getResponse(), getCallback(), HttpStatus.PAYLOAD_TOO_LARGE_413);
                        break;
                    }

                    if (chunk.isLast() || oversize)
                    {
                        if (execute)
                            getRequest().getContext().execute(this::doHandle);
                        else
                            doHandle();
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

            private void doHandle()
            {
                RewindChunksRequest request = new RewindChunksRequest(getRequest(), getCallback(), _chunks);
                handle(request, getResponse(), request);
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
                public InvocationType getInvocationType()
                {
                    return _callback.getInvocationType();
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
                public void succeeded()
                {
                    release();
                    _callback.succeeded();
                }

                @Override
                public void fail(Throwable failure)
                {
                    release();
                    _callback.failed(failure);
                }
            }
        }
    }
}
