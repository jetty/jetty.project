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
import java.nio.ByteBuffer;
import java.util.concurrent.ScheduledExecutorService;
import javax.servlet.DispatcherType;
import javax.servlet.ServletException;
import javax.servlet.ServletInputStream;

import org.eclipse.jetty.continuation.ContinuationThrowable;
import org.eclipse.jetty.http.HttpContent;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpGenerator;
import org.eclipse.jetty.http.HttpGenerator.ResponseInfo;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpHeaderValue;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpParser;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.EofException;
import org.eclipse.jetty.io.UncheckedPrintWriter;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.resource.Resource;

/**
 *
 */
public abstract class HttpChannel
{
    protected static final Logger LOG = Log.getLogger(HttpChannel.class);
    private static final ThreadLocal<HttpChannel> __currentChannel = new ThreadLocal<>();

    public static HttpChannel getCurrentHttpChannel()
    {
        return __currentChannel.get();
    }

    protected static void setCurrentHttpChannel(HttpChannel channel)
    {
        __currentChannel.set(channel);
    }

    private final Server _server;
    private final Connection _connection;
    private final HttpURI _uri;

    private final ChannelEventHandler _handler = new ChannelEventHandler();
    private final HttpChannelState _state;

    private final Request _request;

    private final Response _response;

    private int _requests;

    private HttpVersion _version = HttpVersion.HTTP_1_1;

    private boolean _expect = false;
    private boolean _expect100Continue = false;
    private boolean _expect102Processing = false;
    private boolean _host = false;


    /* ------------------------------------------------------------ */
    public HttpChannel(Server server,Connection connection,HttpInput input)
    {
        _server = server;
        _connection = connection;
        _uri = new HttpURI(URIUtil.__CHARSET);
        _state = new HttpChannelState(this);
        _request = new Request(this,input);
        _response = new Response(this,new Output());
    }

    /* ------------------------------------------------------------ */
    public HttpChannelState getState()
    {
        return _state;
    }

    /* ------------------------------------------------------------ */
    public EventHandler getEventHandler()
    {
        return _handler;
    }

    /* ------------------------------------------------------------ */
    public EndPoint getEndPoint()
    {
        return getConnection().getEndPoint();
    }

    /* ------------------------------------------------------------ */
    public boolean isIdle()
    {
        return _state.isIdle();
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
    public Connection getConnection()
    {
        return _connection;
    }

    /* ------------------------------------------------------------ */
    public InetSocketAddress getLocalAddress()
    {
        return _connection.getEndPoint().getLocalAddress();
    }

    /* ------------------------------------------------------------ */
    public InetSocketAddress getRemoteAddress()
    {
        return _connection.getEndPoint().getRemoteAddress();
    }

    /* ------------------------------------------------------------ */
    /**
     * If the associated response has the Expect header set to 100 Continue,
     * then accessing the input stream indicates that the handler/servlet
     * is ready for the request body and thus a 100 Continue response is sent.
     *
     * @return The input stream for this connection.
     * The stream will be created if it does not already exist.
     * @throws IOException if the InputStream cannot be created
     */
    public void continue100(int available) throws IOException
    {
        // If the client is expecting 100 CONTINUE, then send it now.
        if (_expect100Continue)
        {
            // is content missing?
            if (available==0)
            {
                if (_response.isCommitted())
                    throw new IllegalStateException("Committed before 100 Continues");
                commitResponse(HttpGenerator.CONTINUE_100_INFO,null);
            }
            _expect100Continue=false;
        }
    }

    /* ------------------------------------------------------------ */
    public void reset()
    {
        _expect=false;
        _expect100Continue=false;
        _expect102Processing=false;
        _request.recycle();
        _response.recycle();
        _uri.clear();
    }

    /* ------------------------------------------------------------ */
    protected void handle()
    {
        LOG.debug("{} process",this);

        String threadName=null;
        if (LOG.isDebugEnabled())
        {
            threadName=Thread.currentThread().getName();
            Thread.currentThread().setName(threadName+" - "+_uri);
        }

        setCurrentHttpChannel(this);
        try
        {
            // Loop here to handle async request redispatches.
            // The loop is controlled by the call to async.unhandle in the
            // finally block below.  Unhandle will return false only if an async dispatch has
            // already happened when unhandle is called.
            boolean handling=_state.handling();

            while(handling && getServer().isRunning())
            {
                try
                {
                    _request.setHandled(false);
                    _response.getHttpOutput().reopen();

                    if (_state.isInitial())
                    {
                        _request.setDispatcherType(DispatcherType.REQUEST);
                        getHttpConfiguration().customize(_request);
                        getServer().handle(this);
                    }
                    else
                    {
                        _request.setDispatcherType(DispatcherType.ASYNC);
                        getServer().handleAsync(this);
                    }

                }
                catch (ContinuationThrowable e)
                {
                    LOG.ignore(e);
                }
                catch (EofException e)
                {
                    LOG.debug(e);
                    _state.error(e);
                    _request.setHandled(true);
                }
                catch (ServletException e)
                {
                    LOG.warn(String.valueOf(_uri),e);
                    _state.error(e);
                    _request.setHandled(true);
                    commitError(500, null, e.toString());
                }
                catch (Throwable e)
                {
                    LOG.warn(String.valueOf(_uri),e);
                    _state.error(e);
                    _request.setHandled(true);
                    commitError(500, null, e.toString());
                }
                finally
                {
                    handling = !_state.unhandle();
                }
            }
        }
        finally
        {
            setCurrentHttpChannel(null);
            if (threadName!=null)
                Thread.currentThread().setName(threadName);

            if (_state.isCompleting())
            {
                try
                {
                    _state.completed();
                    if (_expect100Continue)
                    {
                        LOG.debug("100 continues not sent");
                        // We didn't send 100 continues, but the latest interpretation
                        // of the spec (see httpbis) is that the client will either
                        // send the body anyway, or close.  So we no longer need to
                        // do anything special here other than make the connection not persistent
                        _expect100Continue = false;
                        if (!_response.isCommitted())
                            _response.addHeader(HttpHeader.CONNECTION,HttpHeaderValue.CLOSE.toString());
                        else
                            LOG.warn("Can't close non-100 response");
                    }

                    if (!_response.isCommitted() && !_request.isHandled())
                        _response.sendError(404);

                    // Complete generating the response
                    _response.complete();

                    // Complete reading the request
                    _request.getHttpInput().consumeAll();
                }
                catch(EofException e)
                {
                    LOG.debug(e);
                }
                catch(Exception e)
                {
                    LOG.warn(e);
                }
                finally
                {
                    _request.setHandled(true);
                    completed();
                }
            }

            LOG.debug("{} !process",this);
        }
    }

    /* ------------------------------------------------------------ */
    protected boolean commitError(final int status, final String reason, String content)
    {
        LOG.debug("{} sendError {} {}",this,status,reason);

        if (_response.isCommitted())
            return false;

        try
        {
            _response.setStatus(status,reason);
            _response.getHttpFields().add(HttpHeader.CONNECTION,HttpHeaderValue.CLOSE);

            ByteBuffer buffer=null;
            if (content!=null)
            {
                buffer=BufferUtil.toBuffer(content,StringUtil.__UTF8_CHARSET);
                _response.setContentLength(buffer.remaining());
            }

            HttpGenerator.ResponseInfo info = _handler.commit();
            commitResponse(info,buffer);

            return true;
        }
        catch(Exception e)
        {
            LOG.debug("failed to sendError {} {}",status, reason, e);
        }
        finally
        {
            if (_state.isIdle())
                _state.complete();
            _request.getHttpInput().shutdownInput();
        }
        return false;
    }

    /* ------------------------------------------------------------ */
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
    @Override
    public String toString()
    {
        return String.format("%s@%x{r=%d,a=%s}",
                getClass().getSimpleName(),
                hashCode(),
                _requests,
                _state.getState());
    }

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    private class ChannelEventHandler implements EventHandler
    {
        @Override
        public boolean startRequest(HttpMethod httpMethod,String method, String uri, HttpVersion version)
        {
            _host = false;
            _expect = false;
            _expect100Continue=false;
            _expect102Processing=false;

            if(_request.getTimeStamp()==0)
                _request.setTimeStamp(System.currentTimeMillis());
            _request.setMethod(httpMethod,method);

            if (httpMethod==HttpMethod.CONNECT)
                _uri.parseConnect(uri);
            else
                _uri.parse(uri);
            _request.setUri(_uri);
            _request.setPathInfo(_uri.getDecodedPath());
            _version=version==null?HttpVersion.HTTP_0_9:version;
            _request.setHttpVersion(_version);

            return false;
        }

        @Override
        public boolean parsedHeader(HttpHeader header, String name, String value)
        {
            if (value==null)
                value="";
            if (header!=null)
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
                                String[] values = value.split(",");
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
                        String charset=(mime==null||mime.getCharset()==null)?MimeTypes.getCharsetFromContentType(value):mime.getCharset().toString();
                        if (charset!=null)
                            _request.setCharacterEncodingUnchecked(charset);
                        break;
                }
            }
            if (name!=null)
                _request.getHttpFields().add(name, value);
            return false;
        }

        @Override
        public boolean headerComplete(boolean hasBody,boolean persistent)
        {
            _requests++;
            switch (_version)
            {
                case HTTP_0_9:
                    break;
                case HTTP_1_0:
                    if (persistent)
                        _response.getHttpFields().add(HttpHeader.CONNECTION,HttpHeaderValue.KEEP_ALIVE);

                    if (_server.getSendDateHeader())
                        _response.getHttpFields().putDateField(HttpHeader.DATE.toString(),_request.getTimeStamp());
                    break;

                case HTTP_1_1:

                    if (!persistent)
                        _response.getHttpFields().add(HttpHeader.CONNECTION,HttpHeaderValue.CLOSE);

                    if (_server.getSendDateHeader())
                        _response.getHttpFields().putDateField(HttpHeader.DATE.toString(),_request.getTimeStamp());

                    if (!_host)
                    {
                        commitError(HttpStatus.BAD_REQUEST_400,"No Host Header",null);
                        return true;
                    }

                    if (_expect)
                    {
                        commitError(HttpStatus.EXPECTATION_FAILED_417,null,null);
                        return true;
                    }

                    break;
                default:
            }

            // Either handle now or wait for first content/message complete
            if (_expect100Continue)
                return true;

            return false;
        }

        @Override
        public boolean content(ByteBuffer ref)
        {
            if (LOG.isDebugEnabled())
            {
                LOG.debug("{} content {}",this,BufferUtil.toDetailString(ref));
            }
            _request.getHttpInput().content(ref);
            return true;
        }

        @Override
        public boolean messageComplete(long contentLength)
        {
            _request.getHttpInput().shutdownInput();
            return true;
        }

        @Override
        public boolean earlyEOF()
        {
            _request.getHttpInput().shutdownInput();
            return false;
        }

        @Override
        public void badMessage(int status, String reason)
        {
            if (status<400||status>599)
                status=HttpStatus.BAD_REQUEST_400;
            commitError(status,null,null);
        }

        @Override
        public ResponseInfo commit()
        {
            // If we are still expecting a 100, then this response must close
            if (_expect100Continue)
            {
                _expect100Continue=false;
                _response.getHttpFields().put(HttpHeader.CONNECTION,HttpHeaderValue.CLOSE);
            }
            return _response.commit();
        }

        @Override
        public String toString()
        {
            return "CEH:"+HttpChannel.this.getConnection().toString();
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
            write(s.getBytes(_response.getCharacterEncoding()));
        }


        /* ------------------------------------------------------------ */
        @Override
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
                    _response.getHttpFields().put(HttpHeader.CONTENT_TYPE, contentType);

                if (httpContent.getContentLength() > 0)
                    _response.getHttpFields().putLongField(HttpHeader.CONTENT_LENGTH, httpContent.getContentLength());

                String lm = httpContent.getLastModified();
                if (lm != null)
                    _response.getHttpFields().put(HttpHeader.LAST_MODIFIED, lm);
                else if (httpContent.getResource()!=null)
                {
                    long lml=httpContent.getResource().lastModified();
                    if (lml!=-1)
                        _response.getHttpFields().putDateField(HttpHeader.LAST_MODIFIED, lml);
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
                _response.getHttpFields().putDateField(HttpHeader.LAST_MODIFIED, resource.lastModified());
                content=resource.getInputStream();
            }

            // Process content.
            if (content instanceof ByteBuffer)
            {
                commitResponse(_handler.commit(),(ByteBuffer)content);
            }
            else if (content instanceof InputStream)
            {
                throw new IllegalArgumentException("not implemented!");
            }
            else
                throw new IllegalArgumentException("unknown content type?");
        }
    }

    public abstract Connector getConnector();

    public abstract HttpConfiguration getHttpConfiguration();

    protected abstract int write(ByteBuffer content) throws IOException;

    /* Called by the channel or application to commit a specific response info */
    protected abstract void commitResponse(ResponseInfo info, ByteBuffer content) throws IOException;

    protected abstract int getContentBufferSize();

    protected abstract void increaseContentBufferSize(int size);

    protected abstract void resetBuffer();

    protected abstract void flushResponse() throws IOException;

    protected abstract void completeResponse() throws IOException;

    protected abstract void completed();

    protected abstract void execute(Runnable task);

    // TODO use constructor injection ?
    public abstract ScheduledExecutorService getScheduler();

    /* ------------------------------------------------------------ */
    public interface EventHandler extends HttpParser.RequestHandler
    {
        ResponseInfo commit();
    }
}
