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
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.servlet.DispatcherType;
import javax.servlet.RequestDispatcher;

import org.eclipse.jetty.continuation.ContinuationThrowable;
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
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.EofException;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ErrorHandler;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class HttpChannel implements HttpParser.RequestHandler
{
    private static final Logger LOG = Log.getLogger(HttpChannel.class);
    private static final ThreadLocal<HttpChannel> __currentChannel = new ThreadLocal<>();

    public static HttpChannel getCurrentHttpChannel()
    {
        return __currentChannel.get();
    }

    protected static void setCurrentHttpChannel(HttpChannel channel)
    {
        __currentChannel.set(channel);
    }

    private final AtomicBoolean _committed = new AtomicBoolean();
    private final AtomicInteger _requests = new AtomicInteger();
    private final Connector _connector;
    private final HttpConfiguration _configuration;
    private final EndPoint _endPoint;
    private final HttpTransport _transport;
    private final HttpURI _uri;
    private final HttpChannelState _state;
    private final Request _request;
    private final Response _response;
    private HttpVersion _version = HttpVersion.HTTP_1_1;
    private boolean _expect = false;
    private boolean _expect100Continue = false;
    private boolean _expect102Processing = false;
    private boolean _host = false;

    public HttpChannel(Connector connector, HttpConfiguration configuration, EndPoint endPoint, HttpTransport transport)
    {
        _connector = connector;
        _configuration = configuration;
        _endPoint = endPoint;
        _transport = transport;

        _uri = new HttpURI(URIUtil.__CHARSET);
        _state = new HttpChannelState(this);
        _request = new Request(this, new HttpInput());
        _response = new Response(this, new HttpOutput(this));
    }

    public HttpChannelState getState()
    {
        return _state;
    }

    /**
     * @return the number of requests handled by this connection
     */
    public int getRequests()
    {
        return _requests.get();
    }

    public Connector getConnector()
    {
        return _connector;
    }

    public HttpConfiguration getHttpConfiguration()
    {
        return _configuration;
    }

    public Server getServer()
    {
        return _connector.getServer();
    }

    public Request getRequest()
    {
        return _request;
    }

    public Response getResponse()
    {
        return _response;
    }

    public EndPoint getEndPoint()
    {
        return _endPoint;
    }

    public InetSocketAddress getLocalAddress()
    {
        return _endPoint.getLocalAddress();
    }

    public InetSocketAddress getRemoteAddress()
    {
        return _endPoint.getRemoteAddress();
    }

    /**
     * If the associated response has the Expect header set to 100 Continue,
     * then accessing the input stream indicates that the handler/servlet
     * is ready for the request body and thus a 100 Continue response is sent.
     *
     * @throws IOException if the InputStream cannot be created
     */
    public void continue100(int available) throws IOException
    {
        // If the client is expecting 100 CONTINUE, then send it now.
        // TODO: consider using an AtomicBoolean ?
        if (isExpecting100Continue())
        {
            _expect100Continue = false;

            // is content missing?
            if (available == 0)
            {
                if (_response.isCommitted())
                    throw new IOException("Committed before 100 Continues");

                // TODO: break this dependency with HttpGenerator
                boolean committed = commitResponse(HttpGenerator.CONTINUE_100_INFO, null, false);
                if (!committed)
                    throw new IOException("Concurrent commit while trying to send 100-Continue"); // TODO: better message
            }
        }
    }

    public void reset()
    {
        _expect = false;
        _expect100Continue = false;
        _expect102Processing = false;
        _request.recycle();
        _response.recycle();
        _uri.clear();
    }

    protected boolean handle()
    {
        LOG.debug("{} handle enter", this);

        setCurrentHttpChannel(this);

        String threadName = null;
        if (LOG.isDebugEnabled())
        {
            threadName = Thread.currentThread().getName();
            Thread.currentThread().setName(threadName + " - " + _uri);
        }

        try
        {
            // Loop here to handle async request redispatches.
            // The loop is controlled by the call to async.unhandle in the
            // finally block below.  Unhandle will return false only if an async dispatch has
            // already happened when unhandle is called.
            boolean handling = _state.handling();

            while (handling && getServer().isRunning())
            {
                try
                {
                    _request.setHandled(false); // TODO: is this right here ?
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
                        // TODO: should be call customize() as above ?
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
                catch (Throwable e) // TODO: consider catching only Exception
                {
                    LOG.warn(String.valueOf(_uri), e);
                    _state.error(e);
                    _request.setHandled(true);
                    handleError(e);
                }
                finally
                {
                    handling = !_state.unhandle();
                }
            }

            return complete();
        }
        finally
        {
            if (threadName != null && LOG.isDebugEnabled())
                Thread.currentThread().setName(threadName);

            setCurrentHttpChannel(null);

            LOG.debug("{} handle exit", this);
        }
    }

    protected boolean complete()
    {
        LOG.debug("{} complete", this);

        if (!_state.isCompleting())
            return false;

        _state.completed();
        if (isExpecting100Continue())
        {
            LOG.debug("100-Continue response not sent");
            // We didn't send 100 continues, but the latest interpretation
            // of the spec (see httpbis) is that the client will either
            // send the body anyway, or close.  So we no longer need to
            // do anything special here other than make the connection not persistent
            _expect100Continue = false;
            if (!isCommitted())
                _response.addHeader(HttpHeader.CONNECTION.toString(), HttpHeaderValue.CLOSE.toString());
            else
                LOG.warn("Cannot send 'Connection: close' for 100-Continue, response is already committed");
        }

        if (!_response.isCommitted() && !_request.isHandled())
            _response.sendError(Response.SC_NOT_FOUND, null, null);

        _request.setHandled(true);
        _request.getHttpInput().consumeAll();
        try
        {
            _response.getHttpOutput().close();
        }
        catch (IOException x)
        {
            x.printStackTrace(); // TODO
        }

        return true;
    }

    // TODO: remove this method
    protected void completed()
    {
        /*
                // This method is called by handle() when it knows that its handling of the request/response cycle
                // is complete.
                // This may happen in the original thread dispatched to the connection that has called handle(),
                // or it may be from a thread dispatched to call handle() as the result of a resumed suspended request.

                LOG.debug("{} complete", this);


                // Handle connection upgrades
                if (_response.getStatus() == HttpStatus.SWITCHING_PROTOCOLS_101)
                {
                    Connection connection = (Connection)getRequest().getAttribute(HttpConnection.UPGRADE_CONNECTION_ATTRIBUTE);
                    if (connection != null)
                    {
                        LOG.debug("Upgrade from {} to {}", this, connection);
                        getEndPoint().setConnection(connection);
        //                HttpConnection.this.reset(); // TODO: this should be done by the connection privately when handle returns
                        return;
                    }
                }


                // Reset everything for the next cycle.
        //        HttpConnection.this.reset(); // TODO: this should be done by the connection privately when handle returns

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
        */
    }

    /**
     * <p>Sends an error 500, performing a special logic to detect whether the request is suspended,
     * to avoid concurrent writes from the application.</p>
     * <p>It may happen that the application suspends, and then throws an exception, while an application
     * spawned thread writes the response content; in such case, we attempt to commit the error directly
     * bypassing the {@link ErrorHandler} mechanisms and the response OutputStream.</p>
     *
     * @param x the Throwable that caused the problem
     */
    private void handleError(Throwable x)
    {
        if (_state.isSuspended())
        {
            try
            {
                HttpFields fields = new HttpFields();
                ResponseInfo info = new ResponseInfo(_request.getHttpVersion(), fields, 0, Response.SC_INTERNAL_SERVER_ERROR, null, _request.isHead());
                boolean committed = commitResponse(info, null, true);
                if (!committed)
                    LOG.warn("Could not send response error 500, response is already committed");
            }
            catch (IOException e)
            {
                // We tried our best, just log
                LOG.debug("Could not commit response error 500", e);
            }
        }
        else
        {
            _response.sendError(500, null, x.getMessage());
        }
    }

    protected void sendError(ResponseInfo info, String extraContent)
    {
        int status = info.getStatus();
        try
        {
            String reason = info.getReason();
            if (reason == null)
                reason = HttpStatus.getMessage(status);

            // If we are allowed to have a body
            if (status != Response.SC_NO_CONTENT &&
                    status != Response.SC_NOT_MODIFIED &&
                    status != Response.SC_PARTIAL_CONTENT &&
                    status >= Response.SC_OK)
            {
                ErrorHandler errorHandler = null;
                ContextHandler.Context context = _request.getContext();
                if (context != null)
                    errorHandler = context.getContextHandler().getErrorHandler();
                if (errorHandler == null)
                    errorHandler = getServer().getBean(ErrorHandler.class);
                if (errorHandler != null)
                {
                    _request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, new Integer(status));
                    _request.setAttribute(RequestDispatcher.ERROR_MESSAGE, reason);
                    _request.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, _request.getRequestURI());
                    _request.setAttribute(RequestDispatcher.ERROR_SERVLET_NAME, _request.getServletName());
                    errorHandler.handle(null, _request, _request, _response);
                }
                else
                {
                    HttpFields fields = info.getHttpFields();
                    fields.put(HttpHeader.CACHE_CONTROL, "must-revalidate,no-cache,no-store");
                    fields.put(HttpHeader.CONTENT_TYPE, MimeTypes.Type.TEXT_HTML_8859_1.toString());

                    reason = escape(reason);
                    String uri = escape(_request.getRequestURI());
                    extraContent = escape(extraContent);

                    StringBuilder writer = new StringBuilder(2048);
                    writer.append("<html>\n");
                    writer.append("<head>\n");
                    writer.append("<meta http-equiv=\"Content-Type\" content=\"text/html;charset=ISO-8859-1\"/>\n");
                    writer.append("<title>Error ").append(Integer.toString(status)).append(' ').append(reason).append("</title>\n");
                    writer.append("</head>\n");
                    writer.append("<body>\n");
                    writer.append("<h2>HTTP ERROR: ").append(Integer.toString(status)).append("</h2>\n");
                    writer.append("<p>Problem accessing ").append(uri).append(". Reason:\n");
                    writer.append("<pre>").append(reason).append("</pre></p>");
                    if (extraContent != null)
                        writer.append("<p>").append(extraContent).append("</p>");
                    writer.append("<hr /><i><small>Powered by Jetty://</small></i>\n");
                    writer.append("</body>\n");
                    writer.append("</html>");
                    byte[] bytes = writer.toString().getBytes(StringUtil.__ISO_8859_1);
                    fields.put(HttpHeader.CONTENT_LENGTH, String.valueOf(bytes.length));
                    _response.getOutputStream().write(bytes);
                }
            }
            else if (status != Response.SC_PARTIAL_CONTENT)
            {
                // TODO: not sure why we need to modify the request when writing an error ?
                // TODO: or modify the response if the error code cannot have a body ?
                //            _channel.getRequest().getHttpFields().remove(HttpHeader.CONTENT_TYPE);
                //            _channel.getRequest().getHttpFields().remove(HttpHeader.CONTENT_LENGTH);
                //            _characterEncoding = null;
                //            _mimeType = null;
            }

            complete();

            // TODO: is this needed ?
            if (_state.isIdle())
                _state.complete();
            _request.getHttpInput().shutdownInput();
        }
        catch (IOException x)
        {
            // We failed to write the error, bail out
            LOG.debug("Could not write error response " + status, x);
        }
    }

    private String escape(String reason)
    {
        if (reason != null)
        {
            reason = reason.replaceAll("&", "&amp;");
            reason = reason.replaceAll("<", "&lt;");
            reason = reason.replaceAll(">", "&gt;");
        }
        return reason;
    }

    public boolean isExpecting100Continue()
    {
        return _expect100Continue;
    }

    public boolean isExpecting102Processing()
    {
        return _expect102Processing;
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x{r=%s,a=%s}",
                getClass().getSimpleName(),
                hashCode(),
                _requests,
                _state.getState());
    }

    @Override
    public boolean startRequest(HttpMethod httpMethod, String method, String uri, HttpVersion version)
    {
        _host = false;
        _expect = false;
        _expect100Continue = false;
        _expect102Processing = false;

        if (_request.getTimeStamp() == 0)
            _request.setTimeStamp(System.currentTimeMillis());
        _request.setMethod(httpMethod, method);

        if (httpMethod == HttpMethod.CONNECT)
            _uri.parseConnect(uri);
        else
            _uri.parse(uri);
        _request.setUri(_uri);

        String path;
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
        String info = URIUtil.canonicalPath(path);

        if (info == null)
            info = "/";
        _request.setPathInfo(info);
        _version = version == null ? HttpVersion.HTTP_0_9 : version;
        _request.setHttpVersion(_version);

        return false;
    }

    @Override
    public boolean parsedHeader(HttpHeader header, String name, String value)
    {
        if (value == null)
            value = "";
        if (header != null)
        {
            switch (header)
            {
                case HOST:
                    // TODO check if host matched a host in the URI.
                    _host = true;
                    break;

                case EXPECT:
                    HttpHeaderValue expect = HttpHeaderValue.CACHE.get(value);
                    switch (expect == null ? HttpHeaderValue.UNKNOWN : expect)
                    {
                        case CONTINUE:
                            _expect100Continue = true;
                            break;

                        case PROCESSING:
                            _expect102Processing = true;
                            break;

                        default:
                            String[] values = value.split(",");
                            for (int i = 0; values != null && i < values.length; i++)
                            {
                                expect = HttpHeaderValue.CACHE.get(values[i].trim());
                                if (expect == null)
                                    _expect = true;
                                else
                                {
                                    switch (expect)
                                    {
                                        case CONTINUE:
                                            _expect100Continue = true;
                                            break;
                                        case PROCESSING:
                                            _expect102Processing = true;
                                            break;
                                        default:
                                            _expect = true;
                                    }
                                }
                            }
                    }
                    break;

                case CONTENT_TYPE:
                    MimeTypes.Type mime = MimeTypes.CACHE.get(value);
                    String charset = (mime == null || mime.getCharset() == null) ? MimeTypes.getCharsetFromContentType(value) : mime.getCharset().toString();
                    if (charset != null)
                        _request.setCharacterEncodingUnchecked(charset);
                    break;
            }
        }
        if (name != null)
            _request.getHttpFields().add(name, value);
        return false;
    }

    @Override
    public boolean headerComplete()
    {
        _requests.incrementAndGet();
        boolean persistent;
        switch (_version)
        {
            case HTTP_0_9:
                persistent = false;
                break;
            case HTTP_1_0:
                persistent = _request.getHttpFields().contains(HttpHeader.CONNECTION, HttpHeaderValue.KEEP_ALIVE.asString());
                if (persistent)
                    _response.getHttpFields().add(HttpHeader.CONNECTION, HttpHeaderValue.KEEP_ALIVE);

                if (getServer().getSendDateHeader())
                    _response.getHttpFields().putDateField(HttpHeader.DATE.toString(), _request.getTimeStamp());
                break;

            case HTTP_1_1:
                persistent = !_request.getHttpFields().contains(HttpHeader.CONNECTION, HttpHeaderValue.CLOSE.asString());

                if (!persistent)
                    _response.getHttpFields().add(HttpHeader.CONNECTION, HttpHeaderValue.CLOSE);

                if (getServer().getSendDateHeader())
                    _response.getHttpFields().putDateField(HttpHeader.DATE.toString(), _request.getTimeStamp());

                if (!_host)
                {
                    _response.sendError(Response.SC_BAD_REQUEST, "No Host Header", null);
                    return true;
                }

                if (_expect)
                {
                    _response.sendError(Response.SC_EXPECTATION_FAILED, null, null);
                    return true;
                }

                break;
            default:
                throw new IllegalStateException();
        }

        _request.setPersistent(persistent);

        // Either handle now or wait for first content/message complete
        return _expect100Continue;
    }

    @Override
    public boolean content(ByteBuffer ref)
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("{} content {}", this, BufferUtil.toDetailString(ref));
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
        if (status < 400 || status > 599)
            status = HttpStatus.BAD_REQUEST_400;
        _response.sendError(status, null, null);
    }

    // TODO: port the logic present in this method
    /*
        @Override
        public ResponseInfo commit()
        {
            // If we are still expecting a 100, then this response must close
            if (_expect100Continue)
            {
                _expect100Continue = false;
                _response.getHttpFields().put(HttpHeader.CONNECTION, HttpHeaderValue.CLOSE);
            }
            return _response.commit();
        }
    */

    protected boolean commitResponse(ResponseInfo info, ByteBuffer content, boolean complete) throws IOException
    {
        boolean committed = _committed.compareAndSet(false, true);
        if (committed)
            _transport.commit(info, content, complete);
        return committed;
    }

    protected boolean isCommitted()
    {
        return _committed.get();
    }

    /**
     * <p>Requests to write (in a blocking way) the given response content buffer,
     * committing the response if needed.</p>
     *
     * @param content  the content buffer to write
     * @param complete whether the content is complete for the response
     * @throws IOException if the write fails
     */
    protected void write(ByteBuffer content, boolean complete) throws IOException
    {
        if (isCommitted())
        {
            _transport.write(content, complete);
        }
        else
        {
            ResponseInfo info = _response.newResponseInfo();
            boolean committed = commitResponse(info, content, complete);
            if (!committed)
                throw new IOException("Concurrent commit"); // TODO: better message
        }
    }

    protected void execute(Runnable task)
    {
        _connector.getExecutor().execute(task);
    }

    // TODO: remove
    public ScheduledExecutorService getScheduler()
    {
        return _connector.getScheduler();
    }
}
