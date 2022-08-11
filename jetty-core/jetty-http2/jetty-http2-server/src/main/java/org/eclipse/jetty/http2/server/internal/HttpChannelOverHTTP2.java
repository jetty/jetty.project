//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.http2.server.internal;

public class HttpChannelOverHTTP2
{
//
//    extends HttpChannel implements Closeable, WriteFlusher.Listener, HTTP2Channel.Server
//{
//    private static final Logger LOG = LoggerFactory.getLogger(HttpChannelOverHTTP2.class);
//    private static final HttpField SERVER_VERSION = new PreEncodedHttpField(HttpHeader.SERVER, HttpConfiguration.SERVER_VERSION);
//    private static final HttpField POWERED_BY = new PreEncodedHttpField(HttpHeader.X_POWERED_BY, HttpConfiguration.SERVER_VERSION);
//
//    private boolean _expect100Continue;
//    private boolean _delayedUntilContent;
//    private boolean _useOutputDirectByteBuffers;
//    private final ContentDemander _contentDemander;
//
//    public HttpChannelOverHTTP2(Connector connector, HttpConfiguration configuration, EndPoint endPoint, HttpTransportOverHTTP2 transport)
//    {
//        super(connector, configuration, endPoint, transport);
//        _contentDemander = new ContentDemander();
//    }
//
//    protected IStream getStream()
//    {
//        return getHttpTransport().getStream();
//    }
//
//    @Override
//    public boolean isUseOutputDirectByteBuffers()
//    {
//        return _useOutputDirectByteBuffers;
//    }
//
//    public void setUseOutputDirectByteBuffers(boolean useOutputDirectByteBuffers)
//    {
//        _useOutputDirectByteBuffers = useOutputDirectByteBuffers;
//    }
//
//    @Override
//    public boolean isExpecting100Continue()
//    {
//        return _expect100Continue;
//    }
//
//    @Override
//    public void setIdleTimeout(long timeoutMs)
//    {
//        getStream().setIdleTimeout(timeoutMs);
//    }
//
//    @Override
//    public long getIdleTimeout()
//    {
//        return getStream().getIdleTimeout();
//    }
//
//    @Override
//    public void onFlushed(long bytes) throws IOException
//    {
//        getResponse().getHttpOutput().onFlushed(bytes);
//    }
//
//    public Runnable onRequest(HeadersFrame frame)
//    {
//        try
//        {
//            MetaData.Request request = (MetaData.Request)frame.getMetaData();
//            HttpFields fields = request.getFields();
//
//            _expect100Continue = fields.contains(HttpHeader.EXPECT, HttpHeaderValue.CONTINUE.asString());
//
//            HttpFields.Mutable response = getResponse().getHttpFields();
//            if (getHttpConfiguration().getSendServerVersion())
//                response.add(SERVER_VERSION);
//            if (getHttpConfiguration().getSendXPoweredBy())
//                response.add(POWERED_BY);
//
//            onRequest(request);
//
//            boolean endStream = frame.isEndStream();
//            if (endStream)
//            {
//                onContentComplete();
//                onRequestComplete();
//            }
//
//            boolean connect = request instanceof MetaData.ConnectRequest;
//            _delayedUntilContent = getHttpConfiguration().isDelayDispatchUntilContent() &&
//                    !endStream && !_expect100Continue && !connect;
//
//            // Delay the demand of DATA frames for CONNECT with :protocol
//            // or for normal requests expecting 100 continue.
//            if (connect)
//            {
//                if (request.getProtocol() == null)
//                    _contentDemander.demand(false);
//            }
//            else
//            {
//                if (_delayedUntilContent)
//                    _contentDemander.demand(false);
//            }
//
//            if (LOG.isDebugEnabled())
//            {
//                Stream stream = getStream();
//                LOG.debug("HTTP2 Request #{}/{}, delayed={}:{}{} {} {}{}{}",
//                        stream.getId(), Integer.toHexString(stream.getSession().hashCode()),
//                        _delayedUntilContent, System.lineSeparator(),
//                        request.getMethod(), request.getURI(), request.getHttpVersion(),
//                        System.lineSeparator(), fields);
//            }
//
//            return _delayedUntilContent ? null : this;
//        }
//        catch (BadMessageException x)
//        {
//            if (LOG.isDebugEnabled())
//                LOG.debug("onRequest", x);
//            onBadMessage(x);
//            return null;
//        }
//        catch (Throwable x)
//        {
//            onBadMessage(new BadMessageException(HttpStatus.INTERNAL_SERVER_ERROR_500, null, x));
//            return null;
//        }
//    }
//
//    public Runnable onPushRequest(MetaData.Request request)
//    {
//        try
//        {
//            onRequest(request);
//            getRequest().setAttribute("org.eclipse.jetty.pushed", Boolean.TRUE);
//            onContentComplete();
//            onRequestComplete();
//
//            if (LOG.isDebugEnabled())
//            {
//                Stream stream = getStream();
//                LOG.debug("HTTP2 PUSH Request #{}/{}:{}{} {} {}{}{}",
//                        stream.getId(), Integer.toHexString(stream.getSession().hashCode()), System.lineSeparator(),
//                        request.getMethod(), request.getURI(), request.getHttpVersion(),
//                        System.lineSeparator(), request.getFields());
//            }
//
//            return this;
//        }
//        catch (BadMessageException x)
//        {
//            onBadMessage(x);
//            return null;
//        }
//        catch (Throwable x)
//        {
//            onBadMessage(new BadMessageException(HttpStatus.INTERNAL_SERVER_ERROR_500, null, x));
//            return null;
//        }
//    }
//
//    @Override
//    public HttpTransportOverHTTP2 getHttpTransport()
//    {
//        return (HttpTransportOverHTTP2)super.getHttpTransport();
//    }
//
//    @Override
//    public void recycle()
//    {
//        super.recycle();
//        getHttpTransport().recycle();
//        _expect100Continue = false;
//        _delayedUntilContent = false;
//        // The content demander must be the very last thing to be recycled
//        // to make sure any pending demanding content gets cleared off.
//        _contentDemander.recycle();
//    }
//
//    @Override
//    protected void commit(MetaData.Response info)
//    {
//        super.commit(info);
//        if (LOG.isDebugEnabled())
//        {
//            Stream stream = getStream();
//            LOG.debug("HTTP2 Commit Response #{}/{}:{}{} {} {}{}{}",
//                    stream.getId(), Integer.toHexString(stream.getSession().hashCode()), System.lineSeparator(), info.getHttpVersion(), info.getStatus(), info.getReason(),
//                    System.lineSeparator(), info.getFields());
//        }
//    }
//
//    @Override
//    public Runnable onData(DataFrame frame, Callback callback)
//    {
//        ByteBuffer buffer = frame.getData();
//        int length = buffer.remaining();
//        HttpInput.Content content = new HttpInput.Content(buffer)
//        {
//            @Override
//            public boolean isEof()
//            {
//                return frame.isEndStream();
//            }
//
//            @Override
//            public void succeeded()
//            {
//                callback.succeeded();
//            }
//
//            @Override
//            public void failed(Throwable x)
//            {
//                callback.failed(x);
//            }
//
//            @Override
//            public InvocationType getInvocationType()
//            {
//                return callback.getInvocationType();
//            }
//        };
//        boolean needed = _contentDemander.onContent(content);
//        boolean handle = onContent(content);
//
//        boolean endStream = frame.isEndStream();
//        if (endStream)
//        {
//            boolean handleContent = onContentComplete();
//            // This will generate EOF -> must happen before onContentProducible.
//            boolean handleRequest = onRequestComplete();
//            handle |= handleContent | handleRequest;
//        }
//
//        boolean woken = needed && getRequest().getHttpInput().onContentProducible();
//        handle |= woken;
//        if (LOG.isDebugEnabled())
//        {
//            Stream stream = getStream();
//            LOG.debug("HTTP2 Request #{}/{}: {} bytes of {} content, woken: {}, needed: {}, handle: {}",
//                    stream.getId(),
//                    Integer.toHexString(stream.getSession().hashCode()),
//                    length,
//                    endStream ? "last" : "some",
//                    woken,
//                    needed,
//                    handle);
//        }
//
//        boolean wasDelayed = _delayedUntilContent;
//        _delayedUntilContent = false;
//        return handle || wasDelayed ? this : null;
//    }
//
//    /**
//     * Demanding content is a marker content that is used to remember that a demand was
//     * registered into the stream. The {@code needed} flag indicates if the demand originated
//     * from a call to {@link #produceContent()} when false or {@link #needContent()}
//     * when true, as {@link HttpInput#onContentProducible()} must only be called
//     * only when {@link #needContent()} was called.
//     * Instances of this class must never escape the scope of this channel impl,
//     * so {@link #produceContent()} must never return one.
//     */
//    private static final class DemandingContent extends HttpInput.SpecialContent
//    {
//        private final boolean needed;
//
//        private DemandingContent(boolean needed)
//        {
//            this.needed = needed;
//        }
//    }
//
//    private static final HttpInput.Content EOF = new HttpInput.EofContent();
//    private static final HttpInput.Content DEMANDING_NEEDED = new DemandingContent(true);
//    private static final HttpInput.Content DEMANDING_NOT_NEEDED = new DemandingContent(false);
//
//    private class ContentDemander
//    {
//        private final AtomicReference<HttpInput.Content> _content = new AtomicReference<>();
//
//        public void recycle()
//        {
//            if (LOG.isDebugEnabled())
//                LOG.debug("recycle {}", this);
//            HttpInput.Content c = _content.getAndSet(null);
//            if (c != null && !c.isSpecial())
//                throw new AssertionError("unconsumed content: " + c);
//        }
//
//        public HttpInput.Content poll()
//        {
//            while (true)
//            {
//                HttpInput.Content c = _content.get();
//                if (LOG.isDebugEnabled())
//                    LOG.debug("poll, content = {}", c);
//                if (c == null || c.isSpecial() || _content.compareAndSet(c, c.isEof() ? EOF : null))
//                {
//                    if (LOG.isDebugEnabled())
//                        LOG.debug("returning current content");
//                    return c;
//                }
//            }
//        }
//
//        public boolean demand(boolean needed)
//        {
//            while (true)
//            {
//                HttpInput.Content c = _content.get();
//                if (LOG.isDebugEnabled())
//                    LOG.debug("demand({}), content = {}", needed, c);
//                if (c instanceof DemandingContent)
//                {
//                    if (needed && !((DemandingContent)c).needed)
//                    {
//                        if (!_content.compareAndSet(c, DEMANDING_NEEDED))
//                        {
//                            if (LOG.isDebugEnabled())
//                                LOG.debug("already demanding but switched needed flag to true");
//                            continue;
//                        }
//                    }
//                    if (LOG.isDebugEnabled())
//                        LOG.debug("already demanding, returning false");
//                    return false;
//                }
//                if (c != null)
//                {
//                    if (LOG.isDebugEnabled())
//                        LOG.debug("content available, returning true");
//                    return true;
//                }
//                if (_content.compareAndSet(null, needed ? DEMANDING_NEEDED : DEMANDING_NOT_NEEDED))
//                {
//                    IStream stream = getStream();
//                    if (stream == null)
//                    {
//                        _content.set(null);
//                        if (LOG.isDebugEnabled())
//                            LOG.debug("no content available, switched to demanding but stream is now null");
//                        return false;
//                    }
//                    if (LOG.isDebugEnabled())
//                        LOG.debug("no content available, demanding stream {}", stream);
//                    stream.demand(1);
//                    c = _content.get();
//                    boolean hasContent = !(c instanceof DemandingContent) && c != null;
//                    if (LOG.isDebugEnabled())
//                        LOG.debug("has content now? {}", hasContent);
//                    return hasContent;
//                }
//            }
//        }
//
//        public boolean onContent(HttpInput.Content content)
//        {
//            while (true)
//            {
//                HttpInput.Content c = _content.get();
//                if (LOG.isDebugEnabled())
//                    LOG.debug("content delivered by stream: {}, current content: {}", content, c);
//                if (c instanceof DemandingContent)
//                {
//                    if (_content.compareAndSet(c, content))
//                    {
//                        boolean needed = ((DemandingContent)c).needed;
//                        if (LOG.isDebugEnabled())
//                            LOG.debug("replacing demand content with {} succeeded; returning {}", content, needed);
//                        return needed;
//                    }
//                }
//                else if (c == null)
//                {
//                    if (!content.isSpecial())
//                    {
//                        // This should never happen, consider as a bug.
//                        content.failed(new IllegalStateException("Non special content without demand : " + content));
//                        return false;
//                    }
//                    if (_content.compareAndSet(null, content))
//                    {
//                        if (LOG.isDebugEnabled())
//                            LOG.debug("replacing null content with {} succeeded", content);
//                        return false;
//                    }
//                }
//                else if (c.isEof() && content.isEof() && content.isEmpty())
//                {
//                    content.succeeded();
//                    return true;
//                }
//                else if (content.getError() != null)
//                {
//                    if (c.getError() != null)
//                    {
//                        if (c.getError() != content.getError())
//                            c.getError().addSuppressed(content.getError());
//                        return true;
//                    }
//                    if (_content.compareAndSet(c, content))
//                    {
//                        c.failed(content.getError());
//                        if (LOG.isDebugEnabled())
//                            LOG.debug("replacing current content with {} succeeded", content);
//                        return true;
//                    }
//                }
//                else if (c.getError() != null && content.remaining() == 0)
//                {
//                    content.succeeded();
//                    return true;
//                }
//                else
//                {
//                    // This should never happen, consider as a bug.
//                    content.failed(new IllegalStateException("Cannot overwrite exiting content " + c + " with " + content));
//                    return false;
//                }
//            }
//        }
//
//        public boolean onTimeout(Throwable failure)
//        {
//            while (true)
//            {
//                HttpInput.Content c = _content.get();
//                if (LOG.isDebugEnabled())
//                    LOG.debug("onTimeout with current content: {} and failure = {}", c, failure);
//                if (!(c instanceof DemandingContent))
//                    return false;
//                if (_content.compareAndSet(c, new HttpInput.ErrorContent(failure)))
//                {
//                    if (LOG.isDebugEnabled())
//                        LOG.debug("replacing current content with error succeeded");
//                    return true;
//                }
//            }
//        }
//
//        public void eof()
//        {
//            while (true)
//            {
//                HttpInput.Content c = _content.get();
//                if (LOG.isDebugEnabled())
//                    LOG.debug("eof with current content: {}", c);
//                if (c instanceof DemandingContent)
//                {
//                    if (_content.compareAndSet(c, EOF))
//                    {
//                        if (LOG.isDebugEnabled())
//                            LOG.debug("replacing current content with special EOF succeeded");
//                        return;
//                    }
//                }
//                else if (c == null)
//                {
//                    if (_content.compareAndSet(null, EOF))
//                    {
//                        if (LOG.isDebugEnabled())
//                            LOG.debug("replacing null content with special EOF succeeded");
//                        return;
//                    }
//                }
//                else if (c.isEof())
//                {
//                    if (LOG.isDebugEnabled())
//                        LOG.debug("current content already is EOF");
//                    return;
//                }
//                else if (c.remaining() == 0)
//                {
//                    if (_content.compareAndSet(c, EOF))
//                    {
//                        if (LOG.isDebugEnabled())
//                            LOG.debug("replacing current content with special EOF succeeded");
//                        return;
//                    }
//                }
//                else
//                {
//                    // EOF may arrive with HEADERS frame (e.g. a trailer) that is not flow controlled, so we need to wrap the existing content.
//                    // Covered by HttpTrailersTest.testRequestTrailersWithContent.
//                    HttpInput.Content content = new HttpInput.WrappingContent(c, true);
//                    if (_content.compareAndSet(c, content))
//                    {
//                        if (LOG.isDebugEnabled())
//                            LOG.debug("replacing current content with {} succeeded", content);
//                        return;
//                    }
//                }
//            }
//        }
//
//        public boolean failContent(Throwable failure)
//        {
//            while (true)
//            {
//                HttpInput.Content c = _content.get();
//                if (LOG.isDebugEnabled())
//                    LOG.debug("failing current content {} with {} {}", c, failure, this);
//                if (c == null)
//                    return false;
//                if (c.isSpecial())
//                    return c.isEof();
//                if (_content.compareAndSet(c, null))
//                {
//                    c.failed(failure);
//                    if (LOG.isDebugEnabled())
//                        LOG.debug("replacing current content with null succeeded");
//                    return false;
//                }
//            }
//        }
//
//        @Override
//        public String toString()
//        {
//            return getClass().getSimpleName() + "@" + hashCode() + " _content=" + _content;
//        }
//    }
//
//    @Override
//    public boolean needContent()
//    {
//        boolean hasContent = _contentDemander.demand(true);
//        if (LOG.isDebugEnabled())
//            LOG.debug("needContent has content? {}", hasContent);
//        return hasContent;
//    }
//
//    @Override
//    public HttpInput.Content produceContent()
//    {
//        HttpInput.Content content = null;
//        if (_contentDemander.demand(false))
//            content = _contentDemander.poll();
//        if (LOG.isDebugEnabled())
//            LOG.debug("produceContent produced {}", content);
//        return content;
//    }
//
//    @Override
//    public boolean failAllContent(Throwable failure)
//    {
//        if (LOG.isDebugEnabled())
//            LOG.debug("failing all content with {} {}", failure, this);
//        IStream stream = getStream();
//        boolean atEof = stream == null || stream.failAllData(failure);
//        atEof |= _contentDemander.failContent(failure);
//        if (LOG.isDebugEnabled())
//            LOG.debug("failed all content, reached EOF? {}", atEof);
//        return atEof;
//    }
//
//    @Override
//    public boolean failed(Throwable x)
//    {
//        if (LOG.isDebugEnabled())
//            LOG.debug("failed " + x);
//
//        _contentDemander.onContent(new HttpInput.ErrorContent(x));
//
//        return getRequest().getHttpInput().onContentProducible();
//    }
//
//    @Override
//    protected boolean eof()
//    {
//        _contentDemander.eof();
//        return false;
//    }
//
//    @Override
//    public Runnable onTrailer(HeadersFrame frame)
//    {
//        HttpFields trailers = frame.getMetaData().getFields();
//        if (trailers.size() > 0)
//            onTrailers(trailers);
//
//        if (LOG.isDebugEnabled())
//        {
//            Stream stream = getStream();
//            LOG.debug("HTTP2 Request #{}/{}, trailers:{}{}",
//                    stream.getId(), Integer.toHexString(stream.getSession().hashCode()),
//                    System.lineSeparator(), trailers);
//        }
//
//        // This will generate EOF -> need to call onContentProducible.
//        boolean handle = onRequestComplete();
//        boolean woken = getRequest().getHttpInput().onContentProducible();
//        handle |= woken;
//
//        boolean wasDelayed = _delayedUntilContent;
//        _delayedUntilContent = false;
//        return handle || wasDelayed ? this : null;
//    }
//
//    @Override
//    public boolean isIdle()
//    {
//        return getState().isIdle();
//    }
//
//    @Override
//    public boolean onTimeout(Throwable failure, Consumer<Runnable> consumer)
//    {
//        final boolean delayed = _delayedUntilContent;
//        _delayedUntilContent = false;
//
//        boolean reset = isIdle();
//        if (reset)
//            consumeInput();
//
//        getHttpTransport().onStreamTimeout(failure);
//
//        failure.addSuppressed(new Throwable("HttpInput idle timeout"));
//        _contentDemander.onTimeout(failure);
//        boolean needed = getRequest().getHttpInput().onContentProducible();
//
//        if (needed || delayed)
//        {
//            consumer.accept(this::handleWithContext);
//            reset = false;
//        }
//
//        return reset;
//    }
//
//    @Override
//    public Runnable onFailure(Throwable failure, Callback callback)
//    {
//        getHttpTransport().onStreamFailure(failure);
//        boolean handle = failed(failure);
//        consumeInput();
//        return new FailureTask(failure, callback, handle);
//    }
//
//    protected void consumeInput()
//    {
//        getRequest().getHttpInput().consumeAll();
//    }
//
//    private void handleWithContext()
//    {
//        ContextHandler context = getState().getContextHandler();
//        if (context != null)
//            context.handle(getRequest(), this);
//        else
//            handle();
//    }
//
//    /**
//     * If the associated response has the Expect header set to 100 Continue,
//     * then accessing the input stream indicates that the handler/servlet
//     * is ready for the request body and thus a 100 Continue response is sent.
//     *
//     * @throws IOException if the InputStream cannot be created
//     */
//    @Override
//    public void continue100(int available) throws IOException
//    {
//        // If the client is expecting 100 CONTINUE, then send it now.
//        // TODO: consider using an AtomicBoolean ?
//        if (isExpecting100Continue())
//        {
//            _expect100Continue = false;
//
//            // is content missing?
//            if (available == 0)
//            {
//                if (getResponse().isCommitted())
//                    throw new IOException("Committed before 100 Continues");
//
//                boolean committed = sendResponse(HttpGenerator.CONTINUE_100_INFO, null, false);
//                if (!committed)
//                    throw new IOException("Concurrent commit while trying to send 100-Continue");
//            }
//        }
//    }
//
//    @Override
//    public boolean isTunnellingSupported()
//    {
//        return true;
//    }
//
//    @Override
//    public EndPoint getTunnellingEndPoint()
//    {
//        return new ServerHTTP2StreamEndPoint(getStream());
//    }
//
//    @Override
//    public void close()
//    {
//        abort(new IOException("Unexpected close"));
//    }
//
//    @Override
//    public String toString()
//    {
//        IStream stream = getStream();
//        long streamId = stream == null ? -1 : stream.getId();
//        return String.format("%s#%d", super.toString(), streamId);
//    }
//
//    private class FailureTask implements Runnable
//    {
//        private final Throwable failure;
//        private final Callback callback;
//        private final boolean handle;
//
//        public FailureTask(Throwable failure, Callback callback, boolean handle)
//        {
//            this.failure = failure;
//            this.callback = callback;
//            this.handle = handle;
//        }
//
//        @Override
//        public void run()
//        {
//            try
//            {
//                if (handle)
//                    handleWithContext();
//                else if (getHttpConfiguration().isNotifyRemoteAsyncErrors())
//                    getState().asyncError(failure);
//                callback.succeeded();
//            }
//            catch (Throwable x)
//            {
//                callback.failed(x);
//            }
//        }
//
//        @Override
//        public String toString()
//        {
//            return String.format("%s@%x[%s]", getClass().getName(), hashCode(), failure);
//        }
//    }
}
