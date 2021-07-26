//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.server;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritePendingException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

import org.eclipse.jetty.http.BadMessageException;
import org.eclipse.jetty.http.HttpCompliance;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpGenerator;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpHeaderValue;
import org.eclipse.jetty.http.HttpParser;
import org.eclipse.jetty.http.HttpParser.RequestHandler;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http.PreEncodedHttpField;
import org.eclipse.jetty.io.AbstractConnection;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.EofException;
import org.eclipse.jetty.io.WriteFlusher;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IteratingCallback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

import static org.eclipse.jetty.http.HttpStatus.INTERNAL_SERVER_ERROR_500;

/**
 * <p>A {@link Connection} that handles the HTTP protocol.</p>
 */
public class HttpConnection extends AbstractConnection implements Runnable, HttpTransport, WriteFlusher.Listener, Connection.UpgradeFrom, Connection.UpgradeTo
{
    private static final Logger LOG = Log.getLogger(HttpConnection.class);
    public static final HttpField CONNECTION_CLOSE = new PreEncodedHttpField(HttpHeader.CONNECTION, HttpHeaderValue.CLOSE.asString());
    public static final String UPGRADE_CONNECTION_ATTRIBUTE = "org.eclipse.jetty.server.HttpConnection.UPGRADE";
    private static final boolean REQUEST_BUFFER_DIRECT = false;
    private static final boolean HEADER_BUFFER_DIRECT = false;
    private static final boolean CHUNK_BUFFER_DIRECT = false;
    private static final ThreadLocal<HttpConnection> __currentConnection = new ThreadLocal<>();

    private final HttpConfiguration _config;
    private final Connector _connector;
    private final ByteBufferPool _bufferPool;
    private final HttpInput _input;
    private final HttpGenerator _generator;
    private final HttpChannelOverHttp _channel;
    private final HttpParser _parser;
    private final AtomicInteger _contentBufferReferences = new AtomicInteger();
    private volatile ByteBuffer _requestBuffer = null;
    private final BlockingReadCallback _blockingReadCallback = new BlockingReadCallback();
    private final AsyncReadCallback _asyncReadCallback = new AsyncReadCallback();
    private final SendCallback _sendCallback = new SendCallback();
    private final boolean _recordHttpComplianceViolations;
    private final LongAdder bytesIn = new LongAdder();
    private final LongAdder bytesOut = new LongAdder();

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

    public HttpConnection(HttpConfiguration config, Connector connector, EndPoint endPoint, HttpCompliance compliance, boolean recordComplianceViolations)
    {
        super(endPoint, connector.getExecutor());
        _config = config;
        _connector = connector;
        _bufferPool = _connector.getByteBufferPool();
        _generator = newHttpGenerator();
        _channel = newHttpChannel();
        _input = _channel.getRequest().getHttpInput();
        _parser = newHttpParser(compliance);
        _recordHttpComplianceViolations = recordComplianceViolations;
        if (LOG.isDebugEnabled())
            LOG.debug("New HTTP Connection {}", this);
    }

    @Deprecated
    public HttpCompliance getHttpCompliance()
    {
        return _parser.getHttpCompliance();
    }

    public HttpConfiguration getHttpConfiguration()
    {
        return _config;
    }

    public boolean isRecordHttpComplianceViolations()
    {
        return _recordHttpComplianceViolations;
    }

    protected HttpGenerator newHttpGenerator()
    {
        return new HttpGenerator(_config.getSendServerVersion(), _config.getSendXPoweredBy());
    }

    protected HttpChannelOverHttp newHttpChannel()
    {
        return new HttpChannelOverHttp(this, _connector, _config, getEndPoint(), this);
    }

    protected HttpParser newHttpParser(HttpCompliance compliance)
    {
        return new HttpParser(newRequestHandler(), getHttpConfiguration().getRequestHeaderSize(), compliance);
    }

    protected HttpParser.RequestHandler newRequestHandler()
    {
        return _channel;
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
        return _channel;
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
    public boolean isOptimizedForDirectBuffers()
    {
        return getEndPoint().isOptimizedForDirectBuffers();
    }

    @Override
    public long getMessagesIn()
    {
        return getHttpChannel().getRequests();
    }

    @Override
    public long getMessagesOut()
    {
        return getHttpChannel().getRequests();
    }

    @Override
    public ByteBuffer onUpgradeFrom()
    {
        if (BufferUtil.hasContent(_requestBuffer))
        {
            ByteBuffer unconsumed = ByteBuffer.allocateDirect(_requestBuffer.remaining());
            unconsumed.put(_requestBuffer);
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
        // Unfortunately cannot distinguish between header and content
        // bytes, and for content bytes whether they are chunked or not.
        _channel.getResponse().getHttpOutput().onFlushed(bytes);
    }

    void releaseRequestBuffer()
    {
        if (_requestBuffer != null && !_requestBuffer.hasRemaining())
        {
            if (LOG.isDebugEnabled())
                LOG.debug("releaseRequestBuffer {}", this);
            ByteBuffer buffer = _requestBuffer;
            _requestBuffer = null;
            _bufferPool.release(buffer);
        }
    }

    public ByteBuffer getRequestBuffer()
    {
        if (_requestBuffer == null)
            _requestBuffer = _bufferPool.acquire(getInputBufferSize(), REQUEST_BUFFER_DIRECT);
        return _requestBuffer;
    }

    public boolean isRequestBufferEmpty()
    {
        return BufferUtil.isEmpty(_requestBuffer);
    }

    @Override
    public void onFillable()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("{} onFillable enter {} {}", this, _channel.getState(), BufferUtil.toDetailString(_requestBuffer));

        HttpConnection last = setCurrentConnection(this);
        try
        {
            while (getEndPoint().isOpen())
            {
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

                // Handle channel event
                if (handle)
                {
                    boolean suspended = !_channel.handle();

                    // We should break iteration if we have suspended or upgraded the connection.
                    if (suspended || getEndPoint().getConnection() != this)
                        break;
                }
                else if (filled == 0)
                {
                    fillInterested();
                    break;
                }
                else if (filled < 0)
                {
                    if (_channel.getState().isIdle())
                        getEndPoint().shutdownOutput();
                    break;
                }
            }
        }
        catch (Throwable x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("{} caught exception {}", this, _channel.getState(), x);
            BufferUtil.clear(_requestBuffer);
            releaseRequestBuffer();
            close();
        }
        finally
        {
            setCurrentConnection(last);
            if (LOG.isDebugEnabled())
                LOG.debug("{} onFillable exit {} {}", this, _channel.getState(), BufferUtil.toDetailString(_requestBuffer));
        }
    }

    /**
     * Fill and parse data looking for content
     *
     * @return true if an {@link RequestHandler} method was called and it returned true;
     */
    protected boolean fillAndParseForContent()
    {
        // Defensive check to avoid an infinite select/wakeup/fillAndParseForContent/wait loop
        // in case the parser was mistakenly closed and the connection was not aborted.
        if (_parser.isTerminated())
            throw new IllegalStateException("Parser is terminated: " + _parser);

        boolean handled = false;
        while (_parser.inContentState())
        {
            int filled = fillRequestBuffer();
            handled = parseRequestBuffer();
            if (handled || filled <= 0 || _input.hasContent())
                break;
        }
        return handled;
    }

    private int fillRequestBuffer()
    {
        if (_contentBufferReferences.get() > 0)
            throw new IllegalStateException("fill with unconsumed content on " + this);

        if (BufferUtil.isEmpty(_requestBuffer))
        {
            // Get a buffer
            // We are not in a race here for the request buffer as we have not yet received a request,
            // so there are not an possible legal threads calling #parseContent or #completed.
            _requestBuffer = getRequestBuffer();

            // fill
            try
            {
                int filled = getEndPoint().fill(_requestBuffer);
                if (filled == 0) // Do a retry on fill 0 (optimization for SSL connections)
                    filled = getEndPoint().fill(_requestBuffer);

                if (filled > 0)
                    bytesIn.add(filled);
                else if (filled < 0)
                    _parser.atEOF();

                if (LOG.isDebugEnabled())
                    LOG.debug("{} filled {} {}", this, filled, BufferUtil.toDetailString(_requestBuffer));

                return filled;
            }
            catch (IOException e)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug(e);
                _parser.atEOF();
                return -1;
            }
        }

        return 0;
    }

    private boolean parseRequestBuffer()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("{} parse {} {}", this, BufferUtil.toDetailString(_requestBuffer));

        boolean handle = _parser.parseNext(_requestBuffer == null ? BufferUtil.EMPTY_BUFFER : _requestBuffer);

        if (LOG.isDebugEnabled())
            LOG.debug("{} parsed {} {}", this, handle, _parser);

        // recycle buffer ?
        if (_contentBufferReferences.get() == 0)
            releaseRequestBuffer();

        return handle;
    }

    @Override
    public void onCompleted()
    {
        // If we are fill interested, then a read is pending and we must abort
        if (isFillInterested())
        {
            LOG.warn("Pending read in onCompleted {} {}", this, getEndPoint());
            _channel.abort(new IOException("Pending read in onCompleted"));
        }

        // Handle connection upgrades
        else if (_channel.getResponse().getStatus() == HttpStatus.SWITCHING_PROTOCOLS_101)
        {
            Connection connection = (Connection)_channel.getRequest().getAttribute(UPGRADE_CONNECTION_ATTRIBUTE);
            if (connection != null)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Upgrade from {} to {}", this, connection);
                _channel.getState().upgrade();
                getEndPoint().upgrade(connection);
                _channel.recycle();
                _parser.reset();
                _generator.reset();
                if (_contentBufferReferences.get() == 0)
                    releaseRequestBuffer();
                else
                {
                    LOG.warn("{} lingering content references?!?!", this);
                    _requestBuffer = null; // Not returned to pool!
                    _contentBufferReferences.set(0);
                }
                return;
            }
        }

        // Drive to EOF, EarlyEOF or Error
        boolean complete = _input.consumeAll();

        // Finish consuming the request
        // If we are still expecting
        if (_channel.isExpecting100Continue())
        {
            // close to seek EOF
            _parser.close();
        }
        // else abort if we can't consume all
        else if (_generator.isPersistent() && !complete)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("unconsumed input {} {}", this, _parser);
            _channel.abort(new IOException("unconsumed input"));
        }

        // Reset the channel, parsers and generator
        _channel.recycle();
        if (!_parser.isClosed())
        {
            if (_generator.isPersistent())
                _parser.reset();
            else
                _parser.close();
        }

        _generator.reset();

        // if we are not called from the onfillable thread, schedule completion
        if (getCurrentConnection() != this)
        {
            // If we are looking for the next request
            if (_parser.isStart())
            {
                // if the buffer is empty
                if (BufferUtil.isEmpty(_requestBuffer))
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
                        getExecutor().execute(this);
                    }
                    catch (RejectedExecutionException e)
                    {
                        if (getConnector().isRunning())
                            LOG.warn(e);
                        else
                            LOG.ignore(e);
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
    }

    @Override
    protected boolean onReadTimeout(Throwable timeout)
    {
        return _channel.onIdleTimeout(timeout);
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
    public void onClose()
    {
        _sendCallback.close();
        super.onClose();
    }

    @Override
    public void run()
    {
        onFillable();
    }

    @Override
    public void send(MetaData.Response info, boolean head, ByteBuffer content, boolean lastContent, Callback callback)
    {
        if (info == null)
        {
            if (!lastContent && BufferUtil.isEmpty(content))
            {
                callback.succeeded();
                return;
            }
        }
        else
        {
            // If we are still expecting a 100 continues when we commit
            if (_channel.isExpecting100Continue())
                // then we can't be persistent
                _generator.setPersistent(false);
        }

        if (_sendCallback.reset(info, head, content, lastContent, callback))
        {
            _sendCallback.iterate();
        }
    }

    HttpInput.Content newContent(ByteBuffer c)
    {
        return new Content(c);
    }

    @Override
    public void abort(Throwable failure)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("abort {} {}", this, failure);
        // Do a direct close of the output, as this may indicate to a client that the
        // response is bad either with RST or by abnormal completion of chunked response.
        getEndPoint().close();
    }

    @Override
    public boolean isPushSupported()
    {
        return false;
    }

    @Override
    public void push(org.eclipse.jetty.http.MetaData.Request request)
    {
        LOG.debug("ignore push in {}", this);
    }

    public void asyncReadFillInterested()
    {
        getEndPoint().fillInterested(_asyncReadCallback);
    }

    public void blockingReadFillInterested()
    {
        // We try fillInterested here because of SSL and 
        // spurious wakeups.  With  blocking reads, we read in a loop
        // that tries to read/parse content and blocks waiting if there is
        // none available.  The loop can be woken up by incoming encrypted 
        // bytes, which due to SSL might not produce any decrypted bytes.
        // Thus the loop needs to register fill interest again.  However if 
        // the loop is woken up spuriously, then the register interest again
        // can result in a pending read exception, unless we use tryFillInterested.
        getEndPoint().tryFillInterested(_blockingReadCallback);
    }

    public void blockingReadFailure(Throwable e)
    {
        _blockingReadCallback.failed(e);
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
            _channel);
    }

    private class Content extends HttpInput.Content
    {
        public Content(ByteBuffer content)
        {
            super(content);
            _contentBufferReferences.incrementAndGet();
        }

        @Override
        public void succeeded()
        {
            if (_contentBufferReferences.decrementAndGet() == 0)
                releaseRequestBuffer();
        }

        @Override
        public void failed(Throwable x)
        {
            succeeded();
        }
    }

    private class BlockingReadCallback implements Callback
    {
        @Override
        public void succeeded()
        {
            _input.unblock();
        }

        @Override
        public void failed(Throwable x)
        {
            _input.failed(x);
        }

        @Override
        public InvocationType getInvocationType()
        {
            // This callback does not block, rather it wakes up the
            // thread that is blocked waiting on the read.
            return InvocationType.NON_BLOCKING;
        }
    }

    private class AsyncReadCallback implements Callback
    {
        @Override
        public void succeeded()
        {
            if (_channel.getState().onReadPossible())
                _channel.handle();
        }

        @Override
        public void failed(Throwable x)
        {
            if (_input.failed(x))
                _channel.handle();
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

        private boolean reset(MetaData.Response info, boolean head, ByteBuffer content, boolean last, Callback callback)
        {
            if (reset())
            {
                _info = info;
                _head = head;
                _content = content;
                _lastContent = last;
                _callback = callback;
                _header = null;
                _shutdownOut = false;

                if (getConnector().isShutdown())
                    _generator.setPersistent(false);

                return true;
            }

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
                        _header = _bufferPool.acquire(Math.min(_config.getResponseHeaderSize(), _config.getOutputBufferSize()), HEADER_BUFFER_DIRECT);
                        continue;
                    }

                    case HEADER_OVERFLOW:
                    {
                        if (_header.capacity() >= _config.getResponseHeaderSize())
                            throw new BadMessageException(INTERNAL_SERVER_ERROR_500, "Response header too large");
                        releaseHeader();
                        _header = _bufferPool.acquire(_config.getResponseHeaderSize(), HEADER_BUFFER_DIRECT);
                        continue;
                    }
                    case NEED_CHUNK:
                    {
                        _chunk = _bufferPool.acquire(HttpGenerator.CHUNK_SIZE, CHUNK_BUFFER_DIRECT);
                        continue;
                    }
                    case NEED_CHUNK_TRAILER:
                    {
                        releaseChunk();
                        _chunk = _bufferPool.acquire(_config.getResponseHeaderSize(), CHUNK_BUFFER_DIRECT);
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
            release().succeeded();
            if (_shutdownOut)
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
}
