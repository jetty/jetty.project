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
import java.io.InterruptedIOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.eclipse.jetty.http.HttpException;
import org.eclipse.jetty.http.HttpGenerator;
import org.eclipse.jetty.http.HttpGenerator.Action;
import org.eclipse.jetty.http.HttpParser;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.SelectableConnection;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.EofException;
import org.eclipse.jetty.io.SelectableEndPoint;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 */
public abstract class HttpConnection extends SelectableConnection
{

    private static final Logger LOG = Log.getLogger(HttpConnection.class);

    private static final ThreadLocal<HttpConnection> __currentConnection = new ThreadLocal<HttpConnection>();

    private final Server _server;
    private final Connector _connector;
    private final HttpParser _parser;
    private final HttpGenerator _generator;
    private final HttpProcessor _processor;

    int _toFlush;
    ByteBuffer _requestBuffer=null;
    ByteBuffer _responseHeader=null;
    ByteBuffer _chunk=null;
    ByteBuffer _responseBuffer=null; 
    ByteBuffer _content=null;    
    
    
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
    public HttpConnection(Connector connector, SelectableEndPoint endpoint, Server server)
    {
        super(endpoint);
        _connector = connector;
        _server = server;
        
        _processor = new HttpOverHttpProcessor(server,_controller);
       
        _parser = new HttpParser(_processor.getRequestHandler());
        _generator = new HttpGenerator(_processor.getResponseInfo());
        
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
    public HttpProcessor getHttpChannel()
    {
        return _processor;
    }

    /* ------------------------------------------------------------ */
    public void reset()
    {
        _parser.reset();
        _generator.reset();
        _processor.reset();
        if (_requestBuffer!=null)
            _connector.getResponseBuffers().returnBuffer(_requestBuffer);
        _requestBuffer=null;
        if (_responseHeader!=null)
            _connector.getResponseBuffers().returnBuffer(_responseHeader);
        _responseHeader=null;
        if (_responseBuffer!=null)
            _connector.getResponseBuffers().returnBuffer(_responseBuffer);
        _responseBuffer=null;
        if (_chunk!=null)
            _connector.getResponseBuffers().returnBuffer(_chunk);
        _chunk=null;
    }

    
    /* ------------------------------------------------------------ */
    public HttpGenerator getGenerator()
    {
        return _generator;
    }

    /* ------------------------------------------------------------ */
    @Override
    public boolean isIdle()
    {
        return _parser.isIdle() && _generator.isIdle();
    }

    /* ------------------------------------------------------------ */
    public boolean isReadInterested()
    {
        return !_processor.getAsyncContinuation().isSuspended() && !_parser.isComplete();
    }

    /* ------------------------------------------------------------ */
    @Override
    public int getMaxIdleTime()
    {
        if (_connector.isLowResources() && _endp.getMaxIdleTime()==_connector.getMaxIdleTime())
            return _connector.getLowResourceMaxIdleTime();
        if (_endp.getMaxIdleTime()>0)
            return _endp.getMaxIdleTime();
        return _connector.getMaxIdleTime();
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
    public void processInput()
    {
        Connection connection = this;
        boolean progress=true;

        try
        {
            setCurrentConnection(this);

            // don't check for idle while dispatched (unless blocking IO is done).
            getSelectableEndPoint().setCheckForIdle(false);


            // While progress and the connection has not changed
            while (progress && connection==this)
            {
                progress=false;
                try
                {
                    // We will need a buffer to read into
                    if (_requestBuffer==null)
                        _requestBuffer=_parser.isInContent()
                        ?_connector.getRequestBuffers().getBuffer()
                                :_connector.getRequestBuffers().getHeader();   
                    
                    // If we parse to an event, call the connection
                    if (BufferUtil.hasContent(_requestBuffer) && _parser.parseNext(_requestBuffer))
                        _processor.handleRequest();

                }
                catch (HttpException e)
                {
                    progress=true;
                    _controller.sendError(e.getStatus(), e.getReason(), null, true);
                }
                finally
                {
                    // Return empty request buffer
                    if (_requestBuffer!=null && !_requestBuffer.hasRemaining())
                    {
                        _connector.getRequestBuffers().returnBuffer(_requestBuffer);
                        _requestBuffer=null;
                    }
                        
                    //  Is this request/response round complete and are fully flushed?
                    if (_parser.isComplete() && _generator.isComplete())
                    {
                        // look for a switched connection instance?
                        if (_processor.getResponse().getStatus()==HttpStatus.SWITCHING_PROTOCOLS_101)
                        {
                            Connection switched=(Connection)_processor.getRequest().getAttribute("org.eclipse.jetty.io.Connection");
                            if (switched!=null)
                                connection=switched;
                        }

                        // Reset the parser/generator
                        reset();
                        progress=true;
                    }
                    else if (_processor.getRequest().getAsyncContinuation().isAsyncStarted())
                    {
                        // The request is suspended, so even though progress has been made,
                        // exit the while loop by setting progress to false
                        LOG.debug("suspended {}",this);
                        progress=false;
                    }
                }
            }
        }
        catch(IOException e)
        {
            // TODO 
        }
        finally
        {
            setCurrentConnection(null);

            // If we are not suspended
            if (!_processor.getRequest().getAsyncContinuation().isAsyncStarted())
            {
                // reenable idle checking unless request is suspended
                getSelectableEndPoint().setCheckForIdle(true);
            }
        }
    }

    
    /* ------------------------------------------------------------ */
    private int generate(ByteBuffer content, Action action, boolean volatileContent) throws IOException
    {
        if (!_generator.isComplete())
            throw new EofException();

        long prepared=_generator.getContentPrepared();
        
        do
        {
            if (_toFlush!=0)
                flush(true);
            
            if (LOG.isDebugEnabled())
                LOG.debug("{}: generate({},{},{},{},{})@{}",
                    this,
                    BufferUtil.toSummaryString(_responseHeader),
                    BufferUtil.toSummaryString(_chunk),
                    BufferUtil.toSummaryString(_responseBuffer),
                    BufferUtil.toSummaryString(content),
                    action,_generator.getState());
            
            HttpGenerator.Result result=_generator.generate(_responseHeader,_chunk,_responseBuffer,content,action);
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
                case NEED_HEADER:
                    _responseHeader=_connector.getResponseBuffers().getHeader();
                    break;

                case NEED_BUFFER:
                    _responseBuffer=_connector.getResponseBuffers().getBuffer();
                    _responseBuffer=BufferUtil.allocate(8192);
                    break;

                case NEED_CHUNK:
                    _responseHeader=null;
                    _chunk=_connector.getResponseBuffers().getBuffer(HttpGenerator.CHUNK_SIZE);
                    break;

                case FLUSH:
                    _toFlush=
                        (BufferUtil.hasContent(_responseHeader)?8:0)+
                        (BufferUtil.hasContent(_chunk)?4:0)+
                        (BufferUtil.hasContent(_responseBuffer)?2:0);
                    flush(false);
                    break;
                
                case FLUSH_CONTENT:
                    _content=content;
                    _toFlush=
                        (BufferUtil.hasContent(_responseHeader)?8:0)+
                        (BufferUtil.hasContent(_chunk)?4:0)+
                        (BufferUtil.hasContent(_content)?1:0);
                    flush(volatileContent);
                    break;
                
                case SHUTDOWN_OUT:
                    getEndPoint().shutdownOutput();
                    break;
                    
                case OK:
                    break;
            }
        }
        while(BufferUtil.hasContent(content));
        
        return (int)(prepared-_generator.getContentPrepared());
    }
    
    /* ------------------------------------------------------------ */
    private void flush(boolean block) throws IOException
    {
        while (_toFlush>0)
        {
            switch(_toFlush)
            {
                case 10:
                    _endp.flush(_responseHeader,_responseBuffer); 
                    _toFlush=(BufferUtil.hasContent(_responseHeader)?8:0)+(BufferUtil.hasContent(_responseBuffer)?2:0);
                    break;
                case 9: 
                    _endp.flush(_responseHeader,_content); 
                    _toFlush=(BufferUtil.hasContent(_responseHeader)?8:0)+(BufferUtil.hasContent(_content)?1:0);
                    if (_toFlush==0)
                        _content=null;
                    break;
                case 8: 
                    _endp.flush(_responseHeader); 
                    _toFlush=(BufferUtil.hasContent(_responseHeader)?8:0);
                    break;
                case 6: 
                    _endp.flush(_chunk,_responseBuffer);
                    _toFlush=(BufferUtil.hasContent(_chunk)?4:0)+(BufferUtil.hasContent(_responseBuffer)?2:0);
                    break;
                case 5: 
                    _endp.flush(_chunk,_content);
                    _toFlush=(BufferUtil.hasContent(_chunk)?4:0)+(BufferUtil.hasContent(_content)?1:0);
                    if (_toFlush==0)
                        _content=null;
                    break;
                case 4: 
                    _endp.flush(_chunk);
                    _toFlush=(BufferUtil.hasContent(_chunk)?4:0);
                    break;
                case 2: 
                    _endp.flush(_responseBuffer);
                    _toFlush=(BufferUtil.hasContent(_responseBuffer)?2:0);
                    break;
                case 1: 
                    _endp.flush(_content);
                    _toFlush=(BufferUtil.hasContent(_content)?1:0);
                    if (_toFlush==0)
                        _content=null;
                    break;
                case 0:
                default:
                    throw new IllegalStateException();
            }
            
            if (!block)
                break;
            
            if (_toFlush>0)
                blockWriteable();

        }
    }

    /* ------------------------------------------------------------ */
    @Override
    public void onClose()
    {
        _processor.onClose();
    }
    
    /* ------------------------------------------------------------ */
    @Override
    public void onInputShutdown() throws IOException
    {
        // If we don't have a committed response and we are not suspended
        if (_generator.isIdle() && !_processor.getRequest().getAsyncContinuation().isSuspended())
        {
            // then no more can happen, so close.
            _endp.close();
        }
        
        // Make idle parser seek EOF
        if (_parser.isIdle())
            _parser.setPersistent(false);
    }
    
    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    private final class HttpOverHttpProcessor extends HttpProcessor
    {
        private HttpOverHttpProcessor(Server server, HttpController controller)
        {
            super(server,controller);
        }

        @Override
        public InetSocketAddress getLocalAddress()
        {
            return _endp.getLocalAddress();
        }

        @Override
        public InetSocketAddress getRemoteAddress()
        {
            return _endp.getRemoteAddress();
        }

        @Override
        public long getMaxIdleTime()
        {
            return HttpConnection.this.getMaxIdleTime();
        }
    }

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    private final HttpController _controller = new HttpController()
    {
        
        @Override
        public int write(ByteBuffer content, boolean volatileContent) throws IOException
        {
            return HttpConnection.this.generate(content,Action.PREPARE,volatileContent);
        }
        
        @Override
        public void setPersistent(boolean persistent)
        {
            _parser.setPersistent(persistent);
            _generator.setPersistent(persistent);
        }
        
        @Override
        public void sendError(int status, String reason, String content, boolean close) throws IOException
        {
            // TODO Auto-generated method stub
            
        }
        
        @Override
        public void send1xx(int processing102)
        {
            // TODO Auto-generated method stub
            
        }
        
        @Override
        public void resetBuffer()
        {
            // TODO Auto-generated method stub
            
        }
        
        @Override
        public void persist()
        {
            // TODO Auto-generated method stub
            
        }
        
        @Override
        public boolean isResponseCommitted()
        {
            return _generator.isCommitted();
        }
        
        @Override
        public boolean isPersistent()
        {
            return _generator.isPersistent();
        }
        
        @Override
        public void increaseContentBufferSize(int size)
        {
            // TODO Auto-generated method stub
        }
        
        @Override
        public int getContentBufferSize()
        {
            ByteBuffer buffer=_responseBuffer;
            if (buffer!=null)
                return buffer.capacity();
            
            return _connector.getResponseBufferSize();
        }
        
        @Override
        public Connector getConnector()
        {
            return _connector;
        }
        
        @Override
        public void flushResponse() throws IOException
        {
            HttpConnection.this.generate(null,Action.FLUSH,false);
        }
        
        @Override
        public void customize(Request request)
        {
            // TODO Auto-generated method stub
            
        }
        
        @Override
        public void completeResponse()
        {
            // TODO Auto-generated method stub
            
        }
    };
}
