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
import javax.servlet.DispatcherType;
import javax.servlet.ServletException;

import org.eclipse.jetty.continuation.ContinuationThrowable;
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
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class HttpChannel
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

    private final AtomicBoolean _committed = new AtomicBoolean();
    private final ChannelEventHandler _handler = new ChannelEventHandler();
    private final Connector _connector;
    private final HttpConfiguration _configuration;
    private final EndPoint _endPoint;
    private final HttpTransport _transport;
    private final HttpURI _uri;
    private final HttpChannelState _state;
    private final Request _request;
    private final Response _response;
    private int _requests;
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

    public EventHandler getEventHandler()
    {
        return _handler;
    }

    public boolean isIdle()
    {
        return _state.isIdle();
    }

    /**
     * @return the number of requests handled by this connection
     */
    public int getRequests()
    {
        return _requests;
    }

    public Server getServer()
    {
        return _connector.getServer();
    }

    /**
     * @return Returns the request.
     */
    public Request getRequest()
    {
        return _request;
    }

    /**
     * @return Returns the response.
     */
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
                boolean committed = commit(HttpGenerator.CONTINUE_100_INFO, null, false);
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

    protected void handle()
    {
        LOG.debug("{} process", this);

        String threadName = null;
        if (LOG.isDebugEnabled())
        {
            threadName = Thread.currentThread().getName();
            Thread.currentThread().setName(threadName + " - " + _uri);
        }

        setCurrentHttpChannel(this);
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
                    LOG.warn(String.valueOf(_uri), e);
                    _state.error(e);
                    _request.setHandled(true);
                    commitError(500, null, e.toString());
                }
                catch (Throwable e)
                {
                    LOG.warn(String.valueOf(_uri), e);
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
            if (threadName != null)
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
                            _response.addHeader(HttpHeader.CONNECTION, HttpHeaderValue.CLOSE.toString());
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
                catch (EofException e)
                {
                    LOG.debug(e);
                }
                catch (Exception e)
                {
                    LOG.warn(e);
                }
                finally
                {
                    _request.setHandled(true);
                    completed();
                }
            }

            LOG.debug("{} !process", this);
        }
    }

    protected void completed()
    {
        // This is called by HttpChannel#handle when it knows that it's handling of the request/response cycle
        // is complete.  This may be in the original thread dispatched to the connection that has called process from
        // the connection#onFillable method, or it may be from a thread dispatched to call process as the result
        // of a resumed suspended request.
        // At this point the HttpChannel will have completed the generation of any response (although it might remain to
        // be asynchronously flushed TBD), but it may not have consumed the entire
/*
        LOG.debug("{} completed");

        // Handle connection upgrades
        if (getResponse().getStatus()==HttpStatus.SWITCHING_PROTOCOLS_101)
        {
            Connection connection=(Connection)getRequest().getAttribute(UPGRADE_CONNECTION_ATTR);
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
*/
    }

    protected boolean commitError(final int status, final String reason, String content)
    {
        return true; // TODO
/*
        LOG.debug("{} sendError {} {}", this, status, reason);

        if (_response.isCommitted())
            return false;

        try
        {
            _response.setStatus(status, reason);
            _response.getHttpFields().add(HttpHeader.CONNECTION, HttpHeaderValue.CLOSE);

            ByteBuffer buffer = null;
            if (content != null)
            {
                buffer = BufferUtil.toBuffer(content, StringUtil.__UTF8_CHARSET);
                _response.setContentLength(buffer.remaining());
            }

            HttpGenerator.ResponseInfo info = _handler.commit();
            try
            {
                write(info, buffer).get();
            }
            catch (final InterruptedException e)
            {
                throw new InterruptedIOException()
                {{
                        this.initCause(e);
                    }};
            }
            catch (final ExecutionException e)
            {
                throw new IOException()
                {{
                        this.initCause(e);
                    }};
            }

            return true;
        }
        catch (Exception e)
        {
            e.printStackTrace();
            LOG.debug("failed to sendError {} {}", status, reason, e);
        }
        finally
        {
            if (_state.isIdle())
                _state.complete();
            _request.getHttpInput().shutdownInput();
        }
        return false;
*/
    }

    public boolean isSuspended()
    {
        return _request.getAsyncContinuation().isSuspended();
    }

    public void onClose()
    {
        LOG.debug("closed {}", this);
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
        return String.format("%s@%x{r=%d,a=%s}",
                getClass().getSimpleName(),
                hashCode(),
                _requests,
                _state.getState());
    }

    private class ChannelEventHandler implements EventHandler
    {
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
            _requests++;
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
                        commitError(HttpStatus.BAD_REQUEST_400, "No Host Header", null);
                        return true;
                    }

                    if (_expect)
                    {
                        commitError(HttpStatus.EXPECTATION_FAILED_417, null, null);
                        return true;
                    }

                    break;
                default:
                    throw new IllegalStateException();
            }

            _request.setPersistent(persistent);

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
            commitError(status, null, null);
        }

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

        @Override
        public String toString()
        {
            return "CEH:" + HttpChannel.this.getEndPoint().toString();
        }
    }

    protected boolean commit(ResponseInfo info, ByteBuffer content, boolean complete) throws IOException
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
            // TODO: improve this responseInfo creation
            ResponseInfo info = new ResponseInfo(null, _response.getHttpFields(), _response.getLongContentLength(), _response.getStatus(), _response.getReason(), false);
            boolean committed = commit(info, content, complete);
            if (!committed)
                throw new IOException("Concurrent commit"); // TODO: better message
        }
    }

    public Connector getConnector()
    {
        return _connector;
    }

    public HttpConfiguration getHttpConfiguration()
    {
        return _configuration;
    }

//    protected abstract void flush(ByteBuffer content, boolean last) throws IOException;

//    protected abstract FutureCallback<Void> write(ResponseInfo info, ByteBuffer content) throws IOException;

//    protected abstract void completed();

    // TODO: remove
    protected void execute(Runnable task)
    {
        _connector.getExecutor().execute(task);
    }

    // TODO: remove
    public ScheduledExecutorService getScheduler()
    {
        return _connector.getScheduler();
    }

    public interface EventHandler extends HttpParser.RequestHandler
    {
        ResponseInfo commit();
    }
}
