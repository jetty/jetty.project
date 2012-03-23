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
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.servlet.DispatcherType;
import javax.servlet.ServletInputStream;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.continuation.ContinuationThrowable;
import org.eclipse.jetty.http.HttpBuffers;
import org.eclipse.jetty.http.HttpContent;
import org.eclipse.jetty.http.HttpException;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpGenerator;
import org.eclipse.jetty.http.HttpHeaderValue;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpParser;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.http.HttpGenerator.Action;
import org.eclipse.jetty.io.AbstractConnection;

import org.eclipse.jetty.io.AsyncEndPoint;
import org.eclipse.jetty.io.Buffers;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.EofException;
import org.eclipse.jetty.io.RuntimeIOException;
import org.eclipse.jetty.io.UncheckedPrintWriter;
import org.eclipse.jetty.io.nio.SelectChannelEndPoint;
import org.eclipse.jetty.server.nio.NIOConnector;
import org.eclipse.jetty.server.ssl.SslConnector;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.QuotedStringTokenizer;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.resource.Resource;

/**
 */
public abstract class HttpConnection extends AbstractConnection
{
    private static final Logger LOG = Log.getLogger(HttpConnection.class);

    private static final ThreadLocal<HttpConnection> __currentConnection = new ThreadLocal<HttpConnection>();

    private final AsyncEndPoint _asyncEndp;
    private final Server _server;
    private final Connector _connector;
    private final HttpParser _parser;
    private final HttpGenerator _generator;
    private final HttpChannel _channel;

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
    public HttpConnection(Connector connector, EndPoint endpoint, Server server)
    {
        super(endpoint);
        _connector = connector;
        _server = server;
        _asyncEndp=(AsyncEndPoint)endpoint;
        
        _channel = new HttpChannel(server);
       
        _parser = new HttpParser(_channel.getRequestHandler());
        _generator = new HttpGenerator(_channel.getResponseInfo());
        
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
    public void reset()
    {
        _parser.reset();
        _generator.reset();
        _channel.reset();
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
    public boolean isIdle()
    {
        return _parser.isIdle() && _generator.isIdle();
    }

    /* ------------------------------------------------------------ */
    public boolean isReadInterested()
    {
        return !_channel.getAsyncContinuation().isSuspended() && !_parser.isComplete();
    }

    /* ------------------------------------------------------------ */
    public int getMaxIdleTime()
    {
        if (_connector.isLowResources() && _endp.getMaxIdleTime()==_connector.getMaxIdleTime())
            return _connector.getLowResourceMaxIdleTime();
        if (_endp.getMaxIdleTime()>0)
            return _endp.getMaxIdleTime();
        return _connector.getMaxIdleTime();
    }

    /* ------------------------------------------------------------ */
    public String toString()
    {
        return String.format("%s,g=%s,p=%s",
                super.toString(),
                _generator,
                _parser);
    }


    /* ------------------------------------------------------------ */
    @Override
    public Connection handle() throws IOException
    {
        Connection connection = this;
        boolean progress=true;

        try
        {
            setCurrentConnection(this);

            // don't check for idle while dispatched (unless blocking IO is done).
            _asyncEndp.setCheckForIdle(false);


            // While progress and the connection has not changed
            while (progress && connection==this)
            {
                progress=false;
                try
                {
                    // Shall we try some reading
                    if (isReadInterested())
                    {
                        // We will need a buffer to read into
                        if (_requestBuffer==null)
                            _requestBuffer=_parser.isInContent()
                                ?_connector.getRequestBuffers().getBuffer()
                                :_connector.getRequestBuffers().getHeader();    
                    }
                    
                    // If we parse to an event, call the connection
                    if (BufferUtil.hasContent(_requestBuffer) && _parser.parseNext(_requestBuffer))
                        _channel.handleRequest();

                }
                catch (HttpException e)
                {
                    progress=true;
                    _channel.sendError(e.getStatus(), e.getReason(), null, true);
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
                        if (_channel.getResponse().getStatus()==HttpStatus.SWITCHING_PROTOCOLS_101)
                        {
                            Connection switched=(Connection)_channel.getRequest().getAttribute("org.eclipse.jetty.io.Connection");
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
                    }
                }
            }
        }
        finally
        {
            setCurrentConnection(null);

            // If we are not suspended
            if (!_channel.getRequest().getAsyncContinuation().isAsyncStarted())
            {
                // reenable idle checking unless request is suspended
                _asyncEndp.setCheckForIdle(true);
            }
        }
        return connection;
    }

    
    /* ------------------------------------------------------------ */
    private void write(ByteBuffer content, Action action) throws IOException
    {
        if (!_generator.isComplete())
            throw new EofException();

        try
        {
            while(BufferUtil.hasContent(content))
            {
                /* System.err.printf("generate(%s,%s,%s,%s,%s)@%s%n",
                    BufferUtil.toSummaryString(header),
                    BufferUtil.toSummaryString(chunk),
                    BufferUtil.toSummaryString(buffer),
                    BufferUtil.toSummaryString(content),
                    action,gen.getState());*/
                HttpGenerator.Result result=_generator.generate(_responseHeader,_chunk,_responseBuffer,content,action);
                /*System.err.printf("%s (%s,%s,%s,%s,%s)@%s%n",
                    result,
                    BufferUtil.toSummaryString(header),
                    BufferUtil.toSummaryString(chunk),
                    BufferUtil.toSummaryString(buffer),
                    BufferUtil.toSummaryString(content),
                    action,gen.getState());*/

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
                    {
                        Future<Integer> future = getEndPoint().flush(_responseHeader,_chunk,_responseBuffer);
                        future.get(getMaxIdleTime(),TimeUnit.MILLISECONDS);
                        break;
                    }
                    case FLUSH_CONTENT:
                    {
                        Future<Integer> future = getEndPoint().flush(_responseHeader,_chunk,content);
                        future.get(getMaxIdleTime(),TimeUnit.MILLISECONDS);
                        break;
                    }
                    case OK:
                        break;
                    case SHUTDOWN_OUT:
                        getEndPoint().shutdownOutput();
                        break;
                }
                
                switch(action)
                {
                    case COMPLETE: action=Action.PREPARE; break;
                    case FLUSH: action=Action.FLUSH; break;
                    case PREPARE: action=Action.PREPARE; break;
                }
            }
        }

        catch(final TimeoutException e)
        {
            throw new InterruptedIOException(e.toString())
            {
                {
                    this.initCause(e);
                }
            };
        }
        catch (final InterruptedException e)
        {
            throw new InterruptedIOException(e.toString())
            {
                {
                    this.initCause(e);
                }
            };
        }
        catch (final ExecutionException e)
        {
            throw new IOException(e.toString())
            {
                {
                    this.initCause(e);
                }
            };
        }
    }


    /* ------------------------------------------------------------ */
    public void onClose()
    {
        _channel.onClose();
    }
    
    /* ------------------------------------------------------------ */
    public void onInputShutdown() throws IOException
    {
        // If we don't have a committed response and we are not suspended
        if (_generator.isIdle() && !_channel.getRequest().getAsyncContinuation().isSuspended())
        {
            // then no more can happen, so close.
            _endp.close();
        }
        
        // Make idle parser seek EOF
        if (_parser.isIdle())
            _parser.setPersistent(false);
    }

}
