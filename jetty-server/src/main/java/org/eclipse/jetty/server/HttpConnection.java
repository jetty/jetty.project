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
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpHeaderValue;
import org.eclipse.jetty.http.HttpParser;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.io.AbstractConnection;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.EofException;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.FutureCallback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * <p>A {@link Connection} that handles the HTTP protocol.</p>
 */
public class HttpConnection extends AbstractConnection implements Runnable, HttpTransport
{
    public static final String UPGRADE_CONNECTION_ATTRIBUTE = "org.eclipse.jetty.server.HttpConnection.UPGRADE";
    private static final Logger LOG = Log.getLogger(HttpConnection.class);
    private static final ThreadLocal<HttpConnection> __currentConnection = new ThreadLocal<>();

    private final HttpChannelConfig _config;
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

    public HttpChannelConfig getHttpChannelConfig()
    {
        return _config;
    }

    public HttpConnection(HttpChannelConfig config, Connector connector, EndPoint endPoint)
    {
        super(endPoint, connector.getExecutor());

        _config = config;
        _connector = connector;
        _bufferPool = _connector.getByteBufferPool();
        _generator = new HttpGenerator(); // TODO: consider moving the generator to the transport, where it belongs
        _generator.setSendServerVersion(getServer().getSendServerVersion());
        _channel = new HttpChannelOverHttp(connector, config, endPoint, this, new Input());
        _parser = newHttpParser();

        LOG.debug("New HTTP Connection {}", this);
    }

    protected HttpParser newHttpParser()
    {
        return new HttpParser(newRequestHandler(), getHttpChannelConfig().getRequestHeaderSize());
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

    public void reset()
    {
        // If we are still expecting
        if (_channel.isExpecting100Continue())
        {
            // reset to avoid seeking remaining content
            _parser.reset();
            // close to seek EOF
            _parser.close();
        }
        // else if we are persistent
        else if (_generator.isPersistent())
            // reset to seek next request
            _parser.reset();
        else
            // else seek EOF
            _parser.close();

        _generator.reset();
        _channel.reset();

        releaseRequestBuffer();
        if (_chunk!=null)
        {
            _bufferPool.release(_chunk);
            _chunk=null;
        }
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
            ByteBuffer buffer=_requestBuffer;
            _requestBuffer=null;
            _bufferPool.release(buffer);
        }
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
                // Can the parser progress (even with an empty buffer)
                boolean event=_parser.parseNext(_requestBuffer==null?BufferUtil.EMPTY_BUFFER:_requestBuffer);

                // If there is a request buffer, we are re-entering here
                if (!event && BufferUtil.isEmpty(_requestBuffer))
                {
                    if (_requestBuffer == null)
                        _requestBuffer = _bufferPool.acquire(getInputBufferSize(), false);

                    int filled = getEndPoint().fill(_requestBuffer);
                    if (filled==0) // Do a retry on fill 0 (optimisation for SSL connections)
                        filled = getEndPoint().fill(_requestBuffer);

                    LOG.debug("{} filled {}", this, filled);

                    // If we failed to fill
                    if (filled == 0)
                    {
                        // Somebody wanted to read, we didn't so schedule another attempt
                        releaseRequestBuffer();
                        fillInterested();
                        return;
                    }
                    else if (filled < 0)
                    {
                        _parser.shutdownInput();
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
                        return;
                    }

                    // Parse what we have read
                    event=_parser.parseNext(_requestBuffer);
                }

                // Parse the buffer
                if (event)
                {
                    // Parse as much content as there is available before calling the channel
                    // this is both efficient (may queue many chunks), will correctly set available for 100 continues
                    // and will drive the parser to completion if all content is available.
                    while (_parser.inContentState())
                    {
                        if (!_parser.parseNext(_requestBuffer==null?BufferUtil.EMPTY_BUFFER:_requestBuffer))
                            break;
                    }

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
                    {
                        releaseRequestBuffer();
                        return;
                    }
                }
            }
        }
        catch (EofException e)
        {
            LOG.debug(e);
        }
        catch (IOException e)
        {
            if (_parser.isIdle())
                LOG.debug(e);
            else
                LOG.warn(this.toString(), e);
            close();
        }
        catch (Exception e)
        {
            LOG.warn(this.toString(), e);
            close();
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
    public void send(HttpGenerator.ResponseInfo info, ByteBuffer content, boolean lastContent) throws IOException
    {
        // TODO This is always blocking!  One of the important use-cases is to be able to write large static content without a thread

        // If we are still expecting a 100 continues
        if (_channel.isExpecting100Continue())
            // then we can't be persistent
            _generator.setPersistent(false);


        ByteBuffer header = null;
        ByteBuffer chunk = null;
        out: while (true)
        {
            HttpGenerator.Result result = _generator.generateResponse(info, header, chunk, content, lastContent);
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
                    header = _bufferPool.acquire(_config.getResponseHeaderSize(), false);
                    continue;
                }
                case NEED_CHUNK:
                {
                    chunk = _chunk;
                    if (chunk==null)
                        chunk = _chunk = _bufferPool.acquire(HttpGenerator.CHUNK_SIZE, false);
                    continue;
                }
                case FLUSH:
                {
                    // Don't write the chunk or the content if this is a HEAD response
                    if (_channel.getRequest().isHead())
                    {
                        BufferUtil.clear(chunk);
                        BufferUtil.clear(content);
                    }

                    // If we have a header
                    if (BufferUtil.hasContent(header))
                    {
                        // we know there will not be a chunk, so write either header+content or just the header
                        if (BufferUtil.hasContent(content))
                            blockingWrite(header, content);
                        else
                            blockingWrite(header);
                    }
                    else if (BufferUtil.hasContent(chunk))
                    {
                        if (BufferUtil.hasContent(content))
                            blockingWrite(chunk,content);
                        else
                            blockingWrite(chunk);
                    }
                    else if (BufferUtil.hasContent(content))
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
                    if (header!=null)
                        _bufferPool.release(header);
                    if (chunk!=null)
                        _bufferPool.release(chunk);
                    break out;
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

    @Override
    public void send(ByteBuffer content, boolean lastContent) throws IOException
    {
        send(null, content, lastContent);
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
    public void completed()
    {
        // Finish consuming the request
        if (_parser.isInContent() && _generator.isPersistent() && !_channel.isExpecting100Continue())
            // Complete reading the request
            _channel.getRequest().getHttpInput().consumeAll();

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
    }


    private class Input extends ByteBufferHttpInput
    {
        @Override
        protected void blockForContent() throws IOException
        {
            /* We extend the blockForContent method to replace the
            default implementation of a blocking queue with an implementation
            that uses the calling thread to block on a readable callback and
            then to do the parsing before before attempting the read.
            */
            try
            {
                while (true)
                {
                    // Can the parser progress (even with an empty buffer)
                    boolean event=_parser.parseNext(_requestBuffer==null?BufferUtil.EMPTY_BUFFER:_requestBuffer);

                    // If there is more content to parse, loop so we can queue all content from this buffer now without the
                    // need to call blockForContent again
                    while (event && BufferUtil.hasContent(_requestBuffer) && _parser.inContentState())
                        _parser.parseNext(_requestBuffer);

                    // If we have an event, return
                    if (event)
                        return;

                    // Do we have content ready to parse?
                    if (BufferUtil.isEmpty(_requestBuffer))
                    {
                        // If no more input
                        if (getEndPoint().isInputShutdown())
                        {
                            _parser.shutdownInput();
                            return;
                        }

                        // Wait until we can read
                        FutureCallback<Void> block=new FutureCallback<>();
                        getEndPoint().fillInterested(null,block);
                        LOG.debug("{} block readable on {}",this,block);
                        block.get();

                        // We will need a buffer to read into
                        if (_requestBuffer==null)
                            _requestBuffer=_bufferPool.acquire(getInputBufferSize(),false);

                        // read some data
                        int filled=getEndPoint().fill(_requestBuffer);
                        LOG.debug("{} block filled {}",this,filled);
                        if (filled<0)
                        {
                            _parser.shutdownInput();
                            return;
                        }
                    }
                }
            }
            catch (final InterruptedException x)
            {
                throw new InterruptedIOException(getEndPoint().toString()){{initCause(x);}};
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

    private class HttpChannelOverHttp extends HttpChannel<ByteBuffer>
    {
        public HttpChannelOverHttp(Connector connector, HttpChannelConfig config, EndPoint endPoint, HttpTransport transport, HttpInput<ByteBuffer> input)
        {
            super(connector,config,endPoint,transport,input);
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
                    persistent = false;
                    break;

                case HTTP_1_0:
                    persistent = getRequest().getHttpFields().contains(HttpHeader.CONNECTION, HttpHeaderValue.KEEP_ALIVE.asString());
                    if (persistent)
                        getResponse().getHttpFields().add(HttpHeader.CONNECTION, HttpHeaderValue.KEEP_ALIVE);
                    break;

                case HTTP_1_1:
                    persistent = !getRequest().getHttpFields().contains(HttpHeader.CONNECTION, HttpHeaderValue.CLOSE.asString());

                    if (!persistent)
                        getResponse().getHttpFields().add(HttpHeader.CONNECTION, HttpHeaderValue.CLOSE);

                    break;
                default:
                    throw new IllegalStateException();
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

    }


}
