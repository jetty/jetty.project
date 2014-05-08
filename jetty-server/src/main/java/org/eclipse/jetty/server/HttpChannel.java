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
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.servlet.DispatcherType;
import javax.servlet.RequestDispatcher;
import javax.servlet.http.HttpServletRequest;

import org.eclipse.jetty.http.HttpField;
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
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.ChannelEndPoint;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.EofException;
import org.eclipse.jetty.server.HttpChannelState.Action;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ErrorHandler;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.SharedBlockingCallback.Blocker;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.Scheduler;


/* ------------------------------------------------------------ */
/** HttpChannel.
 * Represents a single endpoint for HTTP semantic processing.
 * The HttpChannel is both a HttpParser.RequestHandler, where it passively receives events from
 * an incoming HTTP request, and a Runnable, where it actively takes control of the request/response
 * life cycle and calls the application (perhaps suspending and resuming with multiple calls to run).
 * The HttpChannel signals the switch from passive mode to active mode by returning true to one of the
 * HttpParser.RequestHandler callbacks.   The completion of the active phase is signalled by a call to
 * HttpTransport.completed().
 *
 */
public class HttpChannel<T> implements HttpParser.RequestHandler<T>, Runnable, HttpParser.ProxyHandler
{
    private static final Logger LOG = Log.getLogger(HttpChannel.class);
    private static final ThreadLocal<HttpChannel<?>> __currentChannel = new ThreadLocal<>();

    /* ------------------------------------------------------------ */
    /** Get the current channel that this thread is dispatched to.
     * @see Request#getAttribute(String) for a more general way to access the HttpChannel
     * @return the current HttpChannel or null
     */
    public static HttpChannel<?> getCurrentHttpChannel()
    {
        return __currentChannel.get();
    }

    protected static HttpChannel<?> setCurrentHttpChannel(HttpChannel<?> channel)
    {
        HttpChannel<?> last=__currentChannel.get();
        if (channel==null)
            __currentChannel.remove();
        else 
            __currentChannel.set(channel);
        return last;
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

    public HttpChannel(Connector connector, HttpConfiguration configuration, EndPoint endPoint, HttpTransport transport, HttpInput<T> input)
    {
        _connector = connector;
        _configuration = configuration;
        _endPoint = endPoint;
        _transport = transport;

        _uri = new HttpURI(URIUtil.__CHARSET);
        _state = new HttpChannelState(this);
        input.init(_state);
        _request = new Request(this, input);
        _response = new Response(this, new HttpOutput(this));
    }

    public HttpChannelState getState()
    {
        return _state;
    }

    public HttpVersion getHttpVersion()
    {
        return _version;
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

    public HttpTransport getHttpTransport()
    {
        return _transport;
    }
    
    public ByteBufferPool getByteBufferPool()
    {
        return _connector.getByteBufferPool();
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

    @Override
    public int getHeaderCacheSize()
    {
        return _configuration.getHeaderCacheSize();
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
                boolean committed = sendResponse(HttpGenerator.CONTINUE_100_INFO, null, false);
                if (!committed)
                    throw new IOException("Concurrent commit while trying to send 100-Continue");
            }
        }
    }

    public void reset()
    {
        _committed.set(false);
        _expect = false;
        _expect100Continue = false;
        _expect102Processing = false;
        _request.recycle();
        _response.recycle();
        _uri.clear();
    }

    @Override
    public void run()
    {
        handle();
    }

    /* ------------------------------------------------------------ */
    /**
     * @return True if the channel is ready to continue handling (ie it is not suspended)
     */
    public boolean handle()
    {
        LOG.debug("{} handle enter", this);

        final HttpChannel<?>last = setCurrentHttpChannel(this);

        String threadName = null;
        if (LOG.isDebugEnabled())
        {
            threadName = Thread.currentThread().getName();
            Thread.currentThread().setName(threadName + " - " + _uri);
        }

        HttpChannelState.Action action = _state.handling();
        try
        {
            // Loop here to handle async request redispatches.
            // The loop is controlled by the call to async.unhandle in the
            // finally block below.  Unhandle will return false only if an async dispatch has
            // already happened when unhandle is called.
            loop: while (action.ordinal()<HttpChannelState.Action.WAIT.ordinal() && getServer().isRunning())
            {
                boolean error=false;
                try
                {
                    LOG.debug("{} action {}",this,action);

                    switch(action)
                    {
                        case REQUEST_DISPATCH:
                            _request.setHandled(false);
                            _response.getHttpOutput().reopen();
                            _request.setDispatcherType(DispatcherType.REQUEST);

                            for (HttpConfiguration.Customizer customizer : _configuration.getCustomizers())
                                customizer.customize(getConnector(),_configuration,_request);
                            getServer().handle(this);
                            break;

                        case ASYNC_DISPATCH:
                            _request.setHandled(false);
                            _response.getHttpOutput().reopen();
                            _request.setDispatcherType(DispatcherType.ASYNC);
                            getServer().handleAsync(this);
                            break;

                        case ASYNC_EXPIRED:
                            _request.setHandled(false);
                            _response.getHttpOutput().reopen();
                            _request.setDispatcherType(DispatcherType.ERROR);

                            Throwable ex=_state.getAsyncContextEvent().getThrowable();
                            String reason="Async Timeout";
                            if (ex!=null)
                            {
                                reason="Async Exception";
                                _request.setAttribute(RequestDispatcher.ERROR_EXCEPTION,ex);
                            }
                            _request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE,new Integer(500));
                            _request.setAttribute(RequestDispatcher.ERROR_MESSAGE,reason);
                            _request.setAttribute(RequestDispatcher.ERROR_REQUEST_URI,_request.getRequestURI());

                            _response.setStatusWithReason(500,reason);

                            
                            ErrorHandler eh = ErrorHandler.getErrorHandler(getServer(),_state.getContextHandler());                                
                            if (eh instanceof ErrorHandler.ErrorPageMapper)
                            {
                                String error_page=((ErrorHandler.ErrorPageMapper)eh).getErrorPage((HttpServletRequest)_state.getAsyncContextEvent().getSuppliedRequest());
                                if (error_page!=null)
                                    _state.getAsyncContextEvent().setDispatchPath(error_page);
                            }

                            getServer().handleAsync(this);
                            break;

                        case READ_CALLBACK:
                        {
                            ContextHandler handler=_state.getContextHandler();
                            if (handler!=null)
                                handler.handle(_request.getHttpInput());
                            else
                                _request.getHttpInput().run();
                            break;
                        }

                        case WRITE_CALLBACK:
                        {
                            ContextHandler handler=_state.getContextHandler();

                            if (handler!=null)
                                handler.handle(_response.getHttpOutput());
                            else
                                _response.getHttpOutput().run();
                            break;
                        }   

                        default:
                            break loop;

                    }
                }
                catch (Error e)
                {
                    if ("ContinuationThrowable".equals(e.getClass().getSimpleName()))
                        LOG.ignore(e);
                    else
                    {
                        error=true;
                        throw e;
                    }
                }
                catch (Exception e)
                {
                    error=true;
                    if (e instanceof EofException)
                        LOG.debug(e);
                    else
                        LOG.warn(String.valueOf(_uri), e);
                    _state.error(e);
                    _request.setHandled(true);
                    handleException(e);
                }
                finally
                {
                    if (error && _state.isAsyncStarted())
                        _state.errorComplete();
                    action = _state.unhandle();
                }
            }

        }
        finally
        {
            setCurrentHttpChannel(last);
            if (threadName != null && LOG.isDebugEnabled())
                Thread.currentThread().setName(threadName);
        }

        if (action==Action.COMPLETE)
        {
            try
            {
                _state.completed();

                if (!_response.isCommitted() && !_request.isHandled())
                    _response.sendError(404);
                else
                    // Complete generating the response
                    _response.closeOutput();
            }
            catch(EofException|ClosedChannelException e)
            {
                LOG.debug(e);
            }
            catch(Exception e)
            {
                LOG.warn("complete failed",e);
            }
            finally
            {
                _request.setHandled(true);
                _transport.completed();
            }
        }

        LOG.debug("{} handle exit, result {}", this, action);

        return action!=Action.WAIT;
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
    protected void handleException(Throwable x)
    {
        try
        {
            _request.setAttribute(RequestDispatcher.ERROR_EXCEPTION,x);
            _request.setAttribute(RequestDispatcher.ERROR_EXCEPTION_TYPE,x.getClass());
            if (_state.isSuspended())
            {
                HttpFields fields = new HttpFields();
                fields.add(HttpHeader.CONNECTION,HttpHeaderValue.CLOSE);
                ResponseInfo info = new ResponseInfo(_request.getHttpVersion(), fields, 0, HttpStatus.INTERNAL_SERVER_ERROR_500, null, _request.isHead());
                boolean committed = sendResponse(info, null, true);
                if (!committed)
                    LOG.warn("Could not send response error 500: "+x);
                _request.getAsyncContext().complete();
            }
            else if (isCommitted())
            {
                _transport.abort();
                if (!(x instanceof EofException))
                    LOG.warn("Could not send response error 500: "+x);
            }
            else
            {
                _response.setHeader(HttpHeader.CONNECTION.asString(),HttpHeaderValue.CLOSE.asString());
                _response.sendError(500, x.getMessage());
            }
        }
        catch (IOException e)
        {
            // We tried our best, just log
            LOG.debug("Could not commit response error 500", e);
        }
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
        return String.format("%s@%x{r=%s,a=%s,uri=%s}",
                getClass().getSimpleName(),
                hashCode(),
                _requests,
                _state.getState(),
                _state.getState()==HttpChannelState.State.IDLE?"-":_request.getRequestURI()
            );
    }

    @Override
    public void proxied(String protocol, String sAddr, String dAddr, int sPort, int dPort)
    {
        _request.setAttribute("PROXY", protocol);
        _request.setServerName(sAddr);
        _request.setServerPort(dPort);
        _request.setRemoteAddr(InetSocketAddress.createUnresolved(sAddr,sPort));
    }
    
    @Override
    public boolean startRequest(HttpMethod httpMethod, String method, ByteBuffer uri, HttpVersion version)
    {
        _expect = false;
        _expect100Continue = false;
        _expect102Processing = false;

        _request.setTimeStamp(System.currentTimeMillis());
        _request.setMethod(httpMethod, method);

        if (httpMethod == HttpMethod.CONNECT)
            _uri.parseConnect(uri.array(),uri.arrayOffset()+uri.position(),uri.remaining());
        else
            _uri.parse(uri.array(),uri.arrayOffset()+uri.position(),uri.remaining());
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
            path = _uri.getDecodedPath(StandardCharsets.ISO_8859_1);
        }
        
        String info = URIUtil.canonicalPath(path);

        if (info == null)
        {
            if( path==null && _uri.getScheme()!=null &&_uri.getHost()!=null)
            {
                info = "/";
                _request.setRequestURI("");
            }
            else
            {
                badMessage(400,null);
                return true;
            }
        }
        _request.setPathInfo(info);
        _version = version == null ? HttpVersion.HTTP_0_9 : version;
        _request.setHttpVersion(_version);

        return false;
    }

    @Override
    public boolean parsedHeader(HttpField field)
    {
        HttpHeader header=field.getHeader();
        String value=field.getValue();
        if (value == null)
            value = "";
        if (header != null)
        {
            switch (header)
            {
                case EXPECT:
                    if (_version.getVersion()>=HttpVersion.HTTP_1_1.getVersion())
                    {
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
                    }
                    break;

                case CONTENT_TYPE:
                    MimeTypes.Type mime = MimeTypes.CACHE.get(value);
                    String charset = (mime == null || mime.getCharset() == null) ? MimeTypes.getCharsetFromContentType(value) : mime.getCharset().toString();
                    if (charset != null)
                        _request.setCharacterEncodingUnchecked(charset);
                    break;
                default:
            }
        }

        if (field.getName()!=null)
            _request.getHttpFields().add(field);
        return false;
    }

    @Override
    public boolean parsedHostHeader(String host, int port)
    {
        if (_uri.getHost()==null)
        {
            _request.setServerName(host);
            _request.setServerPort(port);
        }
        return false;
    }

    @Override
    public boolean headerComplete()
    {
        _requests.incrementAndGet();
        switch (_version)
        {
            case HTTP_0_9:
                break;

            case HTTP_1_0:
                if (_configuration.getSendDateHeader())
                    _response.getHttpFields().put(_connector.getServer().getDateField());
                break;

            case HTTP_1_1:
                if (_configuration.getSendDateHeader())
                    _response.getHttpFields().put(_connector.getServer().getDateField());

                if (_expect)
                {
                    badMessage(HttpStatus.EXPECTATION_FAILED_417,null);
                    return true;
                }

                break;

            default:
                throw new IllegalStateException();
        }

        return true;
    }

    @Override
    public boolean content(T item)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("{} content {}", this, item);
        @SuppressWarnings("unchecked")
        HttpInput<T> input = (HttpInput<T>)_request.getHttpInput();
        input.content(item);

        return false;
    }

    @Override
    public boolean messageComplete()
    {
        LOG.debug("{} messageComplete", this);
        _request.getHttpInput().messageComplete();
        return true;
    }

    @Override
    public void earlyEOF()
    {
        _request.getHttpInput().earlyEOF();
    }

    @Override
    public void badMessage(int status, String reason)
    {
        if (status < 400 || status > 599)
            status = HttpStatus.BAD_REQUEST_400;

        try
        {
            if (_state.handling()==Action.REQUEST_DISPATCH)
            {
                ByteBuffer content=null;
                HttpFields fields=new HttpFields();

                ErrorHandler handler=getServer().getBean(ErrorHandler.class);
                if (handler!=null)
                    content=handler.badMessageError(status,reason,fields);

                sendResponse(new ResponseInfo(HttpVersion.HTTP_1_1,fields,0,status,reason,false),content ,true);
            }
        }
        catch (IOException e)
        {
            LOG.debug(e);
        }
        finally
        {
            if (_state.unhandle()==Action.COMPLETE)
                _state.completed();
            else 
                throw new IllegalStateException();
        }
    }

    protected boolean sendResponse(ResponseInfo info, ByteBuffer content, boolean complete, final Callback callback)
    {
        // TODO check that complete only set true once by changing _committed to AtomicRef<Enum>
        boolean committing = _committed.compareAndSet(false, true);
        if (committing)
        {
            // We need an info to commit
            if (info==null)
                info = _response.newResponseInfo();

            // wrap callback to process 100 or 500 responses
            final int status=info.getStatus();
            final Callback committed = (status<200&&status>=100)?new Commit100Callback(callback):new CommitCallback(callback);

            // committing write
            _transport.send(info, content, complete, committed);
        }
        else if (info==null)
        {
            // This is a normal write
            _transport.send(content, complete, callback);
        }
        else
        {
            callback.failed(new IllegalStateException("committed"));
        }
        return committing;
    }

    protected boolean sendResponse(ResponseInfo info, ByteBuffer content, boolean complete) throws IOException
    {
        try(Blocker blocker = _response.getHttpOutput().acquireWriteBlockingCallback())
        {
            boolean committing = sendResponse(info,content,complete,blocker);
            blocker.block();
            return committing;
        }
    }

    public boolean isCommitted()
    {
        return _committed.get();
    }

    /**
     * <p>Non-Blocking write, committing the response if needed.</p>
     *
     * @param content  the content buffer to write
     * @param complete whether the content is complete for the response
     * @param callback Callback when complete or failed
     */
    protected void write(ByteBuffer content, boolean complete, Callback callback)
    {
        sendResponse(null,content,complete,callback);
    }

    protected void execute(Runnable task)
    {
        _connector.getExecutor().execute(task);
    }

    public Scheduler getScheduler()
    {
        return _connector.getScheduler();
    }

    /* ------------------------------------------------------------ */
    /**
     * @return true if the HttpChannel can efficiently use direct buffer (typically this means it is not over SSL or a multiplexed protocol)
     */
    public boolean useDirectBuffers()
    {
        return getEndPoint() instanceof ChannelEndPoint;
    }

    /**
     * If a write or similar to this channel fails this method should be called. The standard implementation
     * of {@link #failed()} is a noop. But the different implementations of HttpChannel might want to take actions.
     */
    public void failed()
    {
    }

    private class CommitCallback implements Callback
    {
        private final Callback _callback;

        private CommitCallback(Callback callback)
        {
            _callback = callback;
        }

        @Override
        public void succeeded()
        {
            _callback.succeeded();
        }

        @Override
        public void failed(final Throwable x)
        {
            if (x instanceof EofException || x instanceof ClosedChannelException)
            {
                LOG.debug(x);
                _callback.failed(x);
                _response.getHttpOutput().closed();
            }
            else
            {
                LOG.warn("Commit failed",x);
                _transport.send(HttpGenerator.RESPONSE_500_INFO,null,true,new Callback()
                {
                    @Override
                    public void succeeded()
                    {
                        _callback.failed(x);
                        _response.getHttpOutput().closed();
                    }

                    @Override
                    public void failed(Throwable th)
                    {
                        LOG.ignore(th);
                        _callback.failed(x);
                        _response.getHttpOutput().closed();
                    }
                });
            }
        }
    }

    private class Commit100Callback extends CommitCallback
    {
        private Commit100Callback(Callback callback)
        {
            super(callback);
        }

        @Override
        public void succeeded()
        {
             _committed.set(false);
             super.succeeded();
        }

    }

}
