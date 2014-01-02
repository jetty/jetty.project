//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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
import java.io.InputStream;
import java.io.PrintWriter;

import javax.servlet.DispatcherType;
import javax.servlet.RequestDispatcher;
import javax.servlet.ServletInputStream;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.continuation.ContinuationThrowable;
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
import org.eclipse.jetty.http.PathMap;
import org.eclipse.jetty.io.AbstractConnection;
import org.eclipse.jetty.io.Buffer;
import org.eclipse.jetty.io.BufferCache.CachedBuffer;
import org.eclipse.jetty.io.Buffers;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.EofException;
import org.eclipse.jetty.io.RuntimeIOException;
import org.eclipse.jetty.io.UncheckedPrintWriter;
import org.eclipse.jetty.server.handler.ErrorHandler;
import org.eclipse.jetty.server.nio.NIOConnector;
import org.eclipse.jetty.server.ssl.SslConnector;
import org.eclipse.jetty.util.QuotedStringTokenizer;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.resource.Resource;

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
 * </p>
 * <p>
 * The HttpConnection support protocol upgrade.  If on completion of a request, the
 * response code is 101 (switch protocols), then the org.eclipse.jetty.io.Connection
 * request attribute is checked to see if there is a new Connection instance. If so,
 * the new connection is returned from {@link #handle()} and is used for future
 * handling of the underlying connection.   Note that for switching protocols that
 * don't use 101 responses (eg CONNECT), the response should be sent and then the
 * status code changed to 101 before returning from the handler.  Implementors
 * of new Connection types should be careful to extract any buffered data from
 * (HttpParser)http.getParser()).getHeaderBuffer() and
 * (HttpParser)http.getParser()).getBodyBuffer() to initialise their new connection.
 * </p>
 *
 */
public abstract class AbstractHttpConnection  extends AbstractConnection
{
    private static final Logger LOG = Log.getLogger(AbstractHttpConnection.class);

    private static final int UNKNOWN = -2;
    private static final ThreadLocal<AbstractHttpConnection> __currentConnection = new ThreadLocal<AbstractHttpConnection>();

    private int _requests;

    protected final Connector _connector;
    protected final Server _server;
    protected final HttpURI _uri;

    protected final Parser _parser;
    protected final HttpFields _requestFields;
    protected final Request _request;
    protected volatile ServletInputStream _in;

    protected final Generator _generator;
    protected final HttpFields _responseFields;
    protected final Response _response;
    protected volatile Output _out;
    protected volatile OutputWriter _writer;
    protected volatile PrintWriter _printWriter;

    int _include;

    private Object _associatedObject; // associated object

    private int _version = UNKNOWN;

    private String _charset;
    private boolean _expect = false;
    private boolean _expect100Continue = false;
    private boolean _expect102Processing = false;
    private boolean _head = false;
    private boolean _host = false;
    private boolean _delayedHandling=false;
    private boolean _earlyEOF = false;

    /* ------------------------------------------------------------ */
    public static AbstractHttpConnection getCurrentConnection()
    {
        return __currentConnection.get();
    }

    /* ------------------------------------------------------------ */
    protected static void setCurrentConnection(AbstractHttpConnection connection)
    {
        __currentConnection.set(connection);
    }

    /* ------------------------------------------------------------ */
    public AbstractHttpConnection(Connector connector, EndPoint endpoint, Server server)
    {
        super(endpoint);
        _uri = StringUtil.__UTF8.equals(URIUtil.__CHARSET)?new HttpURI():new EncodedHttpURI(URIUtil.__CHARSET);
        _connector = connector;
        HttpBuffers ab = (HttpBuffers)_connector;
        _parser = newHttpParser(ab.getRequestBuffers(), endpoint, new RequestHandler());
        _requestFields = new HttpFields();
        _responseFields = new HttpFields();
        _request = new Request(this);
        _response = new Response(this);
        _generator = newHttpGenerator(ab.getResponseBuffers(), endpoint);
        _generator.setSendServerVersion(server.getSendServerVersion());
        _server = server;
    }

    /* ------------------------------------------------------------ */
    protected AbstractHttpConnection(Connector connector, EndPoint endpoint, Server server,
            Parser parser, Generator generator, Request request)
    {
        super(endpoint);

        _uri = URIUtil.__CHARSET.equals(StringUtil.__UTF8)?new HttpURI():new EncodedHttpURI(URIUtil.__CHARSET);
        _connector = connector;
        _parser = parser;
        _requestFields = new HttpFields();
        _responseFields = new HttpFields();
        _request = request;
        _response = new Response(this);
        _generator = generator;
        _generator.setSendServerVersion(server.getSendServerVersion());
        _server = server;
    }

    protected HttpParser newHttpParser(Buffers requestBuffers, EndPoint endpoint, HttpParser.EventHandler requestHandler)
    {
        return new HttpParser(requestBuffers, endpoint, requestHandler);
    }

    protected HttpGenerator newHttpGenerator(Buffers responseBuffers, EndPoint endPoint)
    {
        return new HttpGenerator(responseBuffers, endPoint);
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
    public Server getServer()
    {
        return _server;
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
     * Find out if the request supports CONFIDENTIAL security.
     * @param request the incoming HTTP request
     * @return the result of calling {@link Connector#isConfidential(Request)}, or false
     * if there is no connector
     */
    public boolean isConfidential(Request request)
    {
        return _connector != null && _connector.isConfidential(request);
    }

    /* ------------------------------------------------------------ */
    /**
     * Find out if the request supports INTEGRAL security.
     * @param request the incoming HTTP request
     * @return the result of calling {@link Connector#isIntegral(Request)}, or false
     * if there is no connector
     */
    public boolean isIntegral(Request request)
    {
        return _connector != null && _connector.isIntegral(request);
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
     * @throws IOException if the input stream cannot be retrieved
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
            _in = new HttpInput(AbstractHttpConnection.this);
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
     * @param encoding the PrintWriter encoding
     * @return A {@link PrintWriter} wrapping the {@link #getOutputStream output stream}. The writer is created if it
     *    does not already exist.
     */
    public PrintWriter getPrintWriter(String encoding)
    {
        getOutputStream();
        if (_writer==null)
        {
            _writer=new OutputWriter();
            if (_server.isUncheckedPrintWriter())
                _printWriter=new UncheckedPrintWriter(_writer);
            else
                _printWriter = new PrintWriter(_writer)
                {
                    public void close()
                    {
                        synchronized (lock)
                        {
                            try
                            {
                                out.close();
                            }
                            catch (IOException e)
                            {
                                setError();
                            }
                        }
                    }
                };
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
    public boolean isEarlyEOF()
    {
        return _earlyEOF;
    }

    /* ------------------------------------------------------------ */
    public void reset()
    {
        _parser.reset();
        _parser.returnBuffers(); // TODO maybe only on unhandle
        _requestFields.clear();
        _request.recycle();
        _generator.reset();
        _generator.returnBuffers();// TODO maybe only on unhandle
        _responseFields.clear();
        _response.recycle();
        _uri.clear();
        _writer=null;
        _earlyEOF = false;
    }

    /* ------------------------------------------------------------ */
    protected void handleRequest() throws IOException
    {
        boolean error = false;

        String threadName=null;
        Throwable async_exception=null;
        try
        {
            if (LOG.isDebugEnabled())
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
            boolean was_continuation=_request._async.isContinuation();
            boolean handling=_request._async.handling() && server!=null && server.isRunning();
            while (handling)
            {
                _request.setHandled(false);

                String info=null;
                try
                {
                    _uri.getPort();
                    String path = null;

                    try
                    {
                        path = _uri.getDecodedPath();
                    }
                    catch (Exception e)
                    {
                        LOG.warn("Failed UTF-8 decode for request path, trying ISO-8859-1");
                        LOG.ignore(e);
                        path = _uri.getDecodedPath(StringUtil.__ISO_8859_1);
                    }

                    info=URIUtil.canonicalPath(path);
                    if (info==null && !_request.getMethod().equals(HttpMethods.CONNECT))
                    {
                        if (path==null && _uri.getScheme()!=null && _uri.getHost()!=null)
                        {
                            info="/";
                            _request.setRequestURI("");
                        }
                        else
                            throw new HttpException(400);
                    }
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
                        if (_request._async.isExpired()&&!was_continuation)
                        {
                            async_exception = (Throwable)_request.getAttribute(RequestDispatcher.ERROR_EXCEPTION);
                            _response.setStatus(500,async_exception==null?"Async Timeout":"Async Exception");
                            _request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE,new Integer(500));
                            _request.setAttribute(RequestDispatcher.ERROR_MESSAGE, _response.getReason());
                            _request.setDispatcherType(DispatcherType.ERROR);
                            
                            ErrorHandler eh = _request._async.getContextHandler().getErrorHandler();
                            if (eh instanceof ErrorHandler.ErrorPageMapper)
                            {
                                String error_page=((ErrorHandler.ErrorPageMapper)eh).getErrorPage((HttpServletRequest)_request._async.getRequest());
                                if (error_page!=null)
                                { 
                                    AsyncContinuation.AsyncEventState state = _request._async.getAsyncEventState();
                                    state.setPath(error_page);
                                }
                            }
                        }
                        else
                            _request.setDispatcherType(DispatcherType.ASYNC);
                        server.handleAsync(this);
                    }
                }
                catch (ContinuationThrowable e)
                {
                    LOG.ignore(e);
                }
                catch (EofException e)
                {
                    async_exception=e;
                    LOG.debug(e);
                    error=true;
                    _request.setHandled(true);
                    if (!_response.isCommitted())
                        _generator.sendError(500, null, null, true);
                }
                catch (RuntimeIOException e)
                {
                    async_exception=e;
                    LOG.debug(e);
                    error=true;
                    _request.setHandled(true);
                }
                catch (HttpException e)
                {
                    LOG.debug(e);
                    error=true;
                    _request.setHandled(true);
                    _response.sendError(e.getStatus(), e.getReason());
                }
                catch (Throwable e)
                {
                    async_exception=e;
                    LOG.warn(String.valueOf(_uri),e);
                    error=true;
                    _request.setHandled(true);
                    _generator.sendError(info==null?400:500, null, null, true);
                    
                }
                finally
                {
                    // Complete async requests 
                    if (error && _request.isAsyncStarted())
                        _request.getAsyncContinuation().errorComplete();
                        
                    was_continuation=_request._async.isContinuation();
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
                
                _request._async.doComplete(async_exception);

                if (_expect100Continue)
                {
                    LOG.debug("100 continues not sent");
                    // We didn't send 100 continues, but the latest interpretation
                    // of the spec (see httpbis) is that the client will either
                    // send the body anyway, or close.  So we no longer need to
                    // do anything special here other than make the connection not persistent
                    _expect100Continue = false;
                    if (!_response.isCommitted())
                        _generator.setPersistent(false);
                }

                if(_endp.isOpen())
                {
                    if (error)
                    {
                        _endp.shutdownOutput();
                        _generator.setPersistent(false);
                        if (!_generator.isComplete())
                            _response.complete();
                    }
                    else
                    {
                        if (!_response.isCommitted() && !_request.isHandled())
                            _response.sendError(HttpServletResponse.SC_NOT_FOUND);
                        _response.complete();
                        if (_generator.isPersistent())
                            _connector.persist(_endp);
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
    public abstract Connection handle() throws IOException;

    /* ------------------------------------------------------------ */
    public void commitResponse(boolean last) throws IOException
    {
        if (!_generator.isCommitted())
        {
            _generator.setResponse(_response.getStatus(), _response.getReason());
            try
            {
                // If the client was expecting 100 continues, but we sent something
                // else, then we need to close the connection
                if (_expect100Continue && _response.getStatus()!=100)
                    _generator.setPersistent(false);
                _generator.completeHeader(_responseFields, last);
            }
            catch(RuntimeException e)
            {
                LOG.warn("header full: " + e);

                _response.reset();
                _generator.reset();
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
            catch(RuntimeException e)
            {
                LOG.warn("header full: "+e);
                LOG.debug(e);

                _response.reset();
                _generator.reset();
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
    public void onClose()
    {
        LOG.debug("closed {}",this);
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
        return String.format("%s,g=%s,p=%s,r=%d",
                super.toString(),
                _generator,
                _parser,
                _requests);
    }

    /* ------------------------------------------------------------ */
    protected void startRequest(Buffer method, Buffer uri, Buffer version) throws IOException
    {
        uri=uri.asImmutableBuffer();

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
            _head=false;
            switch (HttpMethods.CACHE.getOrdinal(method))
            {
              case HttpMethods.CONNECT_ORDINAL:
                  _uri.parseConnect(uri.array(), uri.getIndex(), uri.length());
                  break;

              case HttpMethods.HEAD_ORDINAL:
                  _head=true;
                  _uri.parse(uri.array(), uri.getIndex(), uri.length());
                  break;

              default:
                  _uri.parse(uri.array(), uri.getIndex(), uri.length());
            }

            _request.setUri(_uri);

            if (version==null)
            {
                _request.setProtocol(HttpVersions.HTTP_0_9);
                _version=HttpVersions.HTTP_0_9_ORDINAL;
            }
            else
            {
                version= HttpVersions.CACHE.get(version);
                if (version==null)
                    throw new HttpException(HttpStatus.BAD_REQUEST_400,null);
                _version = HttpVersions.CACHE.getOrdinal(version);
                if (_version <= 0) _version = HttpVersions.HTTP_1_0_ORDINAL;
                _request.setProtocol(version.toString());
            }
        }
        catch (Exception e)
        {
            LOG.debug(e);
            if (e instanceof HttpException)
                throw (HttpException)e;
            throw new HttpException(HttpStatus.BAD_REQUEST_400,null,e);
        }
    }

    /* ------------------------------------------------------------ */
    protected void parsedHeader(Buffer name, Buffer value) throws IOException
    {
        int ho = HttpHeaders.CACHE.getOrdinal(name);
        switch (ho)
        {
            case HttpHeaders.HOST_ORDINAL:
                // TODO check if host matched a host in the URI.
                _host = true;
                break;

            case HttpHeaders.EXPECT_ORDINAL:
                if (_version>=HttpVersions.HTTP_1_1_ORDINAL)
                {
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
        }

        _requestFields.add(name, value);
    }

    /* ------------------------------------------------------------ */
    protected void headerComplete() throws IOException
    {
        // Handle idle race
        if (_endp.isOutputShutdown())
        {
            _endp.close();
            return;
        }
        
        _requests++;
        _generator.setVersion(_version);
        switch (_version)
        {
            case HttpVersions.HTTP_0_9_ORDINAL:
                break;
            case HttpVersions.HTTP_1_0_ORDINAL:
                _generator.setHead(_head);
                if (_parser.isPersistent())
                {
                    _responseFields.add(HttpHeaders.CONNECTION_BUFFER, HttpHeaderValues.KEEP_ALIVE_BUFFER);
                    _generator.setPersistent(true);
                }
                else if (HttpMethods.CONNECT.equals(_request.getMethod()))
                {
                    _generator.setPersistent(true);
                    _parser.setPersistent(true);
                }

                if (_server.getSendDateHeader())
                    _generator.setDate(_request.getTimeStampBuffer());
                break;

            case HttpVersions.HTTP_1_1_ORDINAL:
                _generator.setHead(_head);

                if (!_parser.isPersistent())
                {
                    _responseFields.add(HttpHeaders.CONNECTION_BUFFER,HttpHeaderValues.CLOSE_BUFFER);
                    _generator.setPersistent(false);
                }
                if (_server.getSendDateHeader())
                    _generator.setDate(_request.getTimeStampBuffer());

                if (!_host)
                {
                    LOG.debug("!host {}",this);
                    _generator.setResponse(HttpStatus.BAD_REQUEST_400, null);
                    _responseFields.put(HttpHeaders.CONNECTION_BUFFER, HttpHeaderValues.CLOSE_BUFFER);
                    _generator.completeHeader(_responseFields, true);
                    _generator.complete();
                    return;
                }

                if (_expect)
                {
                    LOG.debug("!expectation {}",this);
                    _generator.setResponse(HttpStatus.EXPECTATION_FAILED_417, null);
                    _responseFields.put(HttpHeaders.CONNECTION_BUFFER, HttpHeaderValues.CLOSE_BUFFER);
                    _generator.completeHeader(_responseFields, true);
                    _generator.complete();
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
    protected void content(Buffer buffer) throws IOException
    {
        if (_delayedHandling)
        {
            _delayedHandling=false;
            handleRequest();
        }
    }

    /* ------------------------------------------------------------ */
    public void messageComplete(long contentLength) throws IOException
    {
        if (_delayedHandling)
        {
            _delayedHandling=false;
            handleRequest();
        }
    }

    /* ------------------------------------------------------------ */
    public void earlyEOF()
    {
        _earlyEOF = true;
    }

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    private class RequestHandler extends HttpParser.EventHandler
    {
        /*
         *
         * @see org.eclipse.jetty.server.server.HttpParser.EventHandler#startRequest(org.eclipse.io.Buffer,
         *      org.eclipse.io.Buffer, org.eclipse.io.Buffer)
         */
        @Override
        public void startRequest(Buffer method, Buffer uri, Buffer version) throws IOException
        {
            AbstractHttpConnection.this.startRequest(method, uri, version);
        }

        /*
         * @see org.eclipse.jetty.server.server.HttpParser.EventHandler#parsedHeaderValue(org.eclipse.io.Buffer)
         */
        @Override
        public void parsedHeader(Buffer name, Buffer value) throws IOException
        {
            AbstractHttpConnection.this.parsedHeader(name, value);
        }

        /*
         * @see org.eclipse.jetty.server.server.HttpParser.EventHandler#headerComplete()
         */
        @Override
        public void headerComplete() throws IOException
        {
            AbstractHttpConnection.this.headerComplete();
        }

        /* ------------------------------------------------------------ */
        /*
         * @see org.eclipse.jetty.server.server.HttpParser.EventHandler#content(int, org.eclipse.io.Buffer)
         */
        @Override
        public void content(Buffer ref) throws IOException
        {
            AbstractHttpConnection.this.content(ref);
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
            AbstractHttpConnection.this.messageComplete(contentLength);
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
            if (LOG.isDebugEnabled())
                LOG.debug("Bad request!: "+version+" "+status+" "+reason);
        }

        /* ------------------------------------------------------------ */
        /*
         * (non-Javadoc)
         *
         * @see org.eclipse.jetty.server.server.HttpParser.EventHandler#earlyEOF()
         */
        @Override
        public void earlyEOF()
        {
            AbstractHttpConnection.this.earlyEOF();
        }
    }

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    public class Output extends HttpOutput
    {
        Output()
        {
            super(AbstractHttpConnection.this);
        }

        /* ------------------------------------------------------------ */
        /*
         * @see java.io.OutputStream#close()
         */
        @Override
        public void close() throws IOException
        {
            if (isClosed())
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
            if (isClosed())
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

            if (isClosed())
                throw new IOException("Closed");

            if (super._generator.isWritten())
                throw new IllegalStateException("!empty");

            // Convert HTTP content to content
            if (content instanceof HttpContent)
            {
                HttpContent httpContent = (HttpContent) content;
                Buffer contentType = httpContent.getContentType();
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
                                        contentType+";charset="+QuotedStringTokenizer.quoteIfNeeded(enc,";= "));
                            }
                        }
                        else
                        {
                            _responseFields.put(HttpHeaders.CONTENT_TYPE_BUFFER,
                                    contentType+";charset="+QuotedStringTokenizer.quoteIfNeeded(enc,";= "));
                        }
                    }
                }
                if (httpContent.getContentLength() > 0)
                    _responseFields.putLongField(HttpHeaders.CONTENT_LENGTH_BUFFER, httpContent.getContentLength());
                Buffer lm = httpContent.getLastModified();
                long lml=httpContent.getResource().lastModified();
                if (lm != null)
                {
                    _responseFields.put(HttpHeaders.LAST_MODIFIED_BUFFER, lm);
                }
                else if (httpContent.getResource()!=null)
                {
                    if (lml!=-1)
                        _responseFields.putDateField(HttpHeaders.LAST_MODIFIED_BUFFER, lml);
                }
                
                Buffer etag=httpContent.getETag();
                if (etag!=null)
                    _responseFields.put(HttpHeaders.ETAG_BUFFER,etag);

                
                boolean direct=_connector instanceof NIOConnector && ((NIOConnector)_connector).getUseDirectBuffers() && !(_connector instanceof SslConnector);
                content = direct?httpContent.getDirectBuffer():httpContent.getIndirectBuffer();
                if (content==null)
                    content=httpContent.getInputStream();
            }
            else if (content instanceof Resource)
            {
                resource=(Resource)content;
                _responseFields.putDateField(HttpHeaders.LAST_MODIFIED_BUFFER, resource.lastModified());
                content=resource.getInputStream();
            }

            // Process content.
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
            super(AbstractHttpConnection.this._out);
        }
    }


}
