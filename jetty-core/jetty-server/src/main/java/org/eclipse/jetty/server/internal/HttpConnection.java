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

package org.eclipse.jetty.server.internal;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritePendingException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

import org.eclipse.jetty.http.BadMessageException;
import org.eclipse.jetty.http.ComplianceViolation;
import org.eclipse.jetty.http.HostPortHttpField;
import org.eclipse.jetty.http.HttpCompliance;
import org.eclipse.jetty.http.HttpException;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpGenerator;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpHeaderValue;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpParser;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http.Trailers;
import org.eclipse.jetty.http.UriCompliance;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.EofException;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.io.RuntimeIOException;
import org.eclipse.jetty.io.WriteFlusher;
import org.eclipse.jetty.io.ssl.SslConnection;
import org.eclipse.jetty.server.AbstractMetaDataConnection;
import org.eclipse.jetty.server.ConnectionFactory;
import org.eclipse.jetty.server.ConnectionMetaData;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpChannel;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpStream;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.TunnelSupport;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.HostPort;
import org.eclipse.jetty.util.IteratingCallback;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.thread.Invocable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.eclipse.jetty.http.HttpCompliance.Violation.MISMATCHED_AUTHORITY;
import static org.eclipse.jetty.http.HttpStatus.INTERNAL_SERVER_ERROR_500;

/**
 * <p>A {@link Connection} that handles the HTTP protocol.</p>
 */
public class HttpConnection extends AbstractMetaDataConnection implements Runnable, WriteFlusher.Listener, Connection.UpgradeFrom, Connection.UpgradeTo, ConnectionMetaData
{
    private static final Logger LOG = LoggerFactory.getLogger(HttpConnection.class);
    private static final HttpField PREAMBLE_UPGRADE_H2C = new HttpField(HttpHeader.UPGRADE, "h2c");
    private static final ThreadLocal<HttpConnection> __currentConnection = new ThreadLocal<>();
    private static final AtomicLong __connectionIdGenerator = new AtomicLong();

    private final TunnelSupport _tunnelSupport = new TunnelSupportOverHTTP1();
    private final AtomicLong _streamIdGenerator = new AtomicLong();
    private final long _id;
    private final HttpChannel _httpChannel;
    private final RequestHandler _requestHandler;
    private final HttpParser _parser;
    private final HttpGenerator _generator;
    private final ByteBufferPool _bufferPool;
    private final AtomicReference<HttpStreamOverHTTP1> _stream = new AtomicReference<>();
    private final Lazy _attributes = new Lazy();
    private final DemandContentCallback _demandContentCallback = new DemandContentCallback();
    private final SendCallback _sendCallback = new SendCallback();
    private final boolean _recordHttpComplianceViolations;
    private final LongAdder bytesIn = new LongAdder();
    private final LongAdder bytesOut = new LongAdder();
    private final AtomicBoolean _handling = new AtomicBoolean(false);
    private final HttpFields.Mutable _headerBuilder = HttpFields.build();
    private volatile RetainableByteBuffer _retainableByteBuffer;
    private HttpFields.Mutable _trailers;
    private Runnable _onRequest;
    private long _requests;
    // TODO why is this not on HttpConfiguration?
    private boolean _useInputDirectByteBuffers;
    private boolean _useOutputDirectByteBuffers;

    /**
     * Get the current connection that this thread is dispatched to.
     * Note that a thread may be processing a request asynchronously and
     * thus not be dispatched to the connection.
     *
     * @return the current HttpConnection or null
     * @see Request#getAttribute(String) for a more general way to access the HttpConnection
     */
    public static HttpConnection getCurrentConnection()
    {
        return __currentConnection.get();
    }

    protected static HttpConnection setCurrentConnection(HttpConnection connection)
    {
        HttpConnection last = __currentConnection.get();
        __currentConnection.set(connection);
        return last;
    }

    public HttpConnection(HttpConfiguration configuration, Connector connector, EndPoint endPoint, boolean recordComplianceViolations)
    {
        super(connector, configuration, endPoint);
        _id = __connectionIdGenerator.getAndIncrement();
        _bufferPool = connector.getByteBufferPool();
        _generator = newHttpGenerator();
        _httpChannel = newHttpChannel(connector.getServer(), configuration);
        _requestHandler = newRequestHandler();
        _parser = newHttpParser(configuration.getHttpCompliance());
        _recordHttpComplianceViolations = recordComplianceViolations;
        if (LOG.isDebugEnabled())
            LOG.debug("New HTTP Connection {}", this);
    }

    @Override
    public InvocationType getInvocationType()
    {
        return getServer().getInvocationType();
    }

    public boolean isRecordHttpComplianceViolations()
    {
        return _recordHttpComplianceViolations;
    }

    protected HttpGenerator newHttpGenerator()
    {
        return new HttpGenerator();
    }

    protected HttpParser newHttpParser(HttpCompliance compliance)
    {
        HttpParser parser = new HttpParser(_requestHandler, getHttpConfiguration().getRequestHeaderSize(), compliance);
        parser.setHeaderCacheSize(getHttpConfiguration().getHeaderCacheSize());
        parser.setHeaderCacheCaseSensitive(getHttpConfiguration().isHeaderCacheCaseSensitive());
        return parser;
    }

    protected HttpChannel newHttpChannel(Server server, HttpConfiguration configuration)
    {
        return new HttpChannelState(this);
    }

    protected HttpStreamOverHTTP1 newHttpStream(String method, String uri, HttpVersion version)
    {
        return new HttpStreamOverHTTP1(method, uri, version);
    }

    protected RequestHandler newRequestHandler()
    {
        return new RequestHandler();
    }

    public Server getServer()
    {
        return getConnector().getServer();
    }

    public HttpChannel getHttpChannel()
    {
        return _httpChannel;
    }

    public HttpParser getParser()
    {
        return _parser;
    }

    public HttpGenerator getGenerator()
    {
        return _generator;
    }

    @Override
    public String getId()
    {
        StringBuilder builder = new StringBuilder();
        builder.append(getRemoteSocketAddress()).append('@');
        try
        {
            TypeUtil.toHex(hashCode(), builder);
        }
        catch (IOException ignored)
        {
        }
        builder.append('#').append(_id);
        return builder.toString();
    }

    @Override
    public HttpVersion getHttpVersion()
    {
        HttpStreamOverHTTP1 stream = _stream.get();
        return (stream != null) ? stream._version : HttpVersion.HTTP_1_1;
    }

    @Override
    public String getProtocol()
    {
        return getHttpVersion().asString();
    }

    @Override
    public boolean isPersistent()
    {
        return _generator.isPersistent(getHttpVersion());
    }

    @Override
    public Object removeAttribute(String name)
    {
        return _attributes.removeAttribute(name);
    }

    @Override
    public Object setAttribute(String name, Object attribute)
    {
        return _attributes.setAttribute(name, attribute);
    }

    @Override
    public Object getAttribute(String name)
    {
        return _attributes.getAttribute(name);
    }

    @Override
    public Set<String> getAttributeNameSet()
    {
        return _attributes.getAttributeNameSet();
    }

    @Override
    public void clearAttributes()
    {
        _attributes.clearAttributes();
    }

    @Override
    public long getMessagesIn()
    {
        return _requests;
    }

    @Override
    public long getMessagesOut()
    {
        return _requests; // TODO not strictly correct
    }

    public boolean isUseInputDirectByteBuffers()
    {
        return _useInputDirectByteBuffers;
    }

    public void setUseInputDirectByteBuffers(boolean useInputDirectByteBuffers)
    {
        // TODO why is this not on HttpConfiguration?
        _useInputDirectByteBuffers = useInputDirectByteBuffers;
    }

    public boolean isUseOutputDirectByteBuffers()
    {
        return _useOutputDirectByteBuffers;
    }

    public void setUseOutputDirectByteBuffers(boolean useOutputDirectByteBuffers)
    {
        // TODO why is this not on HttpConfiguration?
        _useOutputDirectByteBuffers = useOutputDirectByteBuffers;
    }

    protected void onComplianceViolation(ComplianceViolation.Mode mode, ComplianceViolation violation, String details)
    {
        //TODO configure this somewhere else
        //TODO what about cookie compliance
        //TODO what about http2 & 3
        //TODO test this in core
        if (isRecordHttpComplianceViolations())
        {
            HttpStreamOverHTTP1 stream = _stream.get();
            if (stream != null)
            {
                if (stream._complianceViolations == null)
                {
                    stream._complianceViolations = new ArrayList<>();
                }
                String record = String.format("%s (see %s) in mode %s for %s in %s",
                    violation.getDescription(), violation.getURL(), mode, details, HttpConnection.this);
                stream._complianceViolations.add(record);
                if (LOG.isDebugEnabled())
                    LOG.debug(record);
            }
        }
    }

    @Override
    public ByteBuffer onUpgradeFrom()
    {
        if (!isRequestBufferEmpty())
        {
            ByteBuffer unconsumed = ByteBuffer.allocateDirect(_retainableByteBuffer.remaining());
            unconsumed.put(_retainableByteBuffer.getByteBuffer());
            unconsumed.flip();
            releaseRequestBuffer();
            return unconsumed;
        }
        return null;
    }

    @Override
    public void onUpgradeTo(ByteBuffer buffer)
    {
        BufferUtil.append(getRequestBuffer(), buffer);
    }

    @Override
    public void onFlushed(long bytes) throws IOException
    {
        // TODO is this callback still needed?   Couldn't we wrap send callback instead?
        //      Either way, the dat rate calculations from HttpOutput.onFlushed should be moved to Channel.
    }

    void releaseRequestBuffer()
    {
        if (_retainableByteBuffer != null && !_retainableByteBuffer.hasRemaining())
        {
            if (LOG.isDebugEnabled())
                LOG.debug("releaseRequestBuffer {}", this);
            if (_retainableByteBuffer.release())
                _retainableByteBuffer = null;
            else
                throw new IllegalStateException("unreleased buffer " + _retainableByteBuffer);
        }
    }

    private ByteBuffer getRequestBuffer()
    {
        if (_retainableByteBuffer == null)
            _retainableByteBuffer = _bufferPool.acquire(getInputBufferSize(), isUseInputDirectByteBuffers());
        return _retainableByteBuffer.getByteBuffer();
    }

    public boolean isRequestBufferEmpty()
    {
        return _retainableByteBuffer == null || !_retainableByteBuffer.hasRemaining();
    }

    @Override
    public void onFillable()
    {
        if (LOG.isDebugEnabled())
            LOG.debug(">>onFillable enter {} {} {}", this, _httpChannel, _retainableByteBuffer);

        HttpConnection last = setCurrentConnection(this);
        try
        {
            while (getEndPoint().isOpen())
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("onFillable fill and parse {} {} {}", this, _httpChannel, _retainableByteBuffer);

                // Fill the request buffer (if needed).
                int filled = fillRequestBuffer();
                if (filled < 0 && getEndPoint().isOutputShutdown())
                    close();

                // Parse the request buffer.
                boolean handle = parseRequestBuffer();

                // There could be a connection upgrade before handling
                // the HTTP/1.1 request, for example PRI * HTTP/2.
                // If there was a connection upgrade, the other
                // connection took over, nothing more to do here.
                if (getEndPoint().getConnection() != this)
                    break;

                // Handle channel event. This will only be true when the headers of a request have been received.
                if (handle)
                {
                    Request request = _httpChannel.getRequest();
                    if (LOG.isDebugEnabled())
                        LOG.debug("HANDLE {} {}", request, this);

                    // handle the request by running the task obtained from onRequest
                    _handling.set(true);
                    Runnable onRequest = _onRequest;
                    _onRequest = null;
                    onRequest.run();

                    // If the _handling boolean has already been CaS'd to false, then stream is completed and we are no longer
                    // handling, so the caller can continue to fill and parse more connections.  If it is still true, then some
                    // thread is still handling the request and they will need to organize more filling and parsing once complete.
                    if (_handling.compareAndSet(true, false))
                    {
                        if (LOG.isDebugEnabled())
                            LOG.debug("request !complete {} {}", request, this);
                        break;
                    }

                    // If the request is complete, but has been upgraded, then break
                    if (getEndPoint().getConnection() != this)
                    {
                        if (LOG.isDebugEnabled())
                            LOG.debug("upgraded {} -> {}", this, getEndPoint().getConnection());
                        break;
                    }
                }
                else if (filled < 0)
                {
                    getEndPoint().shutdownOutput();
                    break;
                }
                else if (_requestHandler._failure != null)
                {
                    // There was an error, don't fill more.
                    break;
                }
                else if (filled == 0)
                {
                    fillInterested();
                    break;
                }
            }
        }
        catch (Throwable x)
        {
            try
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("caught exception {} {}", this, _httpChannel, x);
                if (_retainableByteBuffer != null)
                {
                    _retainableByteBuffer.clear();
                    releaseRequestBuffer();
                }
            }
            finally
            {
                getEndPoint().close(x);
            }
        }
        finally
        {
            setCurrentConnection(last);
            if (LOG.isDebugEnabled())
                LOG.debug("<<onFillable exit {} {} {}", this, _httpChannel, _retainableByteBuffer);
        }
    }

    /**
     * Parse and fill data, looking for content.
     * We do parse first, and only fill if we're out of bytes to avoid unnecessary system calls.
     */
    void parseAndFillForContent()
    {
        // Defensive check to avoid an infinite select/wakeup/fillAndParseForContent/wait loop
        // in case the parser was mistakenly closed and the connection was not aborted.
        if (_parser.isTerminated())
        {
            _requestHandler.messageComplete();
            return;
        }

        // When fillRequestBuffer() is called, it must always be followed by a parseRequestBuffer() call otherwise this method
        // doesn't trigger EOF/earlyEOF which breaks AsyncRequestReadTest.testPartialReadThenShutdown().

        // This loop was designed by a committee and voted by a majority.
        while (_parser.inContentState())
        {
            if (parseRequestBuffer())
                break;
            // Re-check the parser state after parsing to avoid filling,
            // otherwise fillRequestBuffer() would acquire a ByteBuffer
            // that may be leaked.
            if (_parser.inContentState() && fillRequestBuffer() <= 0)
                break;
        }
    }

    private int fillRequestBuffer()
    {
        if (_retainableByteBuffer != null && _retainableByteBuffer.isRetained())
        {
            // TODO this is almost certainly wrong
            RetainableByteBuffer newBuffer = _bufferPool.acquire(getInputBufferSize(), isUseInputDirectByteBuffers());
            if (LOG.isDebugEnabled())
                LOG.debug("replace buffer {} <- {} in {}", _retainableByteBuffer, newBuffer, this);
            _retainableByteBuffer.release();
            _retainableByteBuffer = newBuffer;
        }

        if (!isRequestBufferEmpty())
            return _retainableByteBuffer.remaining();

        // Get a buffer
        // We are not in a race here for the request buffer as we have not yet received a request,
        // so there are not any possible legal threads calling #parseContent or #completed.
        ByteBuffer requestBuffer = getRequestBuffer();

        // fill
        try
        {
            int filled = getEndPoint().fill(requestBuffer);
            if (filled == 0) // Do a retry on fill 0 (optimization for SSL connections)
                filled = getEndPoint().fill(requestBuffer);

            if (LOG.isDebugEnabled())
                LOG.debug("{} filled {} {}", this, filled, _retainableByteBuffer);

            if (filled > 0)
            {
                bytesIn.add(filled);
            }
            else
            {
                if (filled < 0)
                    _parser.atEOF();
                releaseRequestBuffer();
            }

            return filled;
        }
        catch (Throwable x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Unable to fill from endpoint {}", getEndPoint(), x);
            _parser.atEOF();
            if (_retainableByteBuffer != null)
            {
                _retainableByteBuffer.clear();
                releaseRequestBuffer();
            }
            return -1;
        }
    }

    private boolean parseRequestBuffer()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("{} parse {}", this, _retainableByteBuffer);

        if (_parser.isTerminated())
            throw new RuntimeIOException("Parser is terminated");
        boolean handle = _parser.parseNext(_retainableByteBuffer == null ? BufferUtil.EMPTY_BUFFER : _retainableByteBuffer.getByteBuffer());

        if (LOG.isDebugEnabled())
            LOG.debug("{} parsed {} {}", this, handle, _parser);

        // recycle buffer ?
        if (_retainableByteBuffer != null && !_retainableByteBuffer.isRetained())
            releaseRequestBuffer();

        return handle;
    }

    private boolean upgrade(HttpStreamOverHTTP1 stream)
    {
        if (stream.upgrade())
        {
            _httpChannel.recycle();
            _parser.close();
            _generator.reset();
            return true;
        }
        else
        {
            return false;
        }
    }

    @Override
    protected void onFillInterestedFailed(Throwable cause)
    {
        _parser.close();
        super.onFillInterestedFailed(cause);
    }

    @Override
    public boolean onIdleExpired(TimeoutException timeout)
    {
        if (_httpChannel.getRequest() == null)
            return true;
        Runnable task = _httpChannel.onIdleTimeout(timeout);
        if (task != null)
            getExecutor().execute(task);
        return false; // We've handle the exception
    }

    @Override
    public void onOpen()
    {
        super.onOpen();
        if (isRequestBufferEmpty())
            fillInterested();
        else
            getExecutor().execute(this);
    }

    @Override
    public void onClose(Throwable cause)
    {
        // TODO: do we really need to do this?
        //  This event is fired really late, sendCallback should already be failed at this point.
        //  Revisit whether we still need IteratingCallback.close().
        if (cause == null)
            _sendCallback.close();
        else
            _sendCallback.abort(cause);
        super.onClose(cause);
    }

    @Override
    public void run()
    {
        onFillable();
    }

    public void asyncReadFillInterested()
    {
        getEndPoint().tryFillInterested(_demandContentCallback);
    }

    @Override
    public long getBytesIn()
    {
        return bytesIn.longValue();
    }

    @Override
    public long getBytesOut()
    {
        return bytesOut.longValue();
    }

    @Override
    public String toConnectionString()
    {
        return String.format("%s@%x[p=%s,g=%s]=>%s",
            getClass().getSimpleName(),
            hashCode(),
            _parser,
            _generator,
            _httpChannel);
    }

    private class DemandContentCallback implements Callback
    {
        @Override
        public void succeeded()
        {
            Runnable task = _httpChannel.onContentAvailable();
            if (LOG.isDebugEnabled())
                LOG.debug("demand succeeded {}", task);
            if (task != null)
                task.run();
        }

        @Override
        public void failed(Throwable x)
        {
            Runnable task = _httpChannel.onFailure(x);
            if (LOG.isDebugEnabled())
                LOG.debug("demand failed {}", task, x);
            if (task != null)
                // Execute error path as invocation type is probably wrong.
                getConnector().getExecutor().execute(task);
        }

        @Override
        public InvocationType getInvocationType()
        {
            return Invocable.getInvocationType(_httpChannel);
        }
    }

    private class SendCallback extends IteratingCallback
    {
        private MetaData.Response _info;
        private boolean _head;
        private ByteBuffer _content;
        private boolean _lastContent;
        private Callback _callback;
        private RetainableByteBuffer _header;
        private RetainableByteBuffer _chunk;
        private boolean _shutdownOut;

        private SendCallback()
        {
            super(true);
        }

        @Override
        public InvocationType getInvocationType()
        {
            return _callback.getInvocationType();
        }

        private boolean reset(MetaData.Request request, MetaData.Response response, ByteBuffer content, boolean last, Callback callback)
        {
            if (reset())
            {
                _info = response;
                _head = request != null && HttpMethod.HEAD.is(request.getMethod());
                _content = content;
                _lastContent = last;
                _callback = callback;
                _header = null;

                if (getConnector().isShutdown())
                    _generator.setPersistent(false);

                return true;
            }

            if (isClosed() && response == null && last && content == null)
            {
                callback.succeeded();
                return false;
            }

            LOG.warn("reset failed {}", this);

            if (isClosed())
                callback.failed(new EofException());
            else
                callback.failed(new WritePendingException());
            return false;
        }

        @Override
        public Action process() throws Exception
        {
            if (_callback == null)
                throw new IllegalStateException();

            boolean useDirectByteBuffers = isUseOutputDirectByteBuffers();
            while (true)
            {
                ByteBuffer headerByteBuffer = _header == null ? null : _header.getByteBuffer();
                ByteBuffer chunkByteBuffer = _chunk == null ? null : _chunk.getByteBuffer();
                HttpGenerator.Result result = _generator.generateResponse(_info, _head, headerByteBuffer, chunkByteBuffer, _content, _lastContent);
                if (LOG.isDebugEnabled())
                    LOG.debug("generate: {} for {} ({},{},{})@{}",
                        result,
                        this,
                        BufferUtil.toSummaryString(headerByteBuffer),
                        BufferUtil.toSummaryString(_content),
                        _lastContent,
                        _generator.getState());

                switch (result)
                {
                    case NEED_INFO:
                        throw new EofException("request lifecycle violation");

                    case NEED_HEADER:
                    {
                        _header = _bufferPool.acquire(Math.min(getHttpConfiguration().getResponseHeaderSize(), getHttpConfiguration().getOutputBufferSize()), useDirectByteBuffers);
                        continue;
                    }
                    case HEADER_OVERFLOW:
                    {
                        if (_header.capacity() >= getHttpConfiguration().getResponseHeaderSize())
                            throw new HttpException.RuntimeException(INTERNAL_SERVER_ERROR_500, "Response header too large");
                        releaseHeader();
                        _header = _bufferPool.acquire(getHttpConfiguration().getResponseHeaderSize(), useDirectByteBuffers);
                        continue;
                    }
                    case NEED_CHUNK:
                    {
                        _chunk = _bufferPool.acquire(HttpGenerator.CHUNK_SIZE, useDirectByteBuffers);
                        continue;
                    }
                    case NEED_CHUNK_TRAILER:
                    {
                        releaseChunk();
                        _chunk = _bufferPool.acquire(getHttpConfiguration().getResponseHeaderSize(), useDirectByteBuffers);
                        continue;
                    }
                    case FLUSH:
                    {
                        // Don't write the chunk or the content if this is a HEAD response, or any other type of response that should have no content
                        if (_head || _generator.isNoContent())
                        {
                            if (_chunk != null)
                                _chunk.clear();
                            BufferUtil.clear(_content);
                        }

                        int gatherWrite = 0;
                        long bytes = 0;
                        if (BufferUtil.hasContent(headerByteBuffer))
                        {
                            gatherWrite += 4;
                            bytes += _header.remaining();
                        }
                        if (BufferUtil.hasContent(chunkByteBuffer))
                        {
                            gatherWrite += 2;
                            bytes += _chunk.remaining();
                        }
                        if (BufferUtil.hasContent(_content))
                        {
                            gatherWrite += 1;
                            bytes += _content.remaining();
                        }
                        HttpConnection.this.bytesOut.add(bytes);
                        switch (gatherWrite)
                        {
                            case 7:
                                getEndPoint().write(this, headerByteBuffer, chunkByteBuffer, _content);
                                break;
                            case 6:
                                getEndPoint().write(this, headerByteBuffer, chunkByteBuffer);
                                break;
                            case 5:
                                getEndPoint().write(this, headerByteBuffer, _content);
                                break;
                            case 4:
                                getEndPoint().write(this, headerByteBuffer);
                                break;
                            case 3:
                                getEndPoint().write(this, chunkByteBuffer, _content);
                                break;
                            case 2:
                                getEndPoint().write(this, chunkByteBuffer);
                                break;
                            case 1:
                                getEndPoint().write(this, _content);
                                break;
                            default:
                                succeeded();
                        }

                        return Action.SCHEDULED;
                    }
                    case SHUTDOWN_OUT:
                    {
                        _shutdownOut = true;
                        continue;
                    }
                    case DONE:
                    {
                        // If this is the end of the response and the connector was shutdown after response was committed,
                        // we can't add the Connection:close header, but we are still allowed to close the connection
                        // by shutting down the output.
                        if (getConnector().isShutdown() && _generator.isEnd() && _generator.isPersistent())
                            _shutdownOut = true;

                        return Action.SUCCEEDED;
                    }
                    case CONTINUE:
                    {
                        break;
                    }
                    default:
                    {
                        throw new IllegalStateException("generateResponse=" + result);
                    }
                }
            }
        }

        private Callback release()
        {
            Callback complete = _callback;
            _callback = null;
            _info = null;
            _content = null;
            releaseHeader();
            releaseChunk();
            return complete;
        }

        private void releaseHeader()
        {
            if (_header != null)
                _header.release();
            _header = null;
        }

        private void releaseChunk()
        {
            if (_chunk != null)
                _chunk.release();
            _chunk = null;
        }

        @Override
        protected void onCompleteSuccess()
        {
            release().succeeded();
        }

        @Override
        public void onCompleteFailure(final Throwable x)
        {
            failedCallback(release(), x);
        }

        @Override
        public String toString()
        {
            return String.format("%s[i=%s,cb=%s]", super.toString(), _info, _callback);
        }
    }

    protected class RequestHandler implements HttpParser.RequestHandler, ComplianceViolation.Listener
    {
        private Throwable _failure;

        @Override
        public void startRequest(String method, String uri, HttpVersion version)
        {
            HttpStreamOverHTTP1 stream = newHttpStream(method, uri, version);
            if (!_stream.compareAndSet(null, stream))
                throw new IllegalStateException("Stream pending");
            _headerBuilder.clear();
            _httpChannel.setHttpStream(stream);
        }

        @Override
        public void parsedHeader(HttpField field)
        {
            _stream.get().parsedHeader(field);
        }

        @Override
        public boolean headerComplete()
        {
            _onRequest = _stream.get().headerComplete();
            return true;
        }

        @Override
        public boolean content(ByteBuffer buffer)
        {
            HttpStreamOverHTTP1 stream = _stream.get();
            if (stream == null || stream._chunk != null || _retainableByteBuffer == null)
                throw new IllegalStateException();

            if (LOG.isDebugEnabled())
                LOG.debug("content {}/{} for {}", BufferUtil.toDetailString(buffer), _retainableByteBuffer, HttpConnection.this);

            _retainableByteBuffer.retain();
            stream._chunk = Content.Chunk.asChunk(buffer, false, _retainableByteBuffer);
            return true;
        }

        @Override
        public boolean contentComplete()
        {
            // Do nothing at this point.
            // Wait for messageComplete so any trailers can be sent as special content
            return false;
        }

        @Override
        public void parsedTrailer(HttpField field)
        {
            if (_trailers == null)
                _trailers = HttpFields.build();
            _trailers.add(field);
        }

        @Override
        public boolean messageComplete()
        {
            HttpStreamOverHTTP1 stream = _stream.get();
            if (stream._chunk != null)
                throw new IllegalStateException();
            if (_trailers != null)
                stream._chunk = new Trailers(_trailers.asImmutable());
            else
                stream._chunk = Content.Chunk.EOF;
            return false;
        }

        @Override
        public void badMessage(HttpException failure)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("badMessage {} {}", HttpConnection.this, failure);

            _failure = (Throwable)failure;
            _generator.setPersistent(false);

            HttpStreamOverHTTP1 stream = _stream.get();
            if (stream == null)
            {
                stream = newHttpStream("GET", "/badMessage", HttpVersion.HTTP_1_0);
                _stream.set(stream);
                _httpChannel.setHttpStream(stream);
            }

            if (_httpChannel.getRequest() == null)
            {
                HttpURI uri = stream._uri;
                if (uri.hasViolations())
                    uri = HttpURI.from("/badURI");
                _httpChannel.onRequest(new MetaData.Request(_parser.getBeginNanoTime(), stream._method, uri, stream._version, HttpFields.EMPTY));
            }

            Runnable task = _httpChannel.onFailure(_failure);
            if (task != null)
                getServer().getThreadPool().execute(task);
        }

        @Override
        public void earlyEOF()
        {
            if (LOG.isDebugEnabled())
                LOG.debug("early EOF {}", HttpConnection.this);
            _generator.setPersistent(false);
            HttpStreamOverHTTP1 stream = _stream.get();
            if (stream != null)
            {
                BadMessageException bad = new BadMessageException("Early EOF");
                Content.Chunk chunk = stream._chunk;

                if (Content.Chunk.isFailure(chunk))
                {
                    if (chunk.isLast())
                    {
                        chunk.getFailure().addSuppressed(bad);
                    }
                    else
                    {
                        bad.addSuppressed(chunk.getFailure());
                        stream._chunk = Content.Chunk.from(bad);
                    }
                }
                else
                {
                    if (chunk != null)
                        chunk.release();
                    stream._chunk = Content.Chunk.from(bad);
                }

                Runnable todo = _httpChannel.onFailure(bad);
                if (todo != null)
                    getServer().getThreadPool().execute(todo);
            }
        }

        @Override
        public void onComplianceViolation(ComplianceViolation.Mode mode, ComplianceViolation violation, String details)
        {
            HttpConnection.this.onComplianceViolation(mode, violation, details);
        }
    }

    protected class HttpStreamOverHTTP1 implements HttpStream
    {
        private final long _id;
        private final String _method;
        private final HttpURI.Mutable _uri;
        private final HttpVersion _version;
        private long _contentLength = -1;
        private HostPortHttpField _hostField;
        private MetaData.Request _request;
        private HttpField _upgrade = null;
        private Content.Chunk _chunk;
        private boolean _connectionClose = false;
        private boolean _connectionKeepAlive = false;
        private boolean _connectionUpgrade = false;
        private boolean _unknownExpectation = false;
        private boolean _expects100Continue = false;
        private List<String> _complianceViolations;

        protected HttpStreamOverHTTP1(String method, String uri, HttpVersion version)
        {
            _id = _streamIdGenerator.getAndIncrement();
            _method = method;
            _uri = uri == null ? null : HttpURI.build(method, uri);
            _version = Objects.requireNonNull(version);

            if (_uri != null && _uri.getPath() == null && _uri.getScheme() != null && _uri.hasAuthority())
                _uri.path("/");
        }

        @Override
        public Throwable consumeAvailable()
        {
            Throwable result = HttpStream.consumeAvailable(this, getHttpConfiguration());
            if (result != null)
            {
                _generator.setPersistent(false);
                if (_chunk != null)
                    _chunk.release();
                _chunk = Content.Chunk.from(result, true);
            }
            return result;
        }

        public void parsedHeader(HttpField field)
        {
            HttpHeader header = field.getHeader();
            String value = field.getValue();
            if (header != null)
            {
                switch (header)
                {
                    case CONNECTION:
                        _connectionClose |= field.contains(HttpHeaderValue.CLOSE.asString());
                        if (HttpVersion.HTTP_1_0.equals(_version))
                            _connectionKeepAlive |= field.contains(HttpHeader.KEEP_ALIVE.asString());
                        _connectionUpgrade |= field.contains(HttpHeaderValue.UPGRADE.asString());
                        break;

                    case HOST:
                        if (value == null)
                            value = "";
                        if (field instanceof HostPortHttpField)
                            _hostField = (HostPortHttpField)field;
                        else
                            field = _hostField = new HostPortHttpField(value);
                        break;

                    case EXPECT:
                    {
                        if (!HttpHeaderValue.parseCsvIndex(value, t ->
                        {
                            if (t == HttpHeaderValue.CONTINUE)
                            {
                                _expects100Continue = true;
                                return true;
                            }
                            return false;
                        }, s -> false))
                        {
                            _unknownExpectation = true;
                            _expects100Continue = false;
                        }
                        break;
                    }

                    case UPGRADE:
                        _upgrade = field;
                        break;

                    case CONTENT_LENGTH:
                        _contentLength = field.getLongValue();
                        break;

                    default:
                        break;
                }
            }
            _headerBuilder.add(field);
        }

        public Runnable headerComplete()
        {
            UriCompliance compliance;
            if (_uri.hasViolations())
            {
                compliance = getHttpConfiguration().getUriCompliance();
                String badMessage = UriCompliance.checkUriCompliance(compliance, _uri);
                if (badMessage != null)
                    throw new BadMessageException(badMessage);
            }

            // Check host field matches the authority in the any absolute URI or is not blank
            if (_hostField != null)
            {
                if (_uri.isAbsolute())
                {
                    if (!_hostField.getValue().equals(_uri.getAuthority()))
                    {
                        HttpCompliance httpCompliance = getHttpConfiguration().getHttpCompliance();
                        if (httpCompliance.allows(MISMATCHED_AUTHORITY))
                            onComplianceViolation(httpCompliance, MISMATCHED_AUTHORITY, _uri.asString());
                        else
                            throw new BadMessageException("Authority!=Host");
                    }
                }
                else
                {
                    if (StringUtil.isBlank(_hostField.getHostPort().getHost()))
                        throw new BadMessageException("Blank Host");
                }
            }

            // Set the scheme in the URI
            if (!_uri.isAbsolute())
                _uri.scheme(getEndPoint() instanceof SslConnection.SslEndPoint ? HttpScheme.HTTPS : HttpScheme.HTTP);

            // Set the authority (if not already set) in the URI
            if (_uri.getAuthority() == null && !HttpMethod.CONNECT.is(_method))
            {
                HostPort hostPort = _hostField == null ? getServerAuthority() : _hostField.getHostPort();
                int port = hostPort.getPort();
                if (port == HttpScheme.getDefaultPort(_uri.getScheme()))
                    port = -1;
                _uri.authority(hostPort.getHost(), port);
            }

            // Set path (if not already set)
            if (_uri.getPath() == null)
            {
                _uri.path("/");
            }

            _request = new MetaData.Request(_parser.getBeginNanoTime(), _method, _uri.asImmutable(), _version, _headerBuilder, _contentLength);

            Runnable handle = _httpChannel.onRequest(_request);
            ++_requests;

            if (_complianceViolations != null && !_complianceViolations.isEmpty())
            {
                _httpChannel.getRequest().setAttribute(HttpCompliance.VIOLATIONS_ATTR, _complianceViolations);
                _complianceViolations = null;
            }

            boolean persistent;

            switch (_request.getHttpVersion())
            {
                case HTTP_0_9:
                {
                    persistent = false;
                    break;
                }
                case HTTP_1_0:
                {
                    persistent = getHttpConfiguration().isPersistentConnectionsEnabled() &&
                        _connectionKeepAlive &&
                        !_connectionClose ||
                        HttpMethod.CONNECT.is(_method);

                    _generator.setPersistent(persistent);
                    if (!persistent)
                        _connectionKeepAlive = false;

                    break;
                }

                case HTTP_1_1:
                {
                    if (_unknownExpectation)
                    {
                        _requestHandler.badMessage(new BadMessageException(HttpStatus.EXPECTATION_FAILED_417));
                        return null;
                    }

                    persistent = getHttpConfiguration().isPersistentConnectionsEnabled() &&
                        !_connectionClose ||
                        HttpMethod.CONNECT.is(_method);

                    _generator.setPersistent(persistent);

                    // Try to upgrade before calling the application.
                    // In case of WebSocket, it is the application that performs the upgrade
                    // so upgrade(stream) will return false, and the upgrade will be finished
                    // in HttpStreamOverHTTP1.succeeded().
                    // In case of HTTP/2, the application is not called and the upgrade
                    // is finished here by upgrade(stream) which will return true.
                    if (_upgrade != null && HttpConnection.this.upgrade(_stream.get()))
                        return null;

                    break;
                }

                case HTTP_2:
                {
                    // Allow prior knowledge "upgrade" to HTTP/2 only if the connector supports h2c.
                    _upgrade = PREAMBLE_UPGRADE_H2C;

                    if (HttpMethod.PRI.is(_method) &&
                        "*".equals(_uri.getPath()) &&
                        _headerBuilder.size() == 0 &&
                        HttpConnection.this.upgrade(_stream.get()))
                        return null;

                    // TODO is this sufficient?
                    _parser.close();
                    throw new BadMessageException(HttpStatus.UPGRADE_REQUIRED_426, "Upgrade Required");
                }

                default:
                {
                    throw new IllegalStateException("unsupported version " + _version);
                }
            }

            if (!persistent)
                _generator.setPersistent(false);

            return handle;
        }

        @Override
        public String getId()
        {
            return Long.toString(_id);
        }

        @Override
        public Content.Chunk read()
        {
            if (_chunk == null)
            {
                if (_parser.isTerminated())
                    _chunk = Content.Chunk.EOF;
                else
                    parseAndFillForContent();
            }

            Content.Chunk content = _chunk;
            _chunk = Content.Chunk.next(content);

            // Some content is read, but the 100 Continue interim
            // response has not been sent yet, then don't bother
            // sending it later, as the client already sent the content.
            if (content != null && _expects100Continue && content.hasRemaining())
                _expects100Continue = false;

            return content;
        }

        @Override
        public void demand()
        {
            if (_chunk != null)
            {
                Runnable onContentAvailable = _httpChannel.onContentAvailable();
                if (onContentAvailable != null)
                    onContentAvailable.run();
                return;
            }
            parseAndFillForContent();
            if (_chunk != null)
            {
                Runnable onContentAvailable = _httpChannel.onContentAvailable();
                if (onContentAvailable != null)
                    onContentAvailable.run();
                return;
            }

            if (_expects100Continue)
            {
                _expects100Continue = false;
                send(_request, HttpGenerator.CONTINUE_100_INFO, false, null, Callback.NOOP);
            }

            tryFillInterested(_demandContentCallback);
        }

        @Override
        public void prepareResponse(HttpFields.Mutable headers)
        {
            if (_connectionKeepAlive && _version == HttpVersion.HTTP_1_0 && !headers.contains(HttpHeader.CONNECTION, HttpHeaderValue.CLOSE.asString()))
                headers.add(HttpFields.CONNECTION_KEEPALIVE);
        }

        @Override
        public void send(MetaData.Request request, MetaData.Response response, boolean last, ByteBuffer content, Callback callback)
        {
            if (response == null)
            {
                if (!last && BufferUtil.isEmpty(content))
                {
                    callback.succeeded();
                    return;
                }
            }
            else if (_generator.isCommitted())
            {
                callback.failed(new IllegalStateException("Committed"));
            }
            else if (_expects100Continue)
            {
                if (response.getStatus() == HttpStatus.CONTINUE_100)
                {
                    _expects100Continue = false;
                }
                else
                {
                    // Expecting to send a 100 Continue response, but it's a different response,
                    // then cannot be persistent because likely the client did not send the content.
                    _generator.setPersistent(false);
                }
            }

            if (_sendCallback.reset(_request, response, content, last, callback))
                _sendCallback.iterate();
        }

        @Override
        public long getIdleTimeout()
        {
            return getEndPoint().getIdleTimeout();
        }

        @Override
        public void setIdleTimeout(long idleTimeoutMs)
        {
            getEndPoint().setIdleTimeout(idleTimeoutMs);
        }

        @Override
        public boolean isCommitted()
        {
            return _stream.get() != this || _generator.isCommitted();
        }

        private boolean upgrade()
        {
            if (LOG.isDebugEnabled())
                LOG.debug("upgrade {} {}", this, _upgrade);

            // If no upgrade headers then there is nothing to do.
            if (!_connectionUpgrade && _upgrade == null)
                return false;

            @SuppressWarnings("ReferenceEquality")
            boolean isPriorKnowledgeH2C = _upgrade == PREAMBLE_UPGRADE_H2C;
            if (!isPriorKnowledgeH2C  && !_connectionUpgrade)
                throw new BadMessageException(HttpStatus.BAD_REQUEST_400);

            // Find the upgrade factory.
            ConnectionFactory.Upgrading factory = getConnector().getConnectionFactories().stream()
                .filter(f -> f instanceof ConnectionFactory.Upgrading)
                .map(ConnectionFactory.Upgrading.class::cast)
                .filter(f -> f.getProtocols().contains(_upgrade.getValue()))
                .findAny()
                .orElse(null);

            if (factory == null)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("No factory for {} in {}", _upgrade, getConnector());
                return false;
            }

            HttpFields.Mutable response101 = HttpFields.build();
            Connection upgradeConnection = factory.upgradeConnection(getConnector(), getEndPoint(), _request, response101);
            if (upgradeConnection == null)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Upgrade ignored for {} by {}", _upgrade, factory);
                return false;
            }

            // Prior knowledge HTTP/2 does not need a 101 response (it will directly be
            // in HTTP/2 format) while HTTP/1.1 to HTTP/2 upgrade needs a 101 response.
            if (!isPriorKnowledgeH2C)
                send(_request, new MetaData.Response(HttpStatus.SWITCHING_PROTOCOLS_101, null, HttpVersion.HTTP_1_1, response101, 0), false, null, Callback.NOOP);

            if (LOG.isDebugEnabled())
                LOG.debug("Upgrading from {} to {}", getEndPoint().getConnection(), upgradeConnection);
            getEndPoint().upgrade(upgradeConnection);

            return true;
        }

        @Override
        public TunnelSupport getTunnelSupport()
        {
            return _tunnelSupport;
        }

        @Override
        public void succeeded()
        {
            HttpStreamOverHTTP1 stream = _stream.getAndSet(null);
            if (stream == null)
                return;

            if (LOG.isDebugEnabled())
                LOG.debug("succeeded {}", HttpConnection.this);
            // If we are fill interested, then a read is pending and we must abort
            if (isFillInterested())
            {
                LOG.warn("Read pending {} {}", this, getEndPoint());
                abort(new IOException("Pending read in onCompleted"));
                return;
            }

            Connection upgradeConnection = (Connection)_httpChannel.getRequest().getAttribute(HttpStream.UPGRADE_CONNECTION_ATTRIBUTE);
            if (upgradeConnection != null)
            {
                getEndPoint().upgrade(upgradeConnection);
                _httpChannel.recycle();
                _parser.close();
                _generator.reset();
                return;
            }

            _httpChannel.recycle();

            if (_expects100Continue)
            {
                // No content was read, and no 100 Continue response was sent.
                // Close the parser so that below it seeks EOF, not the next request.
                _expects100Continue = false;
                _parser.close();
            }

            // Reset the channel, parsers and generator
            if (!_parser.isClosed())
            {
                if (_generator.isPersistent())
                    _parser.reset();
                else
                    _parser.close();
            }

            _generator.reset();

            // Can the onFillable thread continue processing
            if (_handling.compareAndSet(true, false))
                return;

            // we need to organized further processing
            if (LOG.isDebugEnabled())
                LOG.debug("non-current completion {}", this);

            if (_sendCallback._shutdownOut)
                getEndPoint().shutdownOutput();

            // If we are looking for the next request
            if (_parser.isStart())
            {
                // if the buffer is empty
                if (isRequestBufferEmpty())
                {
                    // look for more data
                    fillInterested();
                }
                // else if we are still running
                else if (getConnector().isRunning())
                {
                    // Dispatched to handle a pipelined request
                    try
                    {
                        getExecutor().execute(HttpConnection.this);
                    }
                    catch (RejectedExecutionException e)
                    {
                        if (getConnector().isRunning())
                            LOG.warn("Failed dispatch of {}", this, e);
                        else
                            LOG.trace("IGNORED", e);
                        getEndPoint().close();
                    }
                }
                else
                {
                    getEndPoint().close();
                }
            }
            // else the parser must be closed, so seek the EOF if we are still open
            else if (getEndPoint().isOpen())
                fillInterested();
        }

        @Override
        public void failed(Throwable x)
        {
            HttpStreamOverHTTP1 stream = _stream.getAndSet(null);
            if (stream == null)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("ignored", x);
                return;
            }
            if (LOG.isDebugEnabled())
                LOG.debug("aborting", x);
            abort(x);
        }

        private void abort(Throwable failure)
        {
            getEndPoint().close(failure);
        }

        @Override
        public InvocationType getInvocationType()
        {
            return HttpStream.super.getInvocationType();
        }
    }

    private class TunnelSupportOverHTTP1 implements TunnelSupport
    {
        @Override
        public String getProtocol()
        {
            return null;
        }

        @Override
        public EndPoint getEndPoint()
        {
            return HttpConnection.this.getEndPoint();
        }
    }
}
