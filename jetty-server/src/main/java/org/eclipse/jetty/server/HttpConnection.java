//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.WritePendingException;
import java.util.concurrent.RejectedExecutionException;

import org.eclipse.jetty.http.HttpGenerator;
import org.eclipse.jetty.http.HttpGenerator.ResponseInfo;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpHeaderValue;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpParser;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.io.AbstractConnection;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.EofException;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IteratingCallback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * <p>A {@link Connection} that handles the HTTP protocol.</p>
 */
public class HttpConnection extends AbstractConnection implements Runnable, HttpTransport, Connection.UpgradeFrom
{
    public static final String UPGRADE_CONNECTION_ATTRIBUTE = "org.eclipse.jetty.server.HttpConnection.UPGRADE";
    private static final boolean REQUEST_BUFFER_DIRECT=false;
    private static final boolean HEADER_BUFFER_DIRECT=false;
    private static final boolean CHUNK_BUFFER_DIRECT=false;
    private static final Logger LOG = Log.getLogger(HttpConnection.class);
    private static final ThreadLocal<HttpConnection> __currentConnection = new ThreadLocal<>();

    private final HttpConfiguration _config;
    private final Connector _connector;
    private final ByteBufferPool _bufferPool;
    private final HttpGenerator _generator;
    private final HttpChannelOverHttp _channel;
    private final HttpParser _parser;
    private volatile ByteBuffer _requestBuffer = null;
    private volatile ByteBuffer _chunk = null;
    private final SendCallback _sendCallback = new SendCallback();


    /* ------------------------------------------------------------ */
    /** Get the current connection that this thread is dispatched to.
     * Note that a thread may be processing a request asynchronously and
     * thus not be dispatched to the connection.
     * @see Request#getAttribute(String) for a more general way to access the HttpConnection
     * @return the current HttpConnection or null
     */
    public static HttpConnection getCurrentConnection()
    {
        return __currentConnection.get();
    }

    protected static HttpConnection setCurrentConnection(HttpConnection connection)
    {
        HttpConnection last=__currentConnection.get();
        __currentConnection.set(connection);
        return last;
    }

    public HttpConfiguration getHttpConfiguration()
    {
        return _config;
    }

    public HttpConnection(HttpConfiguration config, Connector connector, EndPoint endPoint)
    {
        // Tell AbstractConnector executeOnFillable==true because we want the same thread that
        // does the HTTP parsing to handle the request so its cache is hot
        super(endPoint, connector.getExecutor(),true);

        _config = config;
        _connector = connector;
        _bufferPool = _connector.getByteBufferPool();
        _generator = newHttpGenerator();
        HttpInput<ByteBuffer> input = newHttpInput();
        _channel = newHttpChannel(input);
        _parser = newHttpParser();
        if (LOG.isDebugEnabled())
            LOG.debug("New HTTP Connection {}", this);
    }

    protected HttpGenerator newHttpGenerator()
    {
        return new HttpGenerator(_config.getSendServerVersion(),_config.getSendXPoweredBy());
    }

    protected HttpInput<ByteBuffer> newHttpInput()
    {
        return new HttpInputOverHTTP(this);
    }

    protected HttpChannelOverHttp newHttpChannel(HttpInput<ByteBuffer> httpInput)
    {
        return new HttpChannelOverHttp(_connector, _config, getEndPoint(), this, httpInput);
    }

    protected HttpParser newHttpParser()
    {
        return new HttpParser(newRequestHandler(), getHttpConfiguration().getRequestHeaderSize());
    }

    protected HttpParser.RequestHandler<ByteBuffer> newRequestHandler()
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

    public HttpChannel<?> getHttpChannel()
    {
        return _channel;
    }

    public HttpParser getParser()
    {
        return _parser;
    }

    @Override
    public int getMessagesIn()
    {
        return getHttpChannel().getRequests();
    }

    @Override
    public int getMessagesOut()
    {
        return getHttpChannel().getRequests();
    }

    @Override
    public ByteBuffer onUpgradeFrom()
    {
        if (BufferUtil.hasContent(_requestBuffer))
        {
            ByteBuffer buffer = _requestBuffer;
            _requestBuffer=null;
            return buffer;
        }
        return null;
    }

    void releaseRequestBuffer()
    {
        if (_requestBuffer != null && !_requestBuffer.hasRemaining())
        {
            ByteBuffer buffer=_requestBuffer;
            _requestBuffer=null;
            _bufferPool.release(buffer);
        }
    }

    public ByteBuffer getRequestBuffer()
    {
        if (_requestBuffer == null)
            _requestBuffer = _bufferPool.acquire(getInputBufferSize(), REQUEST_BUFFER_DIRECT);
        return _requestBuffer;
    }

    /**
     * <p>Parses and handles HTTP messages.</p>
     * <p>This method is called when this {@link Connection} is ready to read bytes from the {@link EndPoint}.
     * However, it can also be called if there is unconsumed data in the _requestBuffer, as a result of
     * resuming a suspended request when there is a pipelined request already read into the buffer.</p>
     * <p>This method fills bytes and parses them until either: EOF is filled; 0 bytes are filled;
     * the HttpChannel finishes handling; or the connection has changed.</p>
     */
    @Override
    public void onFillable()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("{} onFillable {}", this, _channel.getState());

        final HttpConnection last=setCurrentConnection(this);
        int filled=Integer.MAX_VALUE;
        boolean suspended=false;
        try
        {
            // while not suspended and not upgraded
            while (!suspended && getEndPoint().getConnection()==this)
            {
                // Do we need some data to parse
                if (BufferUtil.isEmpty(_requestBuffer))
                {
                    // If the previous iteration filled 0 bytes or saw a close, then break here
                    if (filled<=0)
                        break;

                    // Can we fill?
                    if(getEndPoint().isInputShutdown())
                    {
                        // No pretend we read -1
                        filled=-1;
                        _parser.atEOF();
                    }
                    else
                    {
                        // Get a buffer
                        // We are not in a race here for the request buffer as we have not yet received a request,
                        // so there are not an possible legal threads calling #parseContent or #completed.
                        _requestBuffer = getRequestBuffer();

                        // fill
                        filled = getEndPoint().fill(_requestBuffer);
                        if (filled==0) // Do a retry on fill 0 (optimization for SSL connections)
                            filled = getEndPoint().fill(_requestBuffer);

                        // tell parser
                        if (filled < 0)
                            _parser.atEOF();
                    }
                }

                // Parse the buffer
                if (_parser.parseNext(_requestBuffer==null?BufferUtil.EMPTY_BUFFER:_requestBuffer))
                {
                    // The parser returned true, which indicates the channel is ready to handle a request.
                    // Call the channel and this will either handle the request/response to completion OR,
                    // if the request suspends, the request/response will be incomplete so the outer loop will exit.
                    // Not that onFillable no longer manipulates the request buffer from this point and that is
                    // left to threads calling #completed or #parseContent (which may be this thread inside handle())
                    suspended = !_channel.handle();
                }
                else
                {
                    // We parsed what we could, recycle the request buffer
                    // We are not in a race here for the request buffer as we have not yet received a request,
                    // so there are not an possible legal threads calling #parseContent or #completed.
                    releaseRequestBuffer();
                }
            }
        }
        catch (EofException e)
        {
            LOG.debug(e);
        }
        catch (Exception e)
        {
            if (_parser.isIdle())
                LOG.debug(e);
            else
                LOG.warn(this.toString(), e);
            close();
        }
        finally
        {
            setCurrentConnection(last);
            if (!suspended && getEndPoint().isOpen() && getEndPoint().getConnection()==this)
            {
                fillInterested();
            }
        }
    }

    /* ------------------------------------------------------------ */
    /** Fill and parse data looking for content
     * @throws IOException
     */
    protected void parseContent() throws IOException
    {
        // Not in a race here for the request buffer with #onFillable because an async consumer of
        // content would only be started after onFillable has given up control.
        // In a little bit of a race with #completed, but then not sure if it is legal to be doing
        // async calls to IO and have a completed call at the same time.
        ByteBuffer requestBuffer = getRequestBuffer();

        while (_parser.inContentState())
        {
            // Can the parser progress (even with an empty buffer)
            boolean parsed = _parser.parseNext(requestBuffer==null?BufferUtil.EMPTY_BUFFER:requestBuffer);

            // No, we can we try reading some content?
            if (BufferUtil.isEmpty(requestBuffer) && getEndPoint().isInputShutdown())
            {
                _parser.atEOF();
                if (parsed)
                    break;
                continue;
            }

            if (parsed)
                break;

            // OK lets read some data
            int filled=getEndPoint().fill(requestBuffer);
            if (LOG.isDebugEnabled()) // Avoid boxing of variable 'filled'
                LOG.debug("{} filled {}",this,filled);
            if (filled<=0)
            {
                if (filled<0)
                {
                    _parser.atEOF();
                    continue;
                }
                break;
            }
        }
    }

    @Override
    public void completed()
    {
        // Handle connection upgrades
        if (_channel.getResponse().getStatus() == HttpStatus.SWITCHING_PROTOCOLS_101)
        {
            Connection connection = (Connection)_channel.getRequest().getAttribute(UPGRADE_CONNECTION_ATTRIBUTE);
            if (connection != null)
            {
                _channel.getState().upgrade();
                getEndPoint().upgrade(connection);
                _channel.reset();
                _parser.reset();
                _generator.reset();
                releaseRequestBuffer();
                return;
            }
        }

        // Finish consuming the request
        // If we are still expecting
        if (_channel.isExpecting100Continue())
        {
            // close to seek EOF
            _parser.close();
        }
        else if (_parser.inContentState() && _generator.isPersistent())
        {
            // If we are async, then we have problems to complete neatly
            if (_channel.getRequest().getHttpInput().isAsync())
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("unconsumed async input {}", this);
                _channel.abort();
            }
            else
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("unconsumed input {}", this);
                // Complete reading the request
                if (!_channel.getRequest().getHttpInput().consumeAll())
                    _channel.abort();
            }
        }

        // Reset the channel, parsers and generator
        _channel.reset();
        if (_generator.isPersistent() && !_parser.isClosed())
            _parser.reset();
        else
            _parser.close();

        // Not in a race here with onFillable, because it has given up control before calling handle.
        // in a slight race with #completed, but not sure what to do with that anyway.
        releaseRequestBuffer();
        if (_chunk!=null)
            _bufferPool.release(_chunk);
        _chunk=null;
        _generator.reset();

        // if we are not called from the onfillable thread, schedule completion
        if (getCurrentConnection()!=this)
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
    protected void onFillInterestedFailed(Throwable cause)
    {
        _parser.close();
        super.onFillInterestedFailed(cause);
    }

    @Override
    public void onOpen()
    {
        super.onOpen();
        fillInterested();
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
    public void send(ResponseInfo info, ByteBuffer content, boolean lastContent, Callback callback)
    {
        // If we are still expecting a 100 continues when we commit
        if (info!=null && _channel.isExpecting100Continue())
            // then we can't be persistent
            _generator.setPersistent(false);

        if(_sendCallback.reset(info,content,lastContent,callback))
            _sendCallback.iterate();
    }

    @Override
    public void send(ByteBuffer content, boolean lastContent, Callback callback)
    {
        if (!lastContent && BufferUtil.isEmpty(content))
            callback.succeeded();
        else if (_sendCallback.reset(null,content,lastContent,callback))
            _sendCallback.iterate();
    }

    @Override
    public void abort()
    {
        // Do a direct close of the output, as this may indicate to a client that the
        // response is bad either with RST or by abnormal completion of chunked response.
        getEndPoint().close();
    }

    @Override
    public String toString()
    {
        return String.format("%s[p=%s,g=%s,c=%s]",
                super.toString(),
                _parser,
                _generator,
                _channel);
    }

    protected class HttpChannelOverHttp extends HttpChannel<ByteBuffer>
    {
        private InetSocketAddress _localAddr;
        private InetSocketAddress _remoteAddr;

        public HttpChannelOverHttp(Connector connector, HttpConfiguration config, EndPoint endPoint, HttpTransport transport, HttpInput<ByteBuffer> input)
        {
            super(connector,config,endPoint,transport,input);
        }

        @Override
        public void proxied(String protocol, String remoteAddress, String localAddress, int remotePort, int localPort)
        {
            _localAddr = InetSocketAddress.createUnresolved(localAddress, localPort);
            _remoteAddr = InetSocketAddress.createUnresolved(remoteAddress, remotePort);
        }

        @Override
        public InetSocketAddress getLocalAddress()
        {
            if (_localAddr != null)
                return _localAddr;
            return super.getLocalAddress();
        }

        @Override
        public InetSocketAddress getRemoteAddress()
        {
            if (_remoteAddr != null)
                return _remoteAddr;
            return super.getRemoteAddress();
        }

        @Override
        public void earlyEOF()
        {
            // If we have no request yet, just close
            if (getRequest().getMethod()==null)
                close();
            else
                super.earlyEOF();
        }

        @Override
        public boolean content(ByteBuffer item)
        {
            super.content(item);
            return true;
        }

        @Override
        public void badMessage(int status, String reason)
        {
            _generator.setPersistent(false);
            super.badMessage(status,reason);
        }

        @Override
        public boolean headerComplete()
        {
            boolean persistent;
            HttpVersion version = getHttpVersion();

            switch (version)
            {
                case HTTP_0_9:
                {
                    persistent = false;
                    break;
                }
                case HTTP_1_0:
                {
                    persistent = getRequest().getHttpFields().contains(HttpHeader.CONNECTION, HttpHeaderValue.KEEP_ALIVE.asString());
                    if (!persistent)
                        persistent = HttpMethod.CONNECT.is(getRequest().getMethod());
                    if (persistent)
                        getResponse().getHttpFields().add(HttpHeader.CONNECTION, HttpHeaderValue.KEEP_ALIVE);
                    break;
                }
                case HTTP_1_1:
                {
                    persistent = !getRequest().getHttpFields().contains(HttpHeader.CONNECTION, HttpHeaderValue.CLOSE.asString());
                    if (!persistent)
                        persistent = HttpMethod.CONNECT.is(getRequest().getMethod());
                    if (!persistent)
                        getResponse().getHttpFields().add(HttpHeader.CONNECTION, HttpHeaderValue.CLOSE);
                    break;
                }
                case HTTP_2:
                {
                    persistent=false;
                    badMessage(400,null);
                    return true;
                }
                default:
                {
                    throw new IllegalStateException();
                }
            }

            if (!persistent)
                _generator.setPersistent(false);

            if (!super.headerComplete())
                return false;

            // Should we delay dispatch until we have some content?
            // We should not delay if there is no content expect or client is expecting 100 or the response is already committed or the request buffer already has something in it to parse
            if (getHttpConfiguration().isDelayDispatchUntilContent() && _parser.getContentLength() > 0 &&
                    !isExpecting100Continue() && !isCommitted() && BufferUtil.isEmpty(_requestBuffer))
                return false;

            return true;
        }

        @Override
        protected void handleException(Throwable x)
        {
            _generator.setPersistent(false);
            super.handleException(x);
        }

        @Override
        public void abort()
        {
            super.abort();
            _generator.setPersistent(false);
        }

        @Override
        public boolean messageComplete()
        {
            super.messageComplete();
            return false;
        }
    }

    private class SendCallback extends IteratingCallback
    {
        private ResponseInfo _info;
        private ByteBuffer _content;
        private boolean _lastContent;
        private Callback _callback;
        private ByteBuffer _header;
        private boolean _shutdownOut;

        private SendCallback()
        {
            super(true);
        }

        private boolean reset(ResponseInfo info, ByteBuffer content, boolean last, Callback callback)
        {
            if (reset())
            {
                _info = info;
                _content = content;
                _lastContent = last;
                _callback = callback;
                _header = null;
                _shutdownOut = false;
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
            if (_callback==null)
                throw new IllegalStateException();

            ByteBuffer chunk = _chunk;
            while (true)
            {
                HttpGenerator.Result result = _generator.generateResponse(_info, _header, chunk, _content, _lastContent);
                if (LOG.isDebugEnabled())
                    LOG.debug("{} generate: {} ({},{},{})@{}",
                        this,
                        result,
                        BufferUtil.toSummaryString(_header),
                        BufferUtil.toSummaryString(_content),
                        _lastContent,
                        _generator.getState());

                switch (result)
                {
                    case NEED_HEADER:
                    {
                        _header = _bufferPool.acquire(_config.getResponseHeaderSize(), HEADER_BUFFER_DIRECT);
                        continue;
                    }
                    case NEED_CHUNK:
                    {
                        chunk = _chunk = _bufferPool.acquire(HttpGenerator.CHUNK_SIZE, CHUNK_BUFFER_DIRECT);
                        continue;
                    }
                    case FLUSH:
                    {
                        // Don't write the chunk or the content if this is a HEAD response, or any other type of response that should have no content
                        if (_channel.getRequest().isHead() || _generator.isNoContent())
                        {
                            BufferUtil.clear(chunk);
                            BufferUtil.clear(_content);
                        }

                        // If we have a header
                        if (BufferUtil.hasContent(_header))
                        {
                            if (BufferUtil.hasContent(_content))
                            {
                                if (BufferUtil.hasContent(chunk))
                                    getEndPoint().write(this, _header, chunk, _content);
                                else
                                    getEndPoint().write(this, _header, _content);
                            }
                            else
                                getEndPoint().write(this, _header);
                        }
                        else if (BufferUtil.hasContent(chunk))
                        {
                            if (BufferUtil.hasContent(_content))
                                getEndPoint().write(this, chunk, _content);
                            else
                                getEndPoint().write(this, chunk);
                        }
                        else if (BufferUtil.hasContent(_content))
                        {
                            getEndPoint().write(this, _content);
                        }
                        else
                        {
                            succeeded(); // nothing to write
                        }
                        return Action.SCHEDULED;
                    }
                    case SHUTDOWN_OUT:
                    {
                        _shutdownOut=true;
                        continue;
                    }
                    case DONE:
                    {
                        return Action.SUCCEEDED;
                    }
                    case CONTINUE:
                    {
                        break;
                    }
                    default:
                    {
                        throw new IllegalStateException("generateResponse="+result);
                    }
                }
            }
        }

        private void releaseHeader()
        {
            ByteBuffer h=_header;
            _header=null;
            if (h!=null)
                _bufferPool.release(h);
        }

        @Override
        protected void onCompleteSuccess()
        {
            releaseHeader();
            _callback.succeeded();
            if (_shutdownOut)
                getEndPoint().shutdownOutput();
        }

        @Override
        public void onCompleteFailure(final Throwable x)
        {
            releaseHeader();
            failedCallback(_callback,x);
            if (_shutdownOut)
                getEndPoint().shutdownOutput();
        }

        @Override
        public String toString()
        {
            return String.format("%s[i=%s,cb=%s]",super.toString(),_info,_callback);
        }
    }
}
