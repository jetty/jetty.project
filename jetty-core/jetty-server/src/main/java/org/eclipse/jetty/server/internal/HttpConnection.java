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

package org.eclipse.jetty.server.internal;

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.WritePendingException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

import org.eclipse.jetty.http.BadMessageException;
import org.eclipse.jetty.http.ComplianceViolation;
import org.eclipse.jetty.http.HostPortHttpField;
import org.eclipse.jetty.http.HttpCompliance;
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
import org.eclipse.jetty.io.AbstractConnection;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.EofException;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.io.RetainableByteBufferPool;
import org.eclipse.jetty.io.WriteFlusher;
import org.eclipse.jetty.io.ssl.SslConnection;
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
import org.eclipse.jetty.util.thread.Invocable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.eclipse.jetty.http.HttpStatus.INTERNAL_SERVER_ERROR_500;

/**
 * <p>A {@link Connection} that handles the HTTP protocol.</p>
 */
public class HttpConnection extends AbstractConnection implements Runnable, WriteFlusher.Listener, Connection.UpgradeFrom, Connection.UpgradeTo, ConnectionMetaData
{
    private static final Logger LOG = LoggerFactory.getLogger(HttpConnection.class);
    private static final ThreadLocal<HttpConnection> __currentConnection = new ThreadLocal<>();
    private static final AtomicLong __connectionIdGenerator = new AtomicLong();

    private final TunnelSupport _tunnelSupport = new TunnelSupportOverHTTP1();
    private final AtomicLong _streamIdGenerator = new AtomicLong();
    private final long _id;
    private final HttpConfiguration _configuration;
    private final Connector _connector;
    private final HttpChannel _httpChannel;
    private final RequestHandler _requestHandler;
    private final HttpParser _parser;
    private final HttpGenerator _generator;
    private final ByteBufferPool _bufferPool;
    private final RetainableByteBufferPool _retainableByteBufferPool;
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
        super(endPoint, connector.getExecutor());
        _id = __connectionIdGenerator.getAndIncrement();
        _configuration = configuration;
        _connector = connector;
        _bufferPool = _connector.getByteBufferPool();
        _retainableByteBufferPool = _bufferPool.asRetainableByteBufferPool();
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
        return new HttpGenerator(_configuration.getSendServerVersion(), _configuration.getSendXPoweredBy());
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
        return _connector.getServer();
    }

    public Connector getConnector()
    {
        return _connector;
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
        return "%s@%x#%d".formatted(getEndPoint().getRemoteSocketAddress(), hashCode(), _id);
    }

    @Override
    public HttpConfiguration getHttpConfiguration()
    {
        return _configuration;
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
    public Connection getConnection()
    {
        return this;
    }

    @Override
    public boolean isPersistent()
    {
        return _generator.isPersistent(getHttpVersion());
    }

    @Override
    public boolean isSecure()
    {
        return getEndPoint() instanceof SslConnection.DecryptedEndPoint;
    }

    @Override
    public SocketAddress getRemoteSocketAddress()
    {
        return getEndPoint().getRemoteSocketAddress();
    }

    @Override
    public SocketAddress getLocalSocketAddress()
    {
        HttpConfiguration config = getHttpConfiguration();
        if (config != null)
        {
            SocketAddress override = config.getLocalAddress();
            if (override != null)
                return override;
        }
        return getEndPoint().getLocalSocketAddress();
    }

    @Override
    public HostPort getServerAuthority()
    {
        HostPort authority = ConnectionMetaData.getServerAuthority(getHttpConfiguration(), this);
        if (authority == null)
            authority = new HostPort(getLocalSocketAddress().toString(), -1);
        return authority;
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

    @Override
    public ByteBuffer onUpgradeFrom()
    {
        if (!isRequestBufferEmpty())
        {
            ByteBuffer unconsumed = ByteBuffer.allocateDirect(_retainableByteBuffer.remaining());
            unconsumed.put(_retainableByteBuffer.getBuffer());
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
            _retainableByteBuffer = _retainableByteBufferPool.acquire(getInputBufferSize(), isUseInputDirectByteBuffers());
        return _retainableByteBuffer.getBuffer();
    }

    public boolean isRequestBufferEmpty()
    {
        return _retainableByteBuffer == null || _retainableByteBuffer.isEmpty();
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
            RetainableByteBuffer newBuffer = _retainableByteBufferPool.acquire(getInputBufferSize(), isUseInputDirectByteBuffers());
            if (LOG.isDebugEnabled())
                LOG.debug("replace buffer {} <- {} in {}", _retainableByteBuffer, newBuffer, this);
            _retainableByteBuffer.release();
            _retainableByteBuffer = newBuffer;
        }

        if (isRequestBufferEmpty())
        {
            // Get a buffer
            // We are not in a race here for the request buffer as we have not yet received a request,
            // so there are not an possible legal threads calling #parseContent or #completed.
            ByteBuffer requestBuffer = getRequestBuffer();

            // fill
            try
            {
                int filled = getEndPoint().fill(requestBuffer);
                if (filled == 0) // Do a retry on fill 0 (optimization for SSL connections)
                    filled = getEndPoint().fill(requestBuffer);

                if (filled > 0)
                    bytesIn.add(filled);
                else if (filled < 0)
                    _parser.atEOF();

                if (LOG.isDebugEnabled())
                    LOG.debug("{} filled {} {}", this, filled, _retainableByteBuffer);

                return filled;
            }
            catch (IOException e)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Unable to fill from endpoint {}", getEndPoint(), e);
                _parser.atEOF();
                return -1;
            }
        }
        return 0;
    }

    private boolean parseRequestBuffer()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("{} parse {}", this, _retainableByteBuffer);

        boolean handle = _parser.parseNext(_retainableByteBuffer == null ? BufferUtil.EMPTY_BUFFER : _retainableByteBuffer.getBuffer());

        if (LOG.isDebugEnabled())
            LOG.debug("{} parsed {} {}", this, handle, _parser);

        // recycle buffer ?
        if (_retainableByteBuffer != null && !_retainableByteBuffer.isRetained())
            releaseRequestBuffer();

        return handle;
    }

    private boolean upgrade(HttpStream stream)
    {
        Connection connection = stream.upgrade();
        if (connection == null)
            return false;

        if (LOG.isDebugEnabled())
            LOG.debug("Upgrade from {} to {}", this, connection);
        getEndPoint().upgrade(connection);
        //_channel.recycle(); // TODO should something be done to the channel?
        _parser.reset();
        _generator.reset();
        if (_retainableByteBuffer != null)
        {
            if (!_retainableByteBuffer.isRetained())
            {
                releaseRequestBuffer();
            }
            else
            {
                LOG.warn("{} lingering content references?!?!", this);
                _retainableByteBuffer = null; // Not returned to pool!
            }
        }
        return true;
    }

    @Override
    protected void onFillInterestedFailed(Throwable cause)
    {
        _parser.close();
        super.onFillInterestedFailed(cause);
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
        try
        {
            if (cause == null)
                _sendCallback.close();
            else
                _sendCallback.failed(cause);
        }
        finally
        {
            if (cause != null)
            {
                Runnable todo = _httpChannel.onFailure(cause);
                if (todo != null)
                    todo.run();
            }
        }
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
        private ByteBuffer _header;
        private ByteBuffer _chunk;
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
                HttpGenerator.Result result = _generator.generateResponse(_info, _head, _header, _chunk, _content, _lastContent);
                if (LOG.isDebugEnabled())
                    LOG.debug("generate: {} for {} ({},{},{})@{}",
                        result,
                        this,
                        BufferUtil.toSummaryString(_header),
                        BufferUtil.toSummaryString(_content),
                        _lastContent,
                        _generator.getState());

                switch (result)
                {
                    case NEED_INFO:
                        throw new EofException("request lifecycle violation");

                    case NEED_HEADER:
                    {
                        _header = _bufferPool.acquire(Math.min(_configuration.getResponseHeaderSize(), _configuration.getOutputBufferSize()), useDirectByteBuffers);
                        continue;
                    }
                    case HEADER_OVERFLOW:
                    {
                        if (_header.capacity() >= _configuration.getResponseHeaderSize())
                            throw new BadMessageException(INTERNAL_SERVER_ERROR_500, "Response header too large");
                        releaseHeader();
                        _header = _bufferPool.acquire(_configuration.getResponseHeaderSize(), useDirectByteBuffers);
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
                        _chunk = _bufferPool.acquire(_configuration.getResponseHeaderSize(), useDirectByteBuffers);
                        continue;
                    }
                    case FLUSH:
                    {
                        // Don't write the chunk or the content if this is a HEAD response, or any other type of response that should have no content
                        if (_head || _generator.isNoContent())
                        {
                            BufferUtil.clear(_chunk);
                            BufferUtil.clear(_content);
                        }

                        byte gatherWrite = 0;
                        long bytes = 0;
                        if (BufferUtil.hasContent(_header))
                        {
                            gatherWrite += 4;
                            bytes += _header.remaining();
                        }
                        if (BufferUtil.hasContent(_chunk))
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
                                getEndPoint().write(this, _header, _chunk, _content);
                                break;
                            case 6:
                                getEndPoint().write(this, _header, _chunk);
                                break;
                            case 5:
                                getEndPoint().write(this, _header, _content);
                                break;
                            case 4:
                                getEndPoint().write(this, _header);
                                break;
                            case 3:
                                getEndPoint().write(this, _chunk, _content);
                                break;
                            case 2:
                                getEndPoint().write(this, _chunk);
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
                _bufferPool.release(_header);
            _header = null;
        }

        private void releaseChunk()
        {
            if (_chunk != null)
                _bufferPool.release(_chunk);
            _chunk = null;
        }

        @Override
        protected void onCompleteSuccess()
        {
            // TODO is this too late to get the request? And is that the right attribute and the right thing to do?
            boolean upgrading = _httpChannel.getRequest() != null && _httpChannel.getRequest().getAttribute(HttpStream.UPGRADE_CONNECTION_ATTRIBUTE) != null;
            release().succeeded();
            // If successfully upgraded it is responsibility of the next protocol to close the connection.
            if (_shutdownOut && !upgrading)
                getEndPoint().shutdownOutput();
        }

        @Override
        public void onCompleteFailure(final Throwable x)
        {
            failedCallback(release(), x);
            if (_shutdownOut)
                getEndPoint().shutdownOutput();
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

        protected RequestHandler()
        {
        }

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

            _retainableByteBuffer.retain();

            if (LOG.isDebugEnabled())
                LOG.debug("content {}/{} for {}", BufferUtil.toDetailString(buffer), _retainableByteBuffer, HttpConnection.this);

            RetainableByteBuffer retainable = _retainableByteBuffer;
            stream._chunk = Content.Chunk.from(buffer, false, () ->
            {
                retainable.release();
                if (LOG.isDebugEnabled())
                    LOG.debug("release {}/{} for {}", BufferUtil.toDetailString(buffer), retainable, this);
            });
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
            // TODO: Not sure what this logic was doing.
//            HttpStreamOverHTTP1 stream = _stream.get();
//            stream._chunk = ContentOLD.last(stream._chunk);
//            if (_trailers != null && (stream._chunk == null || stream._chunk == Content.Chunk.EOF))
//                stream._chunk = new Trailers(_trailers.asImmutable());
//            else
//                stream._chunk = ContentOLD.last(stream._chunk);
//            return false;

            // TODO: verify this new, simpler, logic.
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
        public void badMessage(BadMessageException failure)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("badMessage {} {}", HttpConnection.this, failure);

            _failure = failure;
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
                _httpChannel.onRequest(new MetaData.Request(stream._method, uri, stream._version, HttpFields.EMPTY));
            }

            Runnable todo = _httpChannel.onFailure(failure);
            if (todo != null)
                getServer().getThreadPool().execute(todo);
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

                if (stream._chunk instanceof Error error)
                    error.getCause().addSuppressed(bad);
                else
                {
                    if (stream._chunk != null)
                        stream._chunk.release();
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
    }

    private static final HttpField PREAMBLE_UPGRADE_H2C = new HttpField(HttpHeader.UPGRADE, "h2c");

    protected class HttpStreamOverHTTP1 implements HttpStream
    {
        private final long _nanoTimestamp = System.nanoTime();
        private final long _id;
        private final String _method;
        private final HttpURI.Mutable _uri;
        private final HttpVersion _version;
        private long _contentLength = -1;
        private HostPortHttpField _hostField;
        private MetaData.Request _request;
        private HttpField _upgrade = null;
        private Connection _upgradeConnection;

        Content.Chunk _chunk;
        private boolean _connectionClose = false;
        private boolean _connectionKeepAlive = false;
        private boolean _connectionUpgrade = false;
        private boolean _unknownExpectation = false;
        private boolean _expect100Continue = false;
        private boolean _expect102Processing = false;
        private List<String> _complianceViolations;

        protected HttpStreamOverHTTP1(String method, String uri, HttpVersion version)
        {
            _id = _streamIdGenerator.getAndIncrement();
            _method = method;
            _uri = uri == null ? null : HttpURI.build(method, uri);
            _version = version;

            if (_uri != null && _uri.getPath() == null && _uri.getScheme() != null && _uri.hasAuthority())
                _uri.path("/");
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
                            switch (t)
                            {
                                case CONTINUE:
                                    _expect100Continue = true;
                                    return true;
                                case PROCESSING:
                                    _expect102Processing = true;
                                    return true;
                                default:
                                    return false;
                            }
                        }, s -> false))
                        {
                            _unknownExpectation = true;
                            _expect100Continue = false;
                            _expect102Processing = false;
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
                compliance = _configuration.getUriCompliance();
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
                        throw new BadMessageException("Authority!=Host ");
                }
                else
                {
                    if (StringUtil.isBlank(_hostField.getHostPort().getHost()))
                        throw new BadMessageException("Blank Host");
                }
            }

            // Set the scheme in the URI
            if (!_uri.isAbsolute())
                _uri.scheme(getEndPoint() instanceof SslConnection.DecryptedEndPoint ? HttpScheme.HTTPS : HttpScheme.HTTP);

            // Set the authority (if not already set) in the URI
            if (!HttpMethod.CONNECT.is(_method) && _uri.getAuthority() == null)
            {
                HostPort hostPort = _hostField == null ? getServerAuthority() : _hostField.getHostPort();
                int port = hostPort.getPort();
                if (port == HttpScheme.getDefaultPort(_uri.getScheme()))
                    port = -1;
                _uri.authority(hostPort.getHost(), port);
            }

            _request = new MetaData.Request(_method, _uri.asImmutable(), _version, _headerBuilder, _contentLength);

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

                    // Since persistent status is now exposed in the application API, we need to be more definitive earlier
                    // if we are persistent or not.
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
                        return null; // TODO Is this enough ???
                    }

                    persistent = getHttpConfiguration().isPersistentConnectionsEnabled() &&
                        !_connectionClose ||
                        HttpMethod.CONNECT.is(_method);

                    // Since persistent status is now exposed in the application API, we need to be more definitive earlier
                    // if we are persistent or not.
                    _generator.setPersistent(persistent);

                    if (_upgrade != null && HttpConnection.this.upgrade(_stream.get()))
                        return null; // TODO do we need to return a runnable to complete the upgrade ???

                    break;
                }

                case HTTP_2:
                {
                    // Allow direct "upgrade" to HTTP_2_0 only if the connector supports h2c.
                    _upgrade = PREAMBLE_UPGRADE_H2C;

                    if (HttpMethod.PRI.is(_method) &&
                        "*".equals(_uri.getPath()) &&
                        _headerBuilder.size() == 0 &&
                        HttpConnection.this.upgrade(_stream.get()))
                        return null; // TODO do we need to return a runnable to complete the upgrade ???

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
            return "%s#%d".formatted(_version, _id);
        }

        @Override
        public long getNanoTimeStamp()
        {
            return _nanoTimestamp;
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
            if (content != null && _expect100Continue && content.hasRemaining())
                _expect100Continue = false;

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

            if (_expect100Continue)
            {
                _expect100Continue = false;
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
            else if (response.getStatus() == 102 && !_expect102Processing)
            {
                // silently discard
                callback.succeeded();
            }
            else if (response.getStatus() != 100 && _expect100Continue)
            {
                // If we are still expecting a 100 continues when we commit then we can't be persistent
                _generator.setPersistent(false);
            }

            if (_sendCallback.reset(_request, response, content, last, callback))
                _sendCallback.iterate();
        }

        @Override
        public boolean isPushSupported()
        {
            return false;
        }

        @Override
        public void push(MetaData.Request request)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isCommitted()
        {
            return _stream.get() != this || _generator.isCommitted();
        }

        @Override
        public boolean isComplete()
        {
            return _stream.get() != this;
        }

        @Override
        public void setUpgradeConnection(Connection connection)
        {
            _upgradeConnection = connection;
            if (_httpChannel.getRequest() != null)
                _httpChannel.getRequest().setAttribute(HttpStream.UPGRADE_CONNECTION_ATTRIBUTE, connection);
        }

        @Override
        public Connection upgrade()
        {
            if (LOG.isDebugEnabled())
                LOG.debug("upgrade {} {}", this, _upgrade);

            // If Upgrade attribute already set then we don't need to do anything here.
            if (_upgradeConnection != null)
                return _upgradeConnection;

            // If no upgrade headers there is nothing to do.
            if (!_connectionUpgrade && (_upgrade == null))
                return null;

            @SuppressWarnings("ReferenceEquality")
            boolean isUpgradedH2C = (_upgrade == PREAMBLE_UPGRADE_H2C);
            if (!isUpgradedH2C && !_connectionUpgrade)
                throw new BadMessageException(HttpStatus.BAD_REQUEST_400);

            // Find the upgrade factory
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
                return null;
            }

            // Create new connection
            HttpFields.Mutable response101 = HttpFields.build();
            Connection upgradeConnection = factory.upgradeConnection(getConnector(), getEndPoint(), _request, response101);
            if (upgradeConnection == null)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Upgrade ignored for {} by {}", _upgrade, factory);
                return null;
            }

            // Send 101 if needed
            if (!isUpgradedH2C)
                send(_request, new MetaData.Response(HttpVersion.HTTP_1_1, HttpStatus.SWITCHING_PROTOCOLS_101, response101, 0), false, null, Callback.NOOP);

            if (LOG.isDebugEnabled())
                LOG.debug("Upgrade from {} to {}", getEndPoint().getConnection(), upgradeConnection);
            //getHttpTransport().onCompleted(); // TODO: succeed callback instead?
            return upgradeConnection;
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
                failed(new IOException("Pending read in onCompleted"));
                return;
            }

            // Save the upgrade Connection before recycling the HttpChannel which would clear the request attributes.
            _upgradeConnection = (Connection)_httpChannel.getRequest().getAttribute(HttpStream.UPGRADE_CONNECTION_ATTRIBUTE);

            _httpChannel.recycle();

            if (HttpConnection.this.upgrade(stream))
                return;

            // Finish consuming the request
            // If we are still expecting
            if (_expect100Continue)
            {
                // close to seek EOF
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

            // TODO what about upgrade????

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

            getEndPoint().close();
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
