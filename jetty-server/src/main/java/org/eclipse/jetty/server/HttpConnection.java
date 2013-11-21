//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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

import java.nio.ByteBuffer;
import java.nio.channels.ByteChannel;
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
import org.eclipse.jetty.util.IteratingNestedCallback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * <p>A {@link Connection} that handles the HTTP protocol.</p>
 */
public class HttpConnection extends AbstractConnection implements Runnable, HttpTransport
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


    public static HttpConnection getCurrentConnection()
    {
        return __currentConnection.get();
    }

    protected static void setCurrentConnection(HttpConnection connection)
    {
        __currentConnection.set(connection);
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
        LOG.debug("{} onFillable {}", this, _channel.getState());

        setCurrentConnection(this);
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
                        if (_requestBuffer == null)
                            _requestBuffer = _bufferPool.acquire(getInputBufferSize(), REQUEST_BUFFER_DIRECT);

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
                    suspended = !_channel.handle();
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
            setCurrentConnection(null);
            if (!suspended && getEndPoint().isOpen() && getEndPoint().getConnection()==this)
            {
                fillInterested();
            }
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
    public void run()
    {
        onFillable();
    }


    @Override
    public void send(ResponseInfo info, ByteBuffer content, boolean lastContent, Callback callback)
    {
        if (info==null)
            new ContentCallback(content,lastContent,callback).iterate();
        else
        {
            // If we are still expecting a 100 continues
            if (_channel.isExpecting100Continue())
                // then we can't be persistent
                _generator.setPersistent(false);
            new CommitCallback(info,content,lastContent,callback).iterate();
        }
    }

    @Override
    public void send(ByteBuffer content, boolean lastContent, Callback callback)
    {
        new ContentCallback(content,lastContent,callback).iterate();
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
                LOG.debug("Upgrade from {} to {}", this, connection);
                onClose();
                getEndPoint().setConnection(connection);
                connection.onOpen();
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
            // close to seek EOF
            _parser.close();
        else if (_parser.inContentState() && _generator.isPersistent())
            // Complete reading the request
            _channel.getRequest().getHttpInput().consumeAll();

        // Reset the channel, parsers and generator
        _channel.reset();
        if (_generator.isPersistent() && !_parser.isClosed())
            _parser.reset();
        else
            _parser.close();
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
                if (_requestBuffer == null)
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

    protected class HttpChannelOverHttp extends HttpChannel<ByteBuffer>
    {
        public HttpChannelOverHttp(Connector connector, HttpConfiguration config, EndPoint endPoint, HttpTransport transport, HttpInput<ByteBuffer> input)
        {
            super(connector,config,endPoint,transport,input);
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
                default:
                {
                    throw new IllegalStateException();
                }
            }

            if (!persistent)
                _generator.setPersistent(false);

            return super.headerComplete();
        }

        @Override
        protected void handleException(Throwable x)
        {
            _generator.setPersistent(false);
            super.handleException(x);
        }

        @Override
        public void failed()
        {
            getEndPoint().shutdownOutput();
        }
    }

    private class CommitCallback extends IteratingNestedCallback
    {
        final ByteBuffer _content;
        final boolean _lastContent;
        final ResponseInfo _info;
        ByteBuffer _header;

        CommitCallback(ResponseInfo info, ByteBuffer content, boolean last, Callback callback)
        {
            super(callback);
            _info=info;
            _content=content;
            _lastContent=last;
        }

        @Override
        public State process() throws Exception
        {
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
                        // Look for optimisation to avoid allocating a _header buffer
                        if (_lastContent && _content!=null && !_content.isReadOnly() && _content.hasArray() && BufferUtil.space(_content)>_config.getResponseHeaderSize() )
                        {
                            // use spare space in content buffer for header buffer
                            int p=_content.position();
                            int l=_content.limit();
                            _content.position(l);
                            _content.limit(l+_config.getResponseHeaderSize());
                            _header=_content.slice();
                            _header.limit(0);
                            _content.position(p);
                            _content.limit(l);
                        }
                        else
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
                        // Don't write the chunk or the content if this is a HEAD response
                        if (_channel.getRequest().isHead())
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
                            continue;
                        return State.SCHEDULED;
                    }
                    case SHUTDOWN_OUT:
                    {
                        getEndPoint().shutdownOutput();
                        continue;
                    }
                    case DONE:
                    {
                        if (_header!=null)
                        {
                            // don't release header in spare content buffer
                            if (!_lastContent || _content==null || !_content.hasArray() || !_header.hasArray() ||  _content.array()!=_header.array())
                                _bufferPool.release(_header);
                        }
                        return State.SUCCEEDED;
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
    }

    private class ContentCallback extends IteratingNestedCallback
    {
        final ByteBuffer _content;
        final boolean _lastContent;

        ContentCallback(ByteBuffer content, boolean last, Callback callback)
        {
            super(callback);
            _content=content;
            _lastContent=last;
        }

        @Override
        public State process() throws Exception
        {
            ByteBuffer chunk = _chunk;
            while (true)
            {
                HttpGenerator.Result result = _generator.generateResponse(null, null, chunk, _content, _lastContent);
                if (LOG.isDebugEnabled())
                    LOG.debug("{} generate: {} ({},{})@{}",
                        this,
                        result,
                        BufferUtil.toSummaryString(_content),
                        _lastContent,
                        _generator.getState());

                switch (result)
                {
                    case NEED_HEADER:
                        throw new IllegalStateException();
                    case NEED_CHUNK:
                    {
                        chunk = _chunk = _bufferPool.acquire(HttpGenerator.CHUNK_SIZE, CHUNK_BUFFER_DIRECT);
                        continue;
                    }
                    case FLUSH:
                    {
                        // Don't write the chunk or the content if this is a HEAD response
                        if (_channel.getRequest().isHead())
                        {
                            BufferUtil.clear(chunk);
                            BufferUtil.clear(_content);
                            continue;
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
                            continue;
                        return State.SCHEDULED;
                    }
                    case SHUTDOWN_OUT:
                    {
                        getEndPoint().shutdownOutput();
                        continue;
                    }
                    case DONE:
                    {
                        return State.SUCCEEDED;
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
    }

}
