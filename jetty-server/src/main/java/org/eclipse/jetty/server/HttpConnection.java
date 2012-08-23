//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
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
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;

import org.eclipse.jetty.http.HttpGenerator;
import org.eclipse.jetty.http.HttpParser;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.AbstractConnection;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.FutureCallback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * <p>A {@link Connection} that handles the HTTP protocol.</p>
 */
public class HttpConnection extends AbstractConnection implements Runnable, HttpTransport
{
    public static final String UPGRADE_CONNECTION_ATTRIBUTE = "org.eclispe.jetty.server.HttpConnection.UPGRADE";
    private static final Logger LOG = Log.getLogger(HttpConnection.class);
    private static final ThreadLocal<HttpConnection> __currentConnection = new ThreadLocal<>();

    private final HttpConfiguration _configuration;
    private final Connector _connector;
    private final ByteBufferPool _bufferPool;
    private final Server _server;
    private final HttpGenerator _generator;
    private final HttpChannelOverHttp _channel;
    private final HttpParser _parser;
    private ByteBuffer _requestBuffer = null;

    public static HttpConnection getCurrentConnection()
    {
        return __currentConnection.get();
    }

    protected static void setCurrentConnection(HttpConnection connection)
    {
        __currentConnection.set(connection);
    }

    public HttpConnection(HttpConfiguration config, Connector connector, EndPoint endPoint)
    {
        super(endPoint, connector.getExecutor());

        _configuration = config;
        _connector = connector;
        _bufferPool = _connector.getByteBufferPool();
        _server = connector.getServer();
        _generator = new HttpGenerator(); // TODO: consider moving the generator to the transport, where it belongs
        _generator.setSendServerVersion(_server.getSendServerVersion());
        _channel = new HttpChannelOverHttp(connector, config, endPoint, this, new Input());
        _parser = new HttpParser(_channel,config.getRequestHeaderSize());

        LOG.debug("New HTTP Connection {}", this);
    }

    public Server getServer()
    {
        return _server;
    }

    public Connector getConnector()
    {
        return _connector;
    }

    public HttpChannel getHttpChannel()
    {
        return _channel;
    }

    public void reset()
    {
        if (_generator.isPersistent())
            _parser.reset();
        else
            _parser.close();

        _generator.reset();
        _channel.reset();
        releaseRequestBuffer();
    }

    @Override
    public String toString()
    {
        return String.format("%s,g=%s,p=%s",
                super.toString(),
                _generator,
                _parser);
    }

    private void releaseRequestBuffer()
    {
        if (_requestBuffer != null && !_requestBuffer.hasRemaining())
        {
            _bufferPool.release(_requestBuffer);
            _requestBuffer = null;
        }
    }

    protected boolean readAndParse() throws IOException
    {
        // If there is a request buffer, we are re-entering here
        if (_requestBuffer == null)
        {
            _requestBuffer = _bufferPool.acquire(_configuration.getRequestHeaderSize(), false);

            int filled = getEndPoint().fill(_requestBuffer);

            LOG.debug("{} filled {}", this, filled);

            // If we failed to fill
            if (filled == 0)
            {
                // Somebody wanted to read, we didn't so schedule another attempt
                releaseRequestBuffer();
                fillInterested();
                return false;
            }
            else if (filled < 0)
            {
                _parser.inputShutdown();
                // We were only filling if fully consumed, so if we have
                // read -1 then we have nothing to parse and thus nothing that
                // will generate a response.  If we had a suspended request pending
                // a response or a request waiting in the buffer, we would not be here.
                if (getEndPoint().isOutputShutdown())
                    getEndPoint().close();
                else
                    getEndPoint().shutdownOutput();
                // buffer must be empty and the channel must be idle, so we can release.
                releaseRequestBuffer();
                return false;
            }
        }

        // Parse the buffer
        return _parser.parseNext(_requestBuffer);
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
        try
        {
            while (true)
            {
                if (readAndParse())
                {
                    // The parser returned true, which indicates the channel is ready to handle a request.
                    // Call the channel and this will either handle the request/response to completion OR,
                    // if the request suspends, the request/response will be incomplete so the outer loop will exit.
                    _channel.run(); 
                    
                    // Return if the channel is still processing the request
                    if (_channel.getState().isSuspending())
                    {
                        // release buffer if no input being held.
                        // This is needed here to handle the case of no request input.  If there
                        // is request input, then the release is handled by Input@onAllContentConsumed()
                        if (_channel.getRequest().getHttpInput().available()==0)
                            releaseRequestBuffer();
                        return;
                    }

                    // return if the connection has been changed
                    if (getEndPoint().getConnection()!=this)
                        return;
                }
                else
                {
                    releaseRequestBuffer();
                }
            }
        }
        catch (IOException e)
        {
            if (_parser.isIdle())
                LOG.debug(e);
            else
                LOG.warn(this.toString(), e);
            getEndPoint().close();
        }
        catch (Exception e)
        {
            LOG.warn(this.toString(), e);
            getEndPoint().close();
        }
        finally
        {
            setCurrentConnection(null);
        }
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
    public void commit(HttpGenerator.ResponseInfo info, ByteBuffer content, boolean lastContent) throws IOException
    {
        // TODO This is always blocking!  One of the important use-cases is to be able to write large static content without a thread

        ByteBuffer header = null;
        out: while (true)
        {
            HttpGenerator.Result result = _generator.generateResponse(info, header, content, lastContent);
            if (LOG.isDebugEnabled())
                LOG.debug("{} generate: {} ({},{},{})@{}",
                        this,
                        result,
                        BufferUtil.toSummaryString(header),
                        BufferUtil.toSummaryString(content),
                        lastContent,
                        _generator.getState());

            switch (result)
            {
                case NEED_HEADER:
                {
                    if (header != null)
                        _bufferPool.release(header);
                    header = _bufferPool.acquire(_configuration.getResponseHeaderSize(), false);
                    continue;
                }
                case NEED_CHUNK:
                {
                    if (header != null)
                        _bufferPool.release(header);
                    header = _bufferPool.acquire(HttpGenerator.CHUNK_SIZE, false);
                    continue;
                }
                case FLUSH:
                {
                    if (_channel.getRequest().isHead())
                    {
                        BufferUtil.clear(content);
                        if (BufferUtil.hasContent(header))
                            blockingWrite(header);
                    }
                    else if (BufferUtil.hasContent(header))
                    {
                        if (BufferUtil.hasContent(content))
                            blockingWrite(header, content);
                        else
                            blockingWrite(header);
                    }
                    else  if (BufferUtil.hasContent(content))
                    {
                        blockingWrite(content);
                    }
                    continue;
                }
                case SHUTDOWN_OUT:
                {
                    getEndPoint().shutdownOutput();
                    continue;
                }
                case DONE:
                {
                    break out;
                }
                case CONTINUE:
                {
                    break;
                }
                default:
                {
                    throw new IllegalStateException();
                }
            }
        }
    }

    @Override
    public void write(ByteBuffer content, boolean lastContent) throws IOException
    {
        commit(null, content, lastContent);
    }

    private void blockingWrite(ByteBuffer... bytes) throws IOException
    {
        try
        {
            FutureCallback<Void> callback = new FutureCallback<>();
            getEndPoint().write(null, callback, bytes);
            callback.get();
        }
        catch (InterruptedException x)
        {
            throw (IOException)new InterruptedIOException().initCause(x);
        }
        catch (ExecutionException x)
        {
            Throwable cause = x.getCause();
            if (cause instanceof IOException)
                throw (IOException)cause;
            else if (cause instanceof Exception)
                throw new IOException(cause);
            else
                throw (Error)cause;
        }
    }

    @Override
    public void httpChannelCompleted()
    {
        // Finish consuming the request
        if (_parser.isInContent() && _generator.isPersistent())
            // Complete reading the request
            _channel.getRequest().getHttpInput().consumeAll();
        
        // Handle connection upgrades
        if (_channel.getResponse().getStatus() == HttpStatus.SWITCHING_PROTOCOLS_101)
        {
            Connection connection = (Connection)_channel.getRequest().getAttribute(UPGRADE_CONNECTION_ATTRIBUTE);
            if (connection != null)
            {
                LOG.debug("Upgrade from {} to {}", this, connection);
                getEndPoint().setConnection(connection);
            }
        }

        reset();

        // Is this thread dispatched from a resume ?
        if (getCurrentConnection() != HttpConnection.this)
        {
            if (_parser.isStart())
            {
                // it wants to eat more
                if (_requestBuffer == null)
                {
                    fillInterested();
                }
                else if (getConnector().isStarted())
                {
                    LOG.debug("{} pipelined", this);

                    try
                    {
                        getExecutor().execute(this);
                    }
                    catch (RejectedExecutionException e)
                    {
                        if (getConnector().isStarted())
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

            if (_parser.isClosed() && !getEndPoint().isOutputShutdown())
            {
                // TODO This is a catch all indicating some protocol handling failure
                // Currently needed for requests saying they are HTTP/2.0.
                // This should be removed once better error handling is in place
                LOG.warn("Endpoint output not shutdown when seeking EOF");
                getEndPoint().shutdownOutput();
            }
        }

        // make sure that an oshut connection is driven towards close
        // TODO this is a little ugly
        if (getEndPoint().isOpen() && getEndPoint().isOutputShutdown())
        {
            fillInterested();
        }

        // return if the connection has been changed
        if (getEndPoint().getConnection() != this)
            return;
   
    }
    
    
    private class Input extends HttpInput
    {
        @Override
        protected void blockForContent() throws IOException
        {
            try
            {
                while (true)
                {
                    FutureCallback<Void> callback = new FutureCallback<>();
                    getEndPoint().fillInterested(null, callback);
                    callback.get();
                    if (readAndParse())
                        break;
                    else
                        releaseRequestBuffer();
                }
            }
            catch (InterruptedException x)
            {
                throw new InterruptedIOException();
            }
            catch (ExecutionException e)
            {
                FutureCallback.rethrow(e);
            }
        }

        @Override
        protected void onContentQueued(ByteBuffer ref)
        {
            /* This callback could be used to tell the connection
             * that the request did contain content and thus the request
             * buffer needs to be held until a call to #onAllContentConsumed
             *
             * However it turns out that nothing is needed here because either a
             * request will have content, in which case the request buffer will be
             * released by a call to onAllContentConsumed; or it will not have content.
             * If it does not have content, either it will complete quickly and the
             * buffers will be released in completed() or it will be suspended and
             * onReadable() contains explicit handling to release if it is suspended.
             *
             * We extend this method anyway, to turn off the notify done by the
             * default implementation as this is not needed by our implementation
             * of blockForContent
             */
        }


        @Override
        protected void onAllContentConsumed()
        {
            /* This callback tells the connection that all content that has
             * been parsed has been consumed. Thus the request buffer may be
             * released if it is empty.
             */
            releaseRequestBuffer();
        }
    }
    
    private class HttpChannelOverHttp extends HttpChannel
    {
        public HttpChannelOverHttp(Connector connector, HttpConfiguration configuration, EndPoint endPoint, HttpTransport transport, HttpInput input)
        {
            super(connector,configuration,endPoint,transport,input);
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
            boolean result= super.headerComplete();
            if (!getRequest().isPersistent())
                _generator.setPersistent(false);
            return result;
        }
        
    }
    
    
}
