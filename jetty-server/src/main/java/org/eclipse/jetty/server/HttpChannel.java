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
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;

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
import org.eclipse.jetty.http.HttpParser.RequestHandler;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.io.AbstractConnection;

import org.eclipse.jetty.io.Buffers;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.EofException;
import org.eclipse.jetty.io.RuntimeIOException;
import org.eclipse.jetty.io.UncheckedPrintWriter;
import org.eclipse.jetty.server.HttpServerTestBase.AvailableHandler;
import org.eclipse.jetty.server.nio.NIOConnector;
import org.eclipse.jetty.server.ssl.SslConnector;
import org.eclipse.jetty.util.QuotedStringTokenizer;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.thread.Timeout.Task;

/**
 *
 */
public class HttpChannel
{
    private static final Logger LOG = Log.getLogger(HttpChannel.class);

    private static final ThreadLocal<HttpChannel> __currentConnection = new ThreadLocal<HttpChannel>();

    private int _requests;

    protected final Server _server;
    protected final HttpURI _uri;

    protected final HttpFields _requestFields;
    protected final Request _request;
    protected final AsyncContinuation _async;
    protected volatile ServletInputStream _in;

    protected final HttpFields _responseFields;
    protected final Response _response;
    protected volatile Output _out;
    protected volatile OutputWriter _writer;
    protected volatile PrintWriter _printWriter;

    int _include;

    private HttpVersion _version = HttpVersion.HTTP_1_1;

    private boolean _expect = false;
    private boolean _expect100Continue = false;
    private boolean _expect102Processing = false;
    private boolean _host = false;

    /* ------------------------------------------------------------ */
    public static HttpChannel getCurrentConnection()
    {
        return __currentConnection.get();
    }

    /* ------------------------------------------------------------ */
    protected static void setCurrentConnection(HttpChannel connection)
    {
        __currentConnection.set(connection);
    }

    /* ------------------------------------------------------------ */
    /** Constructor
     *
     */
    public HttpChannel(Server server)
    {
        _uri = new HttpURI(URIUtil.__CHARSET);
        _requestFields = new HttpFields();
        _responseFields = new HttpFields(server.getMaxCookieVersion());
        _request = new Request(this);
        _response = new Response(this);
        _server = server;
        _async = _request.getAsyncContinuation();
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
    public AsyncContinuation getAsyncContinuation()
    {
        return _async;
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
            if (!isContentAvailable())
            {
                if (isResponseCommitted())
                    throw new IllegalStateException("Committed before 100 Continues");

                send1xx(HttpStatus.CONTINUE_100);
            }
            _expect100Continue=false;
        }

        if (_in == null)
            _in = new HttpInput(HttpChannel.this);
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
    public void reset()
    {
        _requestFields.clear();
        _request.recycle();
        _responseFields.clear();
        _response.recycle();
        _uri.clear();
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
            boolean handling=_async.handling() && server!=null && server.isRunning();
            while (handling)
            {
                _request.setHandled(false);

                String info=null;
                try
                {
                    _uri.getPort();
                    info=URIUtil.canonicalPath(_uri.getDecodedPath());
                    if (info==null && !_request.getMethod().equals(HttpMethod.CONNECT))
                        throw new HttpException(400);
                    _request.setPathInfo(info);

                    if (_out!=null)
                        _out.reopen();

                    if (_async.isInitial())
                    {
                        _request.setDispatcherType(DispatcherType.REQUEST);
                        customize(_request);
                        server.handle(this);
                    }
                    else
                    {
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
                    sendError(info==null?400:500, null, null, true);
                }
                finally
                {
                    handling = !_async.unhandle() && server.isRunning() && _server!=null;
                }
            }
        }
        finally
        {
            if (threadName!=null)
                Thread.currentThread().setName(threadName);

            if (_async.isUncompleted())
            {
                _async.doComplete(async_exception);

                if (_expect100Continue)
                {
                    LOG.debug("100 continues not sent");
                    // We didn't send 100 continues, but the latest interpretation
                    // of the spec (see httpbis) is that the client will either
                    // send the body anyway, or close.  So we no longer need to
                    // do anything special here other than make the connection not persistent
                    _expect100Continue = false;
                    if (!_response.isCommitted())
                        setPersistent(false);
                }

                if (error)
                    setPersistent(false);
                else if (!_response.isCommitted() && !_request.isHandled())
                    _response.sendError(HttpServletResponse.SC_NOT_FOUND);

                _response.complete();
                if (isPersistent())
                    persist();

                _request.setHandled(true);
            }
        }
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
    public String toString()
    {
        return String.format("%s r=%d",
                super.toString(),
                _requests);
    }

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    private class RequestHandler implements HttpParser.RequestHandler
    {
        @Override
        public boolean startRequest(String method, String uri, String version) throws IOException
        {
            _host = false;
            _expect = false;
            _expect100Continue=false;
            _expect102Processing=false;

            if(_request.getTimeStamp()==0)
                _request.setTimeStamp(System.currentTimeMillis());
            HttpMethod m = HttpMethod.CACHE.get(method);
            _request.setMethod(m,method);

            try
            {
                if (m==HttpMethod.CONNECT)
                    _uri.parseConnect(uri);
                else
                    _uri.parse(uri);

                _request.setUri(_uri);

                if (version==null)
                {
                    _request.setHttpVersion(HttpVersion.HTTP_0_9);
                    _version=HttpVersion.HTTP_0_9;
                }
                else
                {
                    _version= HttpVersion.CACHE.get(version);
                    if (_version==null)
                        throw new HttpException(HttpStatus.BAD_REQUEST_400,null);
                    _request.setHttpVersion(_version);
                }
            }
            catch (Exception e)
            {
                LOG.debug(e);
                if (e instanceof HttpException)
                    throw (HttpException)e;
                throw new HttpException(HttpStatus.BAD_REQUEST_400,null,e);
            }
            
            return false;
        }

        @Override
        public boolean parsedHeader(HttpHeader header, String name, String value) throws IOException
        {
            switch (header)
            {
                case HOST:
                    // TODO check if host matched a host in the URI.
                    _host = true;
                    break;

                case EXPECT:
                    HttpHeaderValue expect = HttpHeaderValue.CACHE.get(value);
                    switch(expect==null?HttpHeaderValue.UNKNOWN:expect)
                    {
                        case CONTINUE:
                            _expect100Continue=true;
                            break;

                        case PROCESSING:
                            _expect102Processing=true;
                            break;

                        default:
                            String[] values = value.toString().split(",");
                            for  (int i=0;values!=null && i<values.length;i++)
                            {
                                expect=HttpHeaderValue.CACHE.get(values[i].trim());
                                if (expect==null)
                                    _expect=true;
                                else
                                {
                                    switch(expect)
                                    {
                                        case CONTINUE:
                                            _expect100Continue=true;
                                            break;
                                        case PROCESSING:
                                            _expect102Processing=true;
                                            break;
                                        default:
                                            _expect=true;
                                    }
                                }
                            }
                    }
                    break;

                case CONTENT_TYPE:
                    MimeTypes.Type mime=MimeTypes.CACHE.get(value);
                    String charset=(mime==null)?MimeTypes.getCharsetFromContentType(value):mime.getCharset().toString();
                    if (charset!=null)
                        _request.setCharacterEncodingUnchecked(charset);
                    break;
            }

            _requestFields.add(name, value);
            return false;
        }

        @Override
        public boolean headerComplete() throws IOException
        {
            _requests++;
            switch (_version)
            {
                case HTTP_0_9:
                    break;
                case HTTP_1_0:
                    if (isPersistent())
                    {
                        _responseFields.add(HttpHeader.CONNECTION,HttpHeaderValue.KEEP_ALIVE);
                    }

                    if (_server.getSendDateHeader())
                        _responseFields.putDateField(HttpHeader.DATE.toString(),_request.getTimeStamp());
                    break;

                case HTTP_1_1:

                    if (!isPersistent())
                    {
                        _responseFields.add(HttpHeader.CONNECTION,HttpHeaderValue.CLOSE);
                    }
                    if (_server.getSendDateHeader())
                        _responseFields.putDateField(HttpHeader.DATE.toString(),_request.getTimeStamp());

                    if (!_host)
                    {
                        LOG.debug("!host {}",this);
                        _responseFields.put(HttpHeader.CONNECTION, HttpHeaderValue.CLOSE);
                        sendError(HttpStatus.BAD_REQUEST_400,null,null,true);
                        return true;
                    }

                    if (_expect)
                    {
                        LOG.debug("!expectation {}",this);
                        _responseFields.put(HttpHeader.CONNECTION, HttpHeaderValue.CLOSE);
                        sendError(HttpStatus.EXPECTATION_FAILED_417,null,null,true);
                        return true;
                    }

                    break;
                default:
            }

            // Either handle now or wait for first content
            if (_expect100Continue)
                return true;
            
            return false;
        }
        
        @Override
        public boolean content(ByteBuffer ref) throws IOException
        {
            // TODO queue the content
            return true;
        }

        @Override
        public boolean messageComplete(long contentLength) throws IOException
        {
            return true;
        }

        @Override
        public boolean earlyEOF()
        {
            return true;
        }

    }



    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    public class Output extends HttpOutput
    {
        Output()
        {
            super(HttpChannel.this);
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
        public void sendContent(Object content) throws IOException
        {
            Resource resource=null;

            if (isClosed())
                throw new IOException("Closed");

            // Convert HTTP content to contentl
            if (content instanceof HttpContent)
            {
                HttpContent httpContent = (HttpContent) content;
                String contentType = httpContent.getContentType();
                if (contentType != null)
                    _responseFields.put(HttpHeader.CONTENT_TYPE, contentType);
                    
                if (httpContent.getContentLength() > 0)
                    _responseFields.putLongField(HttpHeader.CONTENT_LENGTH, httpContent.getContentLength());
                
                String lm = httpContent.getLastModified();
                if (lm != null)
                    _responseFields.put(HttpHeader.LAST_MODIFIED, lm);
                else if (httpContent.getResource()!=null)
                {
                    long lml=httpContent.getResource().lastModified();
                    if (lml!=-1)
                        _responseFields.putDateField(HttpHeader.LAST_MODIFIED, lml);
                }

                content = httpContent.getDirectBuffer();
                if (content==null)
                    content=httpContent.getIndirectBuffer();
                if (content==null)
                    content=httpContent.getInputStream();
            }
            else if (content instanceof Resource)
            {
                resource=(Resource)content;
                _responseFields.putDateField(HttpHeader.LAST_MODIFIED, resource.lastModified());
                content=resource.getInputStream();
            }

            // Process content.
            if (content instanceof ByteBuffer)
            {
                throw new IllegalArgumentException("not implemented!");
            }
            else if (content instanceof InputStream)
            {
                throw new IllegalArgumentException("not implemented!");
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
            super(HttpChannel.this._out);
        }
    }
    
    /* ------------------------------------------------------------ */
    public ByteBuffer getContent() throws IOException
    {
        // TODO Auto-generated method stub
        return null;
    }

    /* ------------------------------------------------------------ */
    public ByteBuffer blockForContent() throws IOException
    {
        // TODO Auto-generated method stub
        return null;
    }
    
    /* ------------------------------------------------------------ */
    public void write(ByteBuffer wrap) throws IOException
    {
        // TODO Auto-generated method stub
    }

    /* ------------------------------------------------------------ */
    public int getContentWritten()
    {
        // TODO Auto-generated method stub
        return 0;
    }
    
    /* ------------------------------------------------------------ */
    public void sendError(int status, String reason, String content, boolean close)  throws IOException
    {
        // TODO Auto-generated method stub
        
    }
    
    /* ------------------------------------------------------------ */
    public void send1xx(int processing102)
    {
        // TODO Auto-generated method stub
        
    }

    public boolean isAllContentWritten()
    {
        // TODO Auto-generated method stub
        return false;
    }

    public int getContentBufferSize()
    {
        // TODO Auto-generated method stub
        return 0;
    }

    public void increaseContentBufferSize(int size)
    {
        // TODO Auto-generated method stub
        
    }

    public void resetBuffer()
    {
        // TODO Auto-generated method stub
        
    }

    private boolean isContentAvailable()
    {
        // TODO Auto-generated method stub
        return false;
    }

    public boolean isResponseCommitted()
    {
        // TODO Auto-generated method stub
        return false;
    }

    public boolean isPersistent()
    {
        // TODO Auto-generated method stub
        return false;
    }
    
    public void setPersistent(boolean persistent)
    {
    }

    public InetSocketAddress getLocalAddress()
    {
        // TODO Auto-generated method stub
        return null;
    }


    public InetSocketAddress getRemoteAddress()
    {
        // TODO Auto-generated method stub
        return null;
    }
    
    public String getMaxIdleTime()
    {
        // TODO Auto-generated method stub
        return null;
    }

    public void flushResponse()
    {
        // TODO Auto-generated method stub
        
    }

    public void completeResponse()
    {
        // TODO Auto-generated method stub
        
    }

    public void asyncDispatch()
    {
        // TODO Auto-generated method stub
        
    }

    public void scheduleTimeout(Task timeout, long timeoutMs)
    {
        // TODO Auto-generated method stub
        
    }

    public void cancelTimeout(Task timeout)
    {
        // TODO Auto-generated method stub
        
    }


    private void persist()
    {
        // TODO Auto-generated method stub
        
    }

    private void customize(Request request)
    {
        // TODO Auto-generated method stub
        
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @return The result of calling {@link #getConnector}.{@link Connector#isConfidential(Request) isCondidential}(request), or false
     *  if there is no connector.
     */
    public boolean isConfidential(Request request)
    {
        // TODO
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
        // TODO
        return false;
    }

    
    public HttpParser.RequestHandler getRequestHandler()
    {
        return _handler;
    }

    public HttpGenerator.ResponseInfo getResponseInfo()
    {
        return _info;
    }
    
    private final RequestHandler _handler = new RequestHandler();
    private final HttpGenerator.ResponseInfo _info = new HttpGenerator.ResponseInfo()
    {
        @Override
        public HttpVersion getHttpVersion()
        {
            return getRequest().getHttpVersion();
        }
        
        @Override
        public HttpFields getHttpFields()
        {
            return _responseFields;
        }
        
        @Override
        public long getContentLength()
        {
            return _response.getLongContentLength();
        }
        
        @Override
        public boolean isHead()
        {
            return getRequest().isHead();
        }
        
        @Override
        public int getStatus()
        {
            return _response.getStatus();
        }
        
        @Override
        public String getReason()
        {
            return _response.getReason();
        }
    };
    
}
