// ========================================================================
// Copyright (c) 2004-2011 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses.
// ========================================================================

package org.eclipse.jetty.server;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Timer;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.eclipse.jetty.http.HttpException;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpGenerator;
import org.eclipse.jetty.http.HttpGenerator.Action;
import org.eclipse.jetty.http.HttpGenerator.ResponseInfo;
import org.eclipse.jetty.http.HttpParser;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.io.AbstractAsyncConnection;
import org.eclipse.jetty.io.AsyncConnection;
import org.eclipse.jetty.io.AsyncEndPoint;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.EofException;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.FutureCallback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.Timeout.Task;

/**
 */
public class HttpConnection extends AbstractAsyncConnection
{

    private static final Logger LOG = Log.getLogger(HttpConnection.class);

    private static final ThreadLocal<HttpConnection> __currentConnection = new ThreadLocal<HttpConnection>();

    private final Lock _lock = new ReentrantLock();
    private final Server _server;
    private final HttpConnector _connector;
    private final HttpParser _parser;
    private final HttpGenerator _generator;
    private final HttpChannel _channel;
    private final ByteBufferPool _bufferPool;
    
    private ResponseInfo _info;
    FutureCallback<Void> _writeFuture;
    ByteBuffer _requestBuffer=null;
    ByteBuffer _responseHeader=null;
    ByteBuffer _chunk=null;
    ByteBuffer _responseBuffer=null; 
    
    
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
    /** Constructor
     *
     */
    public HttpConnection(HttpConnector connector, AsyncEndPoint endpoint, Server server)
    {
        super(endpoint,connector.getServer().getThreadPool());
        _connector = connector;
        _bufferPool=_connector.getByteBufferPool();
        if (_bufferPool==null)
            new Throwable().printStackTrace();
        
        _server = server;
        
        _channel = new HttpChannelOverHttp(server);
       
        _parser = new HttpParser(_channel.getEventHandler());
        _generator = new HttpGenerator();
        LOG.debug("New HTTP Connection {}",this);
    }

    /* ------------------------------------------------------------ */
    @Override
    public void onOpen()
    {
        LOG.debug("Opened HTTP Connection {}",this);
        super.onOpen();
        scheduleOnReadable();
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
        _parser.reset();
        _generator.reset();
        _channel.reset();
        if (_requestBuffer!=null)
            _bufferPool.release(_requestBuffer);
        _requestBuffer=null;
        if (_responseHeader!=null)
            _bufferPool.release(_responseHeader);
        _responseHeader=null;
        if (_responseBuffer!=null)
            _bufferPool.release(_responseBuffer);
        _responseBuffer=null;
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
    @Override
    public void onReadable()
    {
        AsyncConnection connection = this;
        boolean progress=true;
        boolean suspended=false;

        try
        {
            setCurrentConnection(this);

            // While progress and the connection has not changed
            while (progress && connection==this)
            {
                progress=false;
                try
                {
                    // We will need a buffer to read into
                    if (_requestBuffer==null)
                        _requestBuffer=_parser.isInContent()
                        ?_bufferPool.acquire(_connector.getRequestBufferSize(),false)
                        :_bufferPool.acquire(_connector.getRequestHeaderSize(),false);
                        
                    int filled=getEndPoint().fill(_requestBuffer);
                    System.err.println("filled="+filled+" to "+BufferUtil.toDetailString(_requestBuffer)+" from "+getEndPoint());
                    
                    // If we parse to an event, call the connection
                    if (BufferUtil.hasContent(_requestBuffer) && _parser.parseNext(_requestBuffer))
                    {
                        // don't check for idle while dispatched (unless blocking IO is done).
                        getEndPoint().setCheckForIdle(false);
                        try
                        {
                            _channel.handleRequest();
                        }
                        finally
                        {
                            // If we are not suspended
                            if (!_channel.getRequest().getAsyncContinuation().isAsyncStarted())
                                // reenable idle checking unless request is suspended
                                getEndPoint().setCheckForIdle(true);
                        }
                    }

                }
                catch (HttpException e)
                {
                    progress=true;
                    _channel.sendError(e.getStatus(), e.getReason(), null, true);
                }
                finally
                {
                    // Return empty request buffer if all has been consumed
                    if (_requestBuffer!=null && !_requestBuffer.hasRemaining() && _channel.available()==0)
                    {
                        _bufferPool.release(_requestBuffer);
                        _requestBuffer=null;
                    }
                        
                    //  Is this request/response round complete and are fully flushed?
                    if (_parser.isComplete() && _generator.isComplete())
                    {
                        // look for a switched connection instance?
                        if (_channel.getResponse().getStatus()==HttpStatus.SWITCHING_PROTOCOLS_101)
                        {
                            AsyncConnection switched=(AsyncConnection)_channel.getRequest().getAttribute("org.eclipse.jetty.io.Connection");
                            if (switched!=null)
                                connection=switched;
                        }

                        // Reset the parser/generator
                        reset();
                        progress=true;
                    }
                    else if (_channel.getRequest().getAsyncContinuation().isAsyncStarted())
                    {
                        // The request is suspended, so even though progress has been made,
                        // exit the while loop by setting progress to false
                        LOG.debug("suspended {}",this);
                        progress=false;
                        suspended=true;
                    }
                }
            }
        }
        catch(IOException e)
        {
            LOG.warn(e); 
        }
        finally
        {
            setCurrentConnection(null);
            if (!getEndPoint().isInputShutdown() && !suspended)
                scheduleOnReadable();
        }
    }

    /* ------------------------------------------------------------ */
    private void generateComplete(ByteBuffer content) throws IOException
    {
        _lock.lock();
        try
        {
            if (_generator.isCommitted() || BufferUtil.hasContent(_responseBuffer))
                throw new IllegalStateException("!empty");
            if (_generator.isComplete())
                throw new EofException();

            do
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("{}: send({},{},{})@{}",
                            this,
                            BufferUtil.toSummaryString(_responseHeader),
                            BufferUtil.toSummaryString(_responseBuffer),
                            BufferUtil.toSummaryString(content),
                            _generator.getState());

                HttpGenerator.Result result=_generator.generate(_info,_responseHeader,null,_responseBuffer,content,Action.COMPLETE);
                if (LOG.isDebugEnabled())
                    LOG.debug("{}: {} ({},{},{})@{}",
                            this,
                            result,
                            BufferUtil.toSummaryString(_responseHeader),
                            BufferUtil.toSummaryString(_responseBuffer),
                            BufferUtil.toSummaryString(content),
                            _generator.getState());

                switch(result)
                {
                    case NEED_COMMIT:
                        if (_info==null)
                            _info=_channel.getEventHandler().commit();
                        _responseHeader=_bufferPool.acquire(_connector.getResponseHeaderSize(),false);
                        break;

                    case NEED_BUFFER:
                        _responseBuffer=_bufferPool.acquire(_connector.getResponseBufferSize(),false);
                        break;

                    case NEED_CHUNK:
                        throw new IllegalStateException("!chunk when content length known");

                    case FLUSH:
                        write(_responseHeader,_chunk,_responseBuffer).get();
                        break;

                    case FLUSH_CONTENT:
                        _writeFuture=write(_responseHeader,_chunk,content);
                        return;

                    case SHUTDOWN_OUT:
                        getEndPoint().shutdownOutput();
                        break;

                    case OK:
                        break;
                }
            }
            while(BufferUtil.hasContent(content));
        }
        catch(InterruptedException e)
        {
            LOG.debug(e);
        }
        catch(ExecutionException e)
        {
            LOG.debug(e);
            FutureCallback.rethrow(e);
        }
        finally
        {
            _lock.unlock();
        }
    }

    /* ------------------------------------------------------------ */
    private int generate(ByteBuffer content, Action action) throws IOException
    {
        boolean hasContent=BufferUtil.hasContent(content);
        long preparedBefore=0;
        long preparedAfter;
        _lock.lock();
        try
        {
            preparedBefore=_generator.getContentPrepared();
            
            if (_generator.isComplete())
            {
                if (Action.COMPLETE==action)
                    return 0;
                throw new EofException();
            }
            
            do
            {
                // block if the last write is not complete
                if (_writeFuture!=null && !_writeFuture.isDone())
                    _writeFuture.get();

                if (LOG.isDebugEnabled())
                    LOG.debug("{}: generate({},{},{},{},{})@{}",
                            this,
                            BufferUtil.toSummaryString(_responseHeader),
                            BufferUtil.toSummaryString(_chunk),
                            BufferUtil.toSummaryString(_responseBuffer),
                            BufferUtil.toSummaryString(content),
                            action,_generator.getState());

                HttpGenerator.Result result=_generator.generate(_info,_responseHeader,_chunk,_responseBuffer,content,action);
                if (LOG.isDebugEnabled())
                    LOG.debug("{}: {} ({},{},{},{},{})@{}",
                            this,
                            result,
                            BufferUtil.toSummaryString(_responseHeader),
                            BufferUtil.toSummaryString(_chunk),
                            BufferUtil.toSummaryString(_responseBuffer),
                            BufferUtil.toSummaryString(content),
                            action,_generator.getState());

                switch(result)
                {
                    case NEED_COMMIT:
                        if (_info==null)
                            _info=_channel.getEventHandler().commit();
                        _responseHeader=_bufferPool.acquire(_connector.getResponseHeaderSize(),false);
                        break;

                    case NEED_BUFFER:
                        _responseBuffer=_bufferPool.acquire(_connector.getResponseBufferSize(),false);
                        break;

                    case NEED_CHUNK:
                        _responseHeader=null;
                        _chunk=_bufferPool.acquire(HttpGenerator.CHUNK_SIZE,false);
                        break;

                    case FLUSH:
                        if (hasContent)
                            write(_responseHeader,_chunk,_responseBuffer).get();
                        else
                            _writeFuture=write(_responseHeader,_chunk,_responseBuffer);
                        break;

                    case FLUSH_CONTENT:
                        write(_responseHeader,_chunk,content).get();
                        break;

                    case SHUTDOWN_OUT:
                        getEndPoint().shutdownOutput();
                        break;

                    case OK:
                        break;
                }
            }
            while(BufferUtil.hasContent(content));
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
            preparedAfter=_generator.getContentPrepared();
            _lock.unlock();
        }
        return (int)(preparedAfter-preparedBefore);
    }

    private FutureCallback<Void> write(ByteBuffer b0,ByteBuffer b1,ByteBuffer b2)
    {
        FutureCallback<Void> fcb=new FutureCallback<>();
        if (BufferUtil.hasContent(b0))
        {
            if (BufferUtil.hasContent(b1))
            {
                if (BufferUtil.hasContent(b2))
                    getEndPoint().write(null,fcb,b0,b1,b2);
                else
                    getEndPoint().write(null,fcb,b0,b1);
            }
            else
            {
                if (BufferUtil.hasContent(b2))
                    getEndPoint().write(null,fcb,b0,b2);
                else
                    getEndPoint().write(null,fcb,b0);
            }
        }
        else
        {
            if (BufferUtil.hasContent(b1))
            {
                if (BufferUtil.hasContent(b2))
                    getEndPoint().write(null,fcb,b1,b2);
                else
                    getEndPoint().write(null,fcb,b1);
            }
            else
            {
                if (BufferUtil.hasContent(b2))
                    getEndPoint().write(null,fcb,b2);
                else
                    fcb.completed(null);
            }
        }
        return fcb;
    }

    /* ------------------------------------------------------------ */
    @Override
    public void onClose()
    {
        _channel.onClose();
    }
    
    
    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    private class HttpChannelOverHttp extends HttpChannel
    {
        private HttpChannelOverHttp(Server server)
        {
            super(server,HttpConnection.this);
        }
        
        @Override
        protected int write(ByteBuffer content) throws IOException
        {
            return HttpConnection.this.generate(content,Action.PREPARE);
        }
        
        @Override
        protected void resetBuffer()
        {
            if (_responseBuffer!=null)
                BufferUtil.clear(_responseBuffer);
        }
        
        @Override
        protected void increaseContentBufferSize(int size)
        {
            if (_responseBuffer!=null && _responseBuffer.capacity()>=size)
                return;
            if (_responseBuffer==null && _connector.getResponseBufferSize()>=size)
                return;

            ByteBuffer r=_bufferPool.acquire(size,false);
            if (_responseBuffer!=null)
            {
                BufferUtil.append(_responseBuffer,r);
                _bufferPool.release(_responseBuffer);
            }
            _responseBuffer=r;
        }
        
        @Override
        protected int getContentBufferSize()
        {
            ByteBuffer buffer=_responseBuffer;
            if (buffer!=null)
                return buffer.capacity();
            
            return _connector.getResponseBufferSize();
        }
        
        @Override
        public HttpConnector getHttpConnector()
        {
            return _connector;
        }
        
        @Override
        protected void flushResponse() throws IOException
        {
            HttpConnection.this.generate(null,Action.FLUSH);
        }
        
        @Override
        protected void completeResponse() throws IOException
        {
            HttpConnection.this.generate(null,Action.COMPLETE);
        }

        @Override
        protected void blockForContent() throws IOException
        {
            // While progress and the connection has not changed
            while (getEndPoint().isOpen())
            {
                try
                {
                    // Wait until we can read
                    FutureCallback<Void> block=new FutureCallback<>();
                    getEndPoint().readable(null,block);
                    block.get();

                    // We will need a buffer to read into
                    if (_requestBuffer==null)
                        _requestBuffer=_bufferPool.acquire(_connector.getRequestBufferSize(),false);

                    // If we parse to an event, return
                    if (BufferUtil.hasContent(_requestBuffer) && _parser.parseNext(_requestBuffer))
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
                finally
                {
                    // Return empty request buffer
                    if (_requestBuffer!=null && !_requestBuffer.hasRemaining() && _channel.available()==0)
                    {
                        _bufferPool.release(_requestBuffer);
                        _requestBuffer=null;
                    }
                }
            }
        }

        @Override
        protected void contentConsumed()
        {
            // TODO Auto-generated method stub
            
        }

        @Override
        protected void commit(ResponseInfo info, ByteBuffer content) throws IOException
        {
            _info=info;
            HttpConnection.this.generateComplete(content);
            
        }

        @Override
        public Timer getTimer()
        {
            // TODO Auto-generated method stub
            return null;
        }

    };
}
