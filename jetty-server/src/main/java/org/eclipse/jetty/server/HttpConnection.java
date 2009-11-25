// ========================================================================
// Copyright (c) 2004-2009 Mort Bay Consulting Pty. Ltd.
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
import java.io.PrintWriter;

import javax.servlet.ServletInputStream;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.continuation.ContinuationThrowable;
import org.eclipse.jetty.http.AbstractGenerator;
import org.eclipse.jetty.http.EncodedHttpURI;
import org.eclipse.jetty.http.Generator;
import org.eclipse.jetty.http.HttpBuffers;
import org.eclipse.jetty.http.HttpContent;
import org.eclipse.jetty.http.HttpException;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpGenerator;
import org.eclipse.jetty.http.HttpHeaderValues;
import org.eclipse.jetty.http.HttpHeaders;
import org.eclipse.jetty.http.HttpMethods;
import org.eclipse.jetty.http.HttpParser;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.HttpVersions;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.http.Parser;
import org.eclipse.jetty.io.Buffer;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.EofException;
import org.eclipse.jetty.io.RuntimeIOException;
import org.eclipse.jetty.io.UncheckedPrintWriter;
import org.eclipse.jetty.io.UpgradeConnectionException;
import org.eclipse.jetty.io.BufferCache.CachedBuffer;
import org.eclipse.jetty.io.nio.SelectChannelEndPoint;
import org.eclipse.jetty.util.QuotedStringTokenizer;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.thread.Timeout;

/**
 * <p>A HttpConnection represents the connection of a HTTP client to the server
 * and is created by an instance of a {@link Connector}. It's prime function is
 * to associate {@link Request} and {@link Response} instances with a {@link EndPoint}.
 * </p>
 * <p>
 * A connection is also the prime mechanism used by jetty to recycle objects without
 * pooling.  The {@link Request},  {@link Response}, {@link HttpParser}, {@link HttpGenerator}
 * and {@link HttpFields} instances are all recycled for the duraction of
 * a connection. Where appropriate, allocated buffers are also kept associated
 * with the connection via the parser and/or generator.
 * </p>
 * <p>
 * The connection state is held by 3 separate state machines: The request state, the 
 * response state and the continuation state.  All three state machines must be driven
 * to completion for every request, and all three can complete in any order.
 * 
 *
 */
public class HttpConnection implements Connection
{
    private static final int UNKNOWN = -2;
    private static final ThreadLocal<HttpConnection> __currentConnection = new ThreadLocal<HttpConnection>();

    private final long _timeStamp=System.currentTimeMillis();
    private int _requests;
    private volatile boolean _handling;

    protected final Connector _connector;
    protected final EndPoint _endp;
    protected final Server _server;
    protected final HttpURI _uri;

    protected final Parser _parser;
    protected final HttpFields _requestFields;
    protected final Request _request;
    protected ServletInputStream _in;

    protected final Generator _generator;
    protected final HttpFields _responseFields;
    protected final Response _response;
    protected Output _out;
    protected OutputWriter _writer;
    protected PrintWriter _printWriter;

    int _include;

    private Object _associatedObject; // associated object

    private int _version = UNKNOWN;

    private boolean _expect = false;
    private boolean _expect100Continue = false;
    private boolean _expect102Processing = false;
    private boolean _head = false;
    private boolean _host = false;
    private boolean  _delayedHandling=false;

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
        _uri = StringUtil.__UTF8.equals(URIUtil.__CHARSET)?new HttpURI():new EncodedHttpURI(URIUtil.__CHARSET);
        _connector = connector;
        _endp = endpoint;
        HttpBuffers ab = (HttpBuffers)_connector;
        _parser = new HttpParser(ab.getRequestBuffers(), endpoint, new RequestHandler());
        _requestFields = new HttpFields();
        _responseFields = new HttpFields();
        _request = new Request(this);
        _response = new Response(this);
        _generator = new HttpGenerator(ab.getResponseBuffers(), _endp);
        _generator.setSendServerVersion(server.getSendServerVersion());
        _server = server;
    }

    /* ------------------------------------------------------------ */
    protected HttpConnection(Connector connector, EndPoint endpoint, Server server,
            Parser parser, Generator generator, Request request)
    {
        _uri = URIUtil.__CHARSET.equals(StringUtil.__UTF8)?new HttpURI():new EncodedHttpURI(URIUtil.__CHARSET);
        _connector = connector;
        _endp = endpoint;
        _parser = parser;
        _requestFields = new HttpFields();
        _responseFields = new HttpFields();
        _request = request;
        _response = new Response(this);
        _generator = generator;
        _generator.setSendServerVersion(server.getSendServerVersion());
        _server = server;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return the parser used by this connection
     */
    public Parser getParser()
    {
        return _parser;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return the number of requests handled by this connection
     */
    public int getRequests()
    {
        return _requests;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return The time this connection was established.
     */
    public long getTimeStamp()
    {
        return _timeStamp;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return Returns the associatedObject.
     */
    public Object getAssociatedObject()
    {
        return _associatedObject;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param associatedObject The associatedObject to set.
     */
    public void setAssociatedObject(Object associatedObject)
    {
        _associatedObject = associatedObject;
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
     * @return Returns the requestFields.
     */
    public HttpFields getRequestFields()
    {
        return _requestFields;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return Returns the responseFields.
     */
    public HttpFields getResponseFields()
    {
        return _responseFields;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return The result of calling {@link #getConnector}.{@link Connector#isConfidential(Request) isCondidential}(request), or false
     *  if there is no connector.
     */
    public boolean isConfidential(Request request)
    {
        if (_connector!=null)
            return _connector.isConfidential(request);
        return false;
    }

    /* ------------------------------------------------------------ */
    /**
     * Find out if the request is INTEGRAL security.
     * @param request
     * @return <code>true</code> if there is a {@link #getConnector() connector} and it considers <code>request</code>
     *         to be {@link Connector#isIntegral(Request) integral}
     */
    public boolean isIntegral(Request request)
    {
        if (_connector!=null)
            return _connector.isIntegral(request);
        return false;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return The {@link EndPoint} for this connection.
     */
    public EndPoint getEndPoint()
    {
        return _endp;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return <code>false</code> (this method is not yet implemented)
     */
    public boolean getResolveNames()
    {
        return _connector.getResolveNames();
    }

    /* ------------------------------------------------------------ */
    /**
     * @return Returns the request.
     */
    public Request getRequest()
    {
        return _request;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return Returns the response.
     */
    public Response getResponse()
    {
        return _response;
    }

    /* ------------------------------------------------------------ */
    /**
     * Get the inputStream from the connection.
     * <p>
     * If the associated response has the Expect header set to 100 Continue,
     * then accessing the input stream indicates that the handler/servlet
     * is ready for the request body and thus a 100 Continue response is sent.
     *
     * @return The input stream for this connection.
     * The stream will be created if it does not already exist.
     */
    public ServletInputStream getInputStream() throws IOException
    {
        // If the client is expecting 100 CONTINUE, then send it now.
        if (_expect100Continue)
        {
            // is content missing?
            if (((HttpParser)_parser).getHeaderBuffer()==null || ((HttpParser)_parser).getHeaderBuffer().length()<2)
            {
                if (_generator.isCommitted())
                    throw new IllegalStateException("Committed before 100 Continues");

                ((HttpGenerator)_generator).send1xx(HttpStatus.CONTINUE_100);
            }
            _expect100Continue=false;
        }

        if (_in == null)
            _in = new HttpInput(((HttpParser)_parser),_connector.getMaxIdleTime());
        return _in;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return The output stream for this connection. The stream will be created if it does not already exist.
     */
    public ServletOutputStream getOutputStream()
    {
        if (_out == null)
            _out = new Output();
        return _out;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return A {@link PrintWriter} wrapping the {@link #getOutputStream output stream}. The writer is created if it
     *    does not already exist.
     */
    public PrintWriter getPrintWriter(String encoding)
    {
        getOutputStream();
        if (_writer==null)
        {
            _writer=new OutputWriter();
            _printWriter=new UncheckedPrintWriter(_writer);
        }
        _writer.setCharacterEncoding(encoding);
        return _printWriter;
    }

    /* ------------------------------------------------------------ */
    public boolean isResponseCommitted()
    {
        return _generator.isCommitted();
    }

    /* ------------------------------------------------------------ */
    public void handle() throws IOException
    {
        // Loop while more in buffer
        boolean more_in_buffer =true; // assume true until proven otherwise
        boolean progress=true;

        try
        {
            assert getCurrentConnection()==null;
            assert _handling==false;
            _handling=true;
            setCurrentConnection(this);

            while (more_in_buffer)
            {
                try
                {
                    if (_request._async.isAsync())
                    {
                        // TODO - handle the case of input being read for a 
                        // suspended request.
                        
                        Log.debug("async request",_request);
                        if (!_request._async.isComplete())
                            handleRequest();
                        else if (!_parser.isComplete())
                        {
                            long parsed=_parser.parseAvailable();
                            progress|=parsed>0;
                        }

                        if (_generator.isCommitted() && !_generator.isComplete())
                            progress|=_generator.flushBuffer()>0;
                        if (_endp.isBufferingOutput())
                            _endp.flush();
                    }
                    else
                    {
                        // If we are not ended then parse available
                        if (!_parser.isComplete())
                            progress|=_parser.parseAvailable()>0;

                        // Do we have more generating to do?
                        // Loop here because some writes may take multiple steps and
                        // we need to flush them all before potentially blocking in the
                        // next loop.
                        while (_generator.isCommitted() && !_generator.isComplete())
                        {
                            long written=_generator.flushBuffer();
                            if (written<=0)
                                break;
                            progress=true;
                            if (_endp.isBufferingOutput())
                                _endp.flush();
                        }

                        // Flush buffers
                        if (_endp.isBufferingOutput())
                        {
                            _endp.flush();
                            if (!_endp.isBufferingOutput())
                                progress=true;
                        }

                        if (!progress)
                            return;
                    }
                    progress=false;
                }
                catch (HttpException e)
                {
                    if (Log.isDebugEnabled())
                    {
                        Log.debug("uri="+_uri);
                        Log.debug("fields="+_requestFields);
                        Log.debug(e);
                    }
                    _generator.sendError(e.getStatus(), e.getReason(), null, true);

                    _parser.reset(true);
                    _endp.close();
                    throw e;
                }
                finally
                {
                    more_in_buffer = _parser.isMoreInBuffer() || _endp.isBufferingInput();

                    if (_parser.isComplete() && _generator.isComplete() && !_endp.isBufferingOutput())
                    {
                        if (!_generator.isPersistent())
                        {
                            _parser.reset(true);
                            more_in_buffer=false;
                        }

                        if (more_in_buffer)
                        {
                            reset(false);
                            more_in_buffer = _parser.isMoreInBuffer() || _endp.isBufferingInput(); 
                        }
                        else
                            reset(true);
                        progress=true;
                    }

                    if (_request.isAsyncStarted())
                    {
                        Log.debug("return with suspended request");
                        more_in_buffer=false;
                    }
                    else if (_generator.isCommitted() && !_generator.isComplete() && _endp instanceof SelectChannelEndPoint) // TODO remove SelectChannel dependency
                        ((SelectChannelEndPoint)_endp).setWritable(false);
                }
            }
        }
        finally
        {
            setCurrentConnection(null);
            _handling=false;
        }
    }

    /* ------------------------------------------------------------ */
    public void scheduleTimeout(Timeout.Task task, long timeoutMs)
    {
        throw new UnsupportedOperationException();
    }

    /* ------------------------------------------------------------ */
    public void cancelTimeout(Timeout.Task task)
    {
        throw new UnsupportedOperationException();
    }

    /* ------------------------------------------------------------ */
    public void reset(boolean returnBuffers)
    {
        _parser.reset(returnBuffers); // TODO maybe only release when low on resources
        _requestFields.clear();
        _request.recycle();

        _generator.reset(returnBuffers); // TODO maybe only release when low on resources
        _responseFields.clear();
        _response.recycle();

        _uri.clear();
    }

    /* ------------------------------------------------------------ */
    protected void handleRequest() throws IOException
    {
        boolean error = false;

        String threadName=null;
        try
        {
            if (Log.isDebugEnabled())
            {
                threadName=Thread.currentThread().getName();
                Thread.currentThread().setName(threadName+" - "+_uri);
            }


            // Loop here to handle async request redispatches.
            // The loop is controlled by the call to async.unhandle in the
            // finally block below.  If call is from a non-blocking connector,
            // then the unhandle will return false only if an async dispatch has
            // already happened when unhandle is called.   For a blocking connector,
            // the wait for the asynchronous dispatch or timeout actually happens
            // within the call to unhandle().

            final Server server=_server;
            boolean handling=_request._async.handling() && server!=null && server.isRunning();
            while (handling)
            {
                _request.setHandled(false);

                String info=null;
                try
                {
                    _uri.getPort();
                    info=URIUtil.canonicalPath(_uri.getDecodedPath());
                    if (info==null)
                        throw new HttpException(400);
                    _request.setPathInfo(info);

                    if (_out!=null)
                        _out.reopen();

                    if (_request._async.isInitial())
                    {
                        _request.setDispatcherType(DispatcherType.REQUEST);
                        _connector.customize(_endp, _request);
                        server.handle(this);
                    }
                    else
                    {
                        _request.setDispatcherType(DispatcherType.ASYNC);
                        server.handleAsync(this);
                    }
                }
                catch (UpgradeConnectionException e)
                {
                    throw e;
                }
                catch (ContinuationThrowable e)
                {
                    Log.ignore(e);
                }
                catch (EofException e)
                {
                    Log.debug(e);
                    _request.setHandled(true);
                    error=true;
                }
                catch (RuntimeIOException e)
                {
                    Log.debug(e);
                    _request.setHandled(true);
                    error=true;
                }
                catch (HttpException e)
                {
                    Log.debug(e);
                    _request.setHandled(true);
                    _response.sendError(e.getStatus(), e.getReason());
                    error=true;
                }
                catch (Throwable e)
                {
                    if (e instanceof ThreadDeath)
                        throw (ThreadDeath)e;

                    error=true;
                    if (info==null)
                    {
                        Log.debug(_uri+": "+e);
                        _request.setHandled(true);
                        _generator.sendError(400, null, null, true);
                    }
                    else
                    {
                        Log.debug(""+_uri,e);
                        _request.setHandled(true);
                        _generator.sendError(500, null, null, true);
                    }
                }
                finally
                {
                    handling = !_request._async.unhandle() && server.isRunning() && _server!=null;
                }
            }
        }
        finally
        {
            if (threadName!=null)
                Thread.currentThread().setName(threadName);

            if (_request._async.isUncompleted())
            {
                _request._async.doComplete();

                if (_expect100Continue)
                {
                    // Continue not sent so don't parse any content
                    _expect100Continue = false;
                    if (_parser instanceof HttpParser)
                        ((HttpParser)_parser).setState(HttpParser.STATE_END);
                }

                if(_endp.isOpen())
                {
                    if (_generator.isPersistent())
                        _connector.persist(_endp);

                    if (error)
                        _endp.close();
                    else
                    {
                        if (!_response.isCommitted() && !_request.isHandled())
                            _response.sendError(HttpServletResponse.SC_NOT_FOUND);
                        _response.complete();
                    }
                }
                else
                {
                    _response.complete();
                }

                _request.setHandled(true);
            }
        }
    }

    /* ------------------------------------------------------------ */
    public void commitResponse(boolean last) throws IOException
    {
        if (!_generator.isCommitted())
        {
            _generator.setResponse(_response.getStatus(), _response.getReason());
            try
            {
                _generator.completeHeader(_responseFields, last);
            }
            catch(IOException io)
            {
                throw io;
            }
            catch(RuntimeException e)
            {
                Log.warn("header full: "+e);

                _response.reset();
                _generator.reset(true);
                _generator.setResponse(HttpStatus.INTERNAL_SERVER_ERROR_500,null);
                _generator.completeHeader(_responseFields,Generator.LAST);
                _generator.complete();
                throw new HttpException(HttpStatus.INTERNAL_SERVER_ERROR_500);
            }

        }
        if (last)
            _generator.complete();
    }

    /* ------------------------------------------------------------ */
    public void completeResponse() throws IOException
    {
        if (!_generator.isCommitted())
        {
            _generator.setResponse(_response.getStatus(), _response.getReason());
            try
            {
                _generator.completeHeader(_responseFields, Generator.LAST);
            }
            catch(IOException io)
            {
                throw io;
            }
            catch(RuntimeException e)
            {
                Log.warn("header full: "+e);
                Log.debug(e);

                _response.reset();
                _generator.reset(true);
                _generator.setResponse(HttpStatus.INTERNAL_SERVER_ERROR_500,null);
                _generator.completeHeader(_responseFields,Generator.LAST);
                _generator.complete();
                throw new HttpException(HttpStatus.INTERNAL_SERVER_ERROR_500);
            }
        }

        _generator.complete();
    }

    /* ------------------------------------------------------------ */
    public void flushResponse() throws IOException
    {
        try
        {
            commitResponse(Generator.MORE);
            _generator.flushBuffer();
        }
        catch(IOException e)
        {
            throw (e instanceof EofException) ? e:new EofException(e);
        }
    }

    /* ------------------------------------------------------------ */
    public Generator getGenerator()
    {
        return _generator;
    }

    /* ------------------------------------------------------------ */
    public boolean isIncluding()
    {
        return _include>0;
    }

    /* ------------------------------------------------------------ */
    public void include()
    {
        _include++;
    }

    /* ------------------------------------------------------------ */
    public void included()
    {
        _include--;
        if (_out!=null)
            _out.reopen();
    }

    /* ------------------------------------------------------------ */
    public boolean isIdle()
    {
        return _generator.isIdle() && (_parser.isIdle() || _delayedHandling);
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.io.Connection#isSuspended()
     */
    public boolean isSuspended()
    {
        return _request.getAsyncContinuation().isSuspended();
    }

    /* ------------------------------------------------------------ */
    public boolean isExpecting100Continues()
    {
        return _expect100Continue;
    }

    /* ------------------------------------------------------------ */
    public boolean isExpecting102Processing()
    {
        return _expect102Processing;
    }

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    private class RequestHandler extends HttpParser.EventHandler
    {
        private String _charset;

        /*
         *
         * @see org.eclipse.jetty.server.server.HttpParser.EventHandler#startRequest(org.eclipse.io.Buffer,
         *      org.eclipse.io.Buffer, org.eclipse.io.Buffer)
         */
        @Override
        public void startRequest(Buffer method, Buffer uri, Buffer version) throws IOException
        {
            _host = false;
            _expect = false;
            _expect100Continue=false;
            _expect102Processing=false;
            _delayedHandling=false;
            _charset=null;

            if(_request.getTimeStamp()==0)
                _request.setTimeStamp(System.currentTimeMillis());
            _request.setMethod(method.toString());

            try
            {
                _uri.parse(uri.array(), uri.getIndex(), uri.length());
                _request.setUri(_uri);

                if (version==null)
                {
                    _request.setProtocol(HttpVersions.HTTP_0_9);
                    _version=HttpVersions.HTTP_0_9_ORDINAL;
                }
                else
                {
                    version= HttpVersions.CACHE.get(version);
                    _version = HttpVersions.CACHE.getOrdinal(version);
                    if (_version <= 0) _version = HttpVersions.HTTP_1_0_ORDINAL;
                    _request.setProtocol(version.toString());
                }

                _head = method == HttpMethods.HEAD_BUFFER; // depends on method being decached.
            }
            catch (Exception e)
            {
                Log.debug(e);
                throw new HttpException(HttpStatus.BAD_REQUEST_400,null,e);
            }
        }

        /*
         * @see org.eclipse.jetty.server.server.HttpParser.EventHandler#parsedHeaderValue(org.eclipse.io.Buffer)
         */
        @Override
        public void parsedHeader(Buffer name, Buffer value)
        {
            int ho = HttpHeaders.CACHE.getOrdinal(name);
            switch (ho)
            {
                case HttpHeaders.HOST_ORDINAL:
                    // TODO check if host matched a host in the URI.
                    _host = true;
                    break;

                case HttpHeaders.EXPECT_ORDINAL:
                    value = HttpHeaderValues.CACHE.lookup(value);
                    switch(HttpHeaderValues.CACHE.getOrdinal(value))
                    {
                        case HttpHeaderValues.CONTINUE_ORDINAL:
                            _expect100Continue=_generator instanceof HttpGenerator;
                            break;

                        case HttpHeaderValues.PROCESSING_ORDINAL:
                            _expect102Processing=_generator instanceof HttpGenerator;
                            break;

                        default:
                            String[] values = value.toString().split(",");
                            for  (int i=0;values!=null && i<values.length;i++)
                            {
                                CachedBuffer cb=HttpHeaderValues.CACHE.get(values[i].trim());
                                if (cb==null)
                                    _expect=true;
                                else
                                {
                                    switch(cb.getOrdinal())
                                    {
                                        case HttpHeaderValues.CONTINUE_ORDINAL:
                                            _expect100Continue=_generator instanceof HttpGenerator;
                                            break;
                                        case HttpHeaderValues.PROCESSING_ORDINAL:
                                            _expect102Processing=_generator instanceof HttpGenerator;
                                            break;
                                        default:
                                            _expect=true;
                                    }
                                }
                            }
                    }
                    break;

                case HttpHeaders.ACCEPT_ENCODING_ORDINAL:
                case HttpHeaders.USER_AGENT_ORDINAL:
                    value = HttpHeaderValues.CACHE.lookup(value);
                    break;

                case HttpHeaders.CONTENT_TYPE_ORDINAL:
                    value = MimeTypes.CACHE.lookup(value);
                    _charset=MimeTypes.getCharsetFromContentType(value);
                    break;

                case HttpHeaders.CONNECTION_ORDINAL:
                    //looks rather clumsy, but the idea is to optimize for a single valued header
                    switch(HttpHeaderValues.CACHE.getOrdinal(value))
                    {
                        case -1:
                        {
                            String[] values = value.toString().split(",");
                            for  (int i=0;values!=null && i<values.length;i++)
                            {
                                CachedBuffer cb = HttpHeaderValues.CACHE.get(values[i].trim());

                                if (cb!=null)
                                {
                                    switch(cb.getOrdinal())
                                    {
                                        case HttpHeaderValues.CLOSE_ORDINAL:
                                            _responseFields.add(HttpHeaders.CONNECTION_BUFFER,HttpHeaderValues.CLOSE_BUFFER);
                                            _generator.setPersistent(false);
                                            break;

                                        case HttpHeaderValues.KEEP_ALIVE_ORDINAL:
                                            if (_version==HttpVersions.HTTP_1_0_ORDINAL)
                                                _responseFields.add(HttpHeaders.CONNECTION_BUFFER,HttpHeaderValues.KEEP_ALIVE_BUFFER);
                                            break;
                                    }
                                }
                            }
                            break;
                        }
                        case HttpHeaderValues.CLOSE_ORDINAL:
                            _responseFields.put(HttpHeaders.CONNECTION_BUFFER,HttpHeaderValues.CLOSE_BUFFER);
                            _generator.setPersistent(false);
                            break;

                        case HttpHeaderValues.KEEP_ALIVE_ORDINAL:
                            if (_version==HttpVersions.HTTP_1_0_ORDINAL)
                                _responseFields.put(HttpHeaders.CONNECTION_BUFFER,HttpHeaderValues.KEEP_ALIVE_BUFFER);
                            break;
                    }
            }

            _requestFields.add(name, value);
        }

        /*
         * @see org.eclipse.jetty.server.server.HttpParser.EventHandler#headerComplete()
         */
        @Override
        public void headerComplete() throws IOException
        {
            _requests++;
            _generator.setVersion(_version);
            switch (_version)
            {
                case HttpVersions.HTTP_0_9_ORDINAL:
                    break;
                case HttpVersions.HTTP_1_0_ORDINAL:
                    _generator.setHead(_head);
                    break;
                case HttpVersions.HTTP_1_1_ORDINAL:
                    _generator.setHead(_head);

                    if (_server.getSendDateHeader())
                        _generator.setDate(_request.getTimeStampBuffer());

                    if (!_host)
                    {
                        _generator.setResponse(HttpStatus.BAD_REQUEST_400, null);
                        _responseFields.put(HttpHeaders.CONNECTION_BUFFER, HttpHeaderValues.CLOSE_BUFFER);
                        _generator.completeHeader(_responseFields, true);
                        _generator.complete();
                        return;
                    }

                    if (_expect)
                    {
                        _generator.sendError(HttpStatus.EXPECTATION_FAILED_417, null, null, true);
                        return;
                    }

                    break;
                default:
            }

            if(_charset!=null)
                _request.setCharacterEncodingUnchecked(_charset);

            // Either handle now or wait for first content
            if ((((HttpParser)_parser).getContentLength()<=0 && !((HttpParser)_parser).isChunking())||_expect100Continue)
                handleRequest();
            else
                _delayedHandling=true;
        }

        /* ------------------------------------------------------------ */
        /*
         * @see org.eclipse.jetty.server.server.HttpParser.EventHandler#content(int, org.eclipse.io.Buffer)
         */
        @Override
        public void content(Buffer ref) throws IOException
        {
            if (_delayedHandling)
            {
                _delayedHandling=false;
                handleRequest();
            }
        }

        /* ------------------------------------------------------------ */
        /*
         * (non-Javadoc)
         *
         * @see org.eclipse.jetty.server.server.HttpParser.EventHandler#messageComplete(int)
         */
        @Override
        public void messageComplete(long contentLength) throws IOException
        {
            if (_delayedHandling)
            {
                _delayedHandling=false;
                handleRequest();
            }
        }

        /* ------------------------------------------------------------ */
        /*
         * (non-Javadoc)
         *
         * @see org.eclipse.jetty.server.server.HttpParser.EventHandler#startResponse(org.eclipse.io.Buffer, int,
         *      org.eclipse.io.Buffer)
         */
        @Override
        public void startResponse(Buffer version, int status, Buffer reason)
        {
            Log.debug("Bad request!: "+version+" "+status+" "+reason);
        }
    }


    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    public class Output extends HttpOutput
    {
        Output()
        {
            super((AbstractGenerator)HttpConnection.this._generator,
                  _connector.isLowResources()?_connector.getLowResourceMaxIdleTime():_connector.getMaxIdleTime());
        }

        /* ------------------------------------------------------------ */
        /*
         * @see java.io.OutputStream#close()
         */
        @Override
        public void close() throws IOException
        {
            if (_closed)
                return;

            if (!isIncluding() && !super._generator.isCommitted())
                commitResponse(Generator.LAST);
            else
                flushResponse();

            super.close();
        }


        /* ------------------------------------------------------------ */
        /*
         * @see java.io.OutputStream#flush()
         */
        @Override
        public void flush() throws IOException
        {
            if (!super._generator.isCommitted())
                commitResponse(Generator.MORE);
            super.flush();
        }

        /* ------------------------------------------------------------ */
        /*
         * @see javax.servlet.ServletOutputStream#print(java.lang.String)
         */
        @Override
        public void print(String s) throws IOException
        {
            if (_closed)
                throw new IOException("Closed");
            PrintWriter writer=getPrintWriter(null);
            writer.print(s);
        }

        /* ------------------------------------------------------------ */
        public void sendResponse(Buffer response) throws IOException
        {
            ((HttpGenerator)super._generator).sendResponse(response);
        }

        /* ------------------------------------------------------------ */
        public void sendContent(Object content) throws IOException
        {
            Resource resource=null;

            if (_closed)
                throw new IOException("Closed");

            if (super._generator.getContentWritten() > 0)
                throw new IllegalStateException("!empty");

            if (content instanceof HttpContent)
            {
                HttpContent c = (HttpContent) content;
                Buffer contentType = c.getContentType();
                if (contentType != null && !_responseFields.containsKey(HttpHeaders.CONTENT_TYPE_BUFFER))
                {
                    String enc = _response.getSetCharacterEncoding();
                    if(enc==null)
                        _responseFields.add(HttpHeaders.CONTENT_TYPE_BUFFER, contentType);
                    else
                    {
                        if(contentType instanceof CachedBuffer)
                        {
                            CachedBuffer content_type = ((CachedBuffer)contentType).getAssociate(enc);
                            if(content_type!=null)
                                _responseFields.put(HttpHeaders.CONTENT_TYPE_BUFFER, content_type);
                            else
                            {
                                _responseFields.put(HttpHeaders.CONTENT_TYPE_BUFFER,
                                        contentType+";charset="+QuotedStringTokenizer.quote(enc,";= "));
                            }
                        }
                        else
                        {
                            _responseFields.put(HttpHeaders.CONTENT_TYPE_BUFFER,
                                    contentType+";charset="+QuotedStringTokenizer.quote(enc,";= "));
                        }
                    }
                }
                if (c.getContentLength() > 0)
                    _responseFields.putLongField(HttpHeaders.CONTENT_LENGTH_BUFFER, c.getContentLength());
                Buffer lm = c.getLastModified();
                long lml=c.getResource().lastModified();
                if (lm != null)
                    _responseFields.put(HttpHeaders.LAST_MODIFIED_BUFFER, lm,lml);
                else if (c.getResource()!=null)
                {
                    if (lml!=-1)
                        _responseFields.putDateField(HttpHeaders.LAST_MODIFIED_BUFFER, lml);
                }

                content = c.getBuffer();
                if (content==null)
                    content=c.getInputStream();
            }
            else if (content instanceof Resource)
            {
                resource=(Resource)content;
                _responseFields.putDateField(HttpHeaders.LAST_MODIFIED_BUFFER, resource.lastModified());
                content=resource.getInputStream();
            }


            if (content instanceof Buffer)
            {
                super._generator.addContent((Buffer) content, Generator.LAST);
                commitResponse(Generator.LAST);
            }
            else if (content instanceof InputStream)
            {
                InputStream in = (InputStream)content;

                try
                {
                    int max = super._generator.prepareUncheckedAddContent();
                    Buffer buffer = super._generator.getUncheckedBuffer();

                    int len=buffer.readFrom(in,max);

                    while (len>=0)
                    {
                        super._generator.completeUncheckedAddContent();
                        _out.flush();

                        max = super._generator.prepareUncheckedAddContent();
                        buffer = super._generator.getUncheckedBuffer();
                        len=buffer.readFrom(in,max);
                    }
                    super._generator.completeUncheckedAddContent();
                    _out.flush();
                }
                finally
                {
                    if (resource!=null)
                        resource.release();
                    else
                        in.close();

                }
            }
            else
                throw new IllegalArgumentException("unknown content type?");


        }
    }

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    public class OutputWriter extends HttpWriter
    {
        OutputWriter()
        {
            super(HttpConnection.this._out);
        }
    }

}
