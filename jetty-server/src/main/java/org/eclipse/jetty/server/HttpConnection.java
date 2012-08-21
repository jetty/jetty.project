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
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;

import org.eclipse.jetty.http.HttpGenerator;
import org.eclipse.jetty.http.HttpGenerator.ResponseInfo;
import org.eclipse.jetty.http.HttpParser;
import org.eclipse.jetty.http.HttpStatus;
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
 * A Connection that handles the HTTP protocol
 */
public class HttpConnection extends AbstractConnection
{
    private static final Logger LOG = Log.getLogger(HttpConnection.class);

    private static final ThreadLocal<HttpConnection> __currentConnection = new ThreadLocal<>();
    private static final FutureCallback<Void> __completed = new FutureCallback<>();
    static
    {
        __completed.completed(null);
    }

    public static final String UPGRADE_CONNECTION_ATTRIBUTE = "org.eclispe.jetty.server.HttpConnection.UPGRADE";


    private final Server _server;
    private final HttpConfiguration _httpConfig;
    private final Connector _connector;
    private final HttpParser _parser;
    private final HttpGenerator _generator;
    private final HttpChannel _channel;
    private final ByteBufferPool _bufferPool;

    private ResponseInfo _info;
    private ByteBuffer _requestBuffer=null;
    private ByteBuffer _chunk=null;
    private int _headerBytes;


    /* ------------------------------------------------------------ */
    public static HttpConnection getCurrentConnection()
    {
        return __currentConnection.get();
    }

    /* ------------------------------------------------------------ */
    protected static void setCurrentConnection(HttpConnection connection)
    {
        __currentConnection.set(connection);
    }

    /* ------------------------------------------------------------ */
    public HttpConnection(HttpConfiguration config, Connector connector, EndPoint endPoint)
    {
        super(endPoint,connector.getExecutor());

        _httpConfig=config;
        _connector = connector;
        _bufferPool=_connector.getByteBufferPool();
        _server = connector.getServer();
        _channel = new HttpChannel(connector, config, endPoint, new HttpTransportOverHttp(_bufferPool, _httpConfig, endPoint));
        _parser = new HttpParser(_channel.getEventHandler());
        _generator = new HttpGenerator();
        _generator.setSendServerVersion(_server.getSendServerVersion());

        LOG.debug("New HTTP Connection {}",this);
    }

    /* ------------------------------------------------------------ */
    /**
     * @return the parser used by this connection
     */
    public HttpParser getParser()
    {
        return _parser;
    }


    /* ------------------------------------------------------------ */
    public Server getServer()
    {
        return _server;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return Returns the connector.
     */
    public Connector getConnector()
    {
        return _connector;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return Returns the HttpChannel.
     */
    public HttpChannel getHttpChannel()
    {
        return _channel;
    }

    /* ------------------------------------------------------------ */
    public void reset()
    {
        if (_generator.isPersistent())
            _parser.reset();
        else
            _parser.close();

        _generator.reset();
        _channel.reset();
        releaseRequestBuffer();
        if (_chunk!=null)
            _bufferPool.release(_chunk);
        _chunk=null;
        _info=null;
    }


    /* ------------------------------------------------------------ */
    public HttpGenerator getGenerator()
    {
        return _generator;
    }


    /* ------------------------------------------------------------ */
    @Override
    public String toString()
    {
        return String.format("%s,g=%s,p=%s",
                super.toString(),
                _generator,
                _parser);
    }

    /* ------------------------------------------------------------ */
    private void releaseRequestBuffer()
    {
        if (_requestBuffer!=null && !_requestBuffer.hasRemaining())
        {
            _bufferPool.release(_requestBuffer);
            _requestBuffer=null;
        }
    }

    /* ------------------------------------------------------------ */
    /** Parse and handle HTTP messages.
     * <p>
     * This method is normally called as the {@link AbstractConnection} onReadable callback.
     * However, it can also be called {@link HttpChannelOverHttp#completed()} if there is unconsumed
     * data in the _requestBuffer, as a result of resuming a suspended request when there is a pipelined
     * request already read into the buffer.
     * <p>
     * This method will fill data and parse it until either: EOF is filled; 0 bytes are filled;
     * the HttpChannel becomes !idle; or the connection has been changed
     */
    @Override
    public void onFillable()
    {
        LOG.debug("{} onReadable {}",this,_channel.isIdle());

        int filled=-2;

        try
        {
            setCurrentConnection(this);

            // TODO try to generalize this loop into AbstractConnection
            while (true)
            {
                // Fill the request buffer with data only if it is totally empty.
                if (BufferUtil.isEmpty(_requestBuffer))
                {
                    if (_requestBuffer==null)
                        _requestBuffer=_bufferPool.acquire(_httpConfig.getRequestHeaderSize(),false);  // TODO may acquire on speculative read. probably released to early

                    filled=getEndPoint().fill(_requestBuffer);

                    LOG.debug("{} filled {}",this,filled);

                    // If we failed to fill
                    if (filled==0)
                    {
                        // Somebody wanted to read, we didn't so schedule another attempt
                        releaseRequestBuffer();
                        fillInterested();
                        return;
                    }
                    else if (filled<0)
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
                        return;
                    }
                    else
                    {
                        _headerBytes+=filled;
                    }
                }

                // Parse the buffer
                if (_parser.parseNext(_requestBuffer))
                {
                    // reset header count
                    _headerBytes=0;

                    // For most requests, there will not be a body, so we can try to recycle the buffer now
                    releaseRequestBuffer();

                    if (!_channel.getRequest().isPersistent())
                        _generator.setPersistent(false);

                    // The parser returned true, which indicates the channel is ready to handle a request.
                    // Call the channel and this will either handle the request/response to completion OR,
                    // if the request suspends, the request/response will be incomplete so the outer loop will exit.
                    boolean complete = _channel.handle();

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

                    HttpConnection.this.reset();

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
                                    // TODO: avoid object creation
                                    getExecutor().execute(new Runnable()
                                    {
                                        @Override
                                        public void run()
                                        {
                                            onFillable();
                                        }
                                    });
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
                    if (getEndPoint().getConnection()!=this)
                        return;
                }
                else if (_headerBytes>= _httpConfig.getRequestHeaderSize())
                {
                    _parser.reset();
                    _parser.close();
                    _channel.getEventHandler().badMessage(HttpStatus.REQUEST_ENTITY_TOO_LARGE_413,null);
                }
            }
        }
        catch(IOException e)
        {
            if (_parser.isIdle())
                LOG.debug(e);
            else
                LOG.warn(this.toString(),e);
            getEndPoint().close();
        }
        catch(Exception e)
        {
            LOG.warn(this.toString(),e);
            getEndPoint().close();
        }
        finally
        {
            setCurrentConnection(null);
        }
    }

    /* ------------------------------------------------------------ */
    @Override
    public void onOpen()
    {
        super.onOpen();
        fillInterested();
    }

    /* ------------------------------------------------------------ */
    @Override
    public void onClose()
    {
        super.onClose();
        _channel.onClose();
    }


    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    private class HttpChannelOverHttp extends HttpChannel implements Runnable
    {
        private HttpChannelOverHttp(Server server)
        {
            super(_connector, _httpConfig, HttpConnection.this.getEndPoint(), new HttpTransportOverHttp(_bufferPool, _httpConfig, HttpConnection.this.getEndPoint()));
        }

        public Connector getConnector()
        {
            return _connector;
        }

        public HttpConfiguration getHttpConfiguration()
        {
            return _httpConfig;
        }

        @Override
        protected boolean commitError(int status, String reason, String content)
        {
            if (!super.commitError(status,reason,content))
            {
                // TODO - should this just be a close and we don't worry about a RST overtaking a flushed response?

                // We could not send the error, so a shutdown of the connection will at least tell
                // the client something is wrong
                getEndPoint().shutdownOutput();
                _generator.abort();
                return false;
            }
            return true;
        }

        @Override
        protected void completed()
        {
            // This is called by HttpChannel#handle when it knows that it's handling of the request/response cycle
            // is complete.  This may be in the original thread dispatched to the connection that has called process from
            // the connection#onFillable method, or it may be from a thread dispatched to call process as the result
            // of a resumed suspended request.
            // At this point the HttpChannel will have completed the generation of any response (although it might remain to
            // be asynchronously flushed TBD), but it may not have consumed the entire

            LOG.debug("{} completed");

            // Handle connection upgrades
            if (getResponse().getStatus()==HttpStatus.SWITCHING_PROTOCOLS_101)
            {
                Connection connection=(Connection)getRequest().getAttribute(UPGRADE_CONNECTION_ATTRIBUTE);
                if (connection!=null)
                {
                    LOG.debug("Upgrade from {} to {}",this,connection);
                    getEndPoint().setConnection(connection);
                    HttpConnection.this.reset();
                    return;
                }
            }


            // Reset everything for the next cycle.
            HttpConnection.this.reset();

            // are called from non connection thread (ie dispatched from a resume)
            if (getCurrentConnection()!=HttpConnection.this)
            {
                if (_parser.isStart())
                {
                    // it wants to eat more
                    if (_requestBuffer==null)
                        fillInterested();
                    else if (getConnector().isStarted())
                    {
                        LOG.debug("{} pipelined",this);

                        try
                        {
                            execute(this);
                        }
                        catch(RejectedExecutionException e)
                        {
                            if (getConnector().isStarted())
                                LOG.warn(e);
                            else
                                LOG.ignore(e);
                            getEndPoint().close();
                        }
                    }
                    else
                        getEndPoint().close();
                }

                if (_parser.isClosed()&&!getEndPoint().isOutputShutdown())
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

        /* ------------------------------------------------------------ */
        @Override
        public void run()
        {
            onFillable();
        }

        /* ------------------------------------------------------------ */
//        @Override
        public void flush(ByteBuffer content, boolean last) throws IOException
        {
            // Only one response writer at a time.
            synchronized(this)
            {
                ByteBuffer header=null;
                try
                {
                    if (_generator.isEnd())
                    {
                        // TODO do we need this escape?
                        if (last && BufferUtil.isEmpty(content))
                            return;
                        throw new EofException();
                    }

                    loop: while (true)
                    {
                        HttpGenerator.Result result=_generator.generateResponse(_info,header,content,last);
                        if (LOG.isDebugEnabled())
                            LOG.debug("{} generate: {} ({},{},{})@{}",
                                this,
                                result,
                                BufferUtil.toSummaryString(header),
                                BufferUtil.toSummaryString(content),
                                last,
                                _generator.getState());

                        switch(result)
                        {
                            case NEED_INFO:
                                if (_info==null)
                                    _info=_channel.getEventHandler().commit();
                                continue;

                            case NEED_HEADER:
                                if (header!=null)
                                    _bufferPool.release(header);
                                header=_bufferPool.acquire(_httpConfig.getResponseHeaderSize(),false);
                                continue;

                            case NEED_CHUNK:
                                if (header!=null)
                                    _bufferPool.release(header);
                                header=_bufferPool.acquire(HttpGenerator.CHUNK_SIZE,false);
                                continue;

                            case FLUSH:
                                if (_info.isHead())
                                {
                                    write(header,null).get();
                                    BufferUtil.clear(content);
                                }
                                else
                                    write(header,content).get();

                                continue;

                            case SHUTDOWN_OUT:
                                getEndPoint().shutdownOutput();
                                continue;

                            case DONE:
                                break loop;
                        }
                    }
                }
                catch(InterruptedException e)
                {
                    LOG.debug(e);
                }
                catch(ExecutionException e)
                {
                    LOG.debug(e);
                    if (e.getCause() instanceof IOException)
                        throw (IOException)e.getCause();
                    throw new RuntimeException(e);
                }
                finally
                {
                    if (header!=null)
                        _bufferPool.release(header);
                }
            }
        }

//        @Override
        protected FutureCallback<Void> write(ResponseInfo info, ByteBuffer content) throws IOException
        {
            // Only one response writer at a time.
            synchronized(this)
            {
                ByteBuffer header=null;
                try
                {
                    if (_generator.isEnd())
                        throw new EofException();

                    FutureCallback<Void> fcb=null;

                    loop: while (true)
                    {
                        HttpGenerator.Result result=_generator.generateResponse(info,header,content,true);
                        if (LOG.isDebugEnabled())
                            LOG.debug("{} send: {} ({},{})@{}",
                                this,
                                result,
                                BufferUtil.toSummaryString(header),
                                BufferUtil.toSummaryString(content),
                                _generator.getState());

                        switch(result)
                        {
                            case NEED_INFO:
                                throw new IllegalStateException();

                            case NEED_HEADER:
                                if (header!=null)
                                    _bufferPool.release(header);
                                header=_bufferPool.acquire(_httpConfig.getResponseHeaderSize(),false);
                                continue;

                            case NEED_CHUNK:
                                if (header!=null)
                                    _bufferPool.release(header);
                                header=_bufferPool.acquire(HttpGenerator.CHUNK_SIZE,false);
                                continue;

                            case FLUSH:
                                if(info.isHead())
                                {
                                    BufferUtil.clear(content);
                                    fcb=write(header,null);
                                }
                                else
                                    fcb=write(header,content);
                                continue;

                            case SHUTDOWN_OUT:
                                getEndPoint().shutdownOutput();
                                continue;

                            case DONE:
                                if (fcb==null)
                                    fcb=__completed;
                                break loop;
                        }
                    }
                    return fcb;
                }
                finally
                {
                    if (header!=null)
                        _bufferPool.release(header);
                }
            }
        }

        @Override
        public ScheduledExecutorService getScheduler()
        {
            return _connector.getScheduler();
        }

        @Override
        protected void execute(Runnable task)
        {
            _connector.getExecutor().execute(task);
        }

        private FutureCallback<Void> write(ByteBuffer b0,ByteBuffer b1)
        {
            FutureCallback<Void> fcb=new FutureCallback<>();
            if (BufferUtil.hasContent(b0))
            {
                if (BufferUtil.hasContent(b1))
                {
                    getEndPoint().write(null,fcb,b0,b1);
                }
                else
                {
                    getEndPoint().write(null,fcb,b0);
                }
            }
            else
            {
                if (BufferUtil.hasContent(b1))
                {
                    getEndPoint().write(null,fcb,b1);
                }
                else
                {
                    fcb.completed(null);
                }
            }
            return fcb;
        }

    }

    private class HttpHttpInput extends HttpInput
    {
        @Override
        protected void blockForContent() throws IOException
        {
            /* We extend the blockForContent method to replace the
            default implementation of a blocking queue with an implementation
            that uses the calling thread to block on a readable callback and
            then to do the parsing before before attempting the read.
            */

            // While progress and the connection has not changed
            boolean parsed_event=_parser.parseNext(_requestBuffer==null?BufferUtil.EMPTY_BUFFER:_requestBuffer);
            while (!parsed_event && !getEndPoint().isInputShutdown())
            {
                try
                {
                    // Do we have content ready to parse?
                    if (BufferUtil.isEmpty(_requestBuffer))
                    {
                        // Wait until we can read
                        FutureCallback<Void> block=new FutureCallback<>();
                        getEndPoint().fillInterested(null,block);
                        LOG.debug("{} block readable on {}",this,block);
                        block.get();

                        // We will need a buffer to read into
                        if (_requestBuffer==null)
                            _requestBuffer=_bufferPool.acquire(_httpConfig.getRequestBufferSize(),false);

                        int filled=getEndPoint().fill(_requestBuffer);
                        LOG.debug("{} block filled {}",this,filled);
                        if (filled<0)
                            _parser.inputShutdown();
                    }

                    // If we parse to an event, return
                    while (BufferUtil.hasContent(_requestBuffer) && _parser.inContentState())
                            parsed_event|=_parser.parseNext(_requestBuffer);
                    if (parsed_event)
                        return;
                }
                catch (InterruptedException e)
                {
                    LOG.debug(e);
                }
                catch (ExecutionException e)
                {
                    LOG.debug(e);
                    FutureCallback.rethrow(e);
                }
            }
        }

        @Override
        public int available()
        {
            int available=super.available();
            if (available==0 && _parser.isInContent() && BufferUtil.hasContent(_requestBuffer))
                return 1;
            return available;
        }

        @Override
        public void consumeAll()
        {
            // Consume content only if the connection is persistent
            if (!_generator.isPersistent())
            {
                _parser.setState(HttpParser.State.CLOSED);
                synchronized (lock())
                {
                    _inputQ.clear();
                }
            }
            else
            {
                while (true)
                {
                    synchronized (lock())
                    {
                        _inputQ.clear();
                    }
                    if (_parser.isComplete() || _parser.isClosed())
                        return;
                    try
                    {
                        blockForContent();
                    }
                    catch(IOException e)
                    {
                        LOG.warn(e);
                    }
                }
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
}
