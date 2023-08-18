//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee10.servlet;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import jakarta.servlet.RequestDispatcher;
import org.eclipse.jetty.ee10.servlet.ServletChannelState.Action;
import org.eclipse.jetty.http.BadMessageException;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpHeaderValue;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.QuietException;
import org.eclipse.jetty.server.ConnectionMetaData;
import org.eclipse.jetty.server.CustomRequestLog;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.ResponseUtils;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ContextRequest;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.ExceptionUtil;
import org.eclipse.jetty.util.HostPort;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.URIUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.eclipse.jetty.util.thread.Invocable.InvocationType.NON_BLOCKING;

/**
 * The ServletChannel contains the state and behaviors associated with the Servlet API
 * lifecycle for a single request/response cycle. Specifically it uses
 * {@link ServletChannelState} to coordinate the states of dispatch state, input and
 * output according to the servlet specification.  The combined state so obtained
 * is reflected in the behaviour of the contained {@link HttpInput} implementation of
 * {@link jakarta.servlet.ServletInputStream}.
 * <p>
 * This class is reusable over multiple requests for the same {@link ServletContextHandler}
 * and is {@link #recycle() recycled} after each use before being
 * {@link #associate(ServletContextRequest) associated} with a new {@link ServletContextRequest}
 * and then {@link #associate(Request, Response, Callback) associated} with possibly wrapped
 * request, response and callback.
 * </p>
 * @see ServletChannelState
 * @see HttpInput
 */
public class ServletChannel
{
    private static final Logger LOG = LoggerFactory.getLogger(ServletChannel.class);

    private final ServletChannelState _state;
    private final ServletContextHandler.ServletScopedContext _context;
    private final ServletContextHandler.ServletContextApi _servletContextApi;
    private final ConnectionMetaData _connectionMetaData;
    private final AtomicLong _requests = new AtomicLong();
    private final HttpInput _httpInput;
    private final HttpOutput _httpOutput;
    private ServletContextRequest _servletContextRequest;
    private Request _request;
    private Response _response;
    private Callback _callback;
    private boolean _expects100Continue;

    public ServletChannel(ServletContextHandler servletContextHandler, Request request)
    {
        this(servletContextHandler, request.getConnectionMetaData());
    }

    public ServletChannel(ServletContextHandler servletContextHandler, ConnectionMetaData connectionMetaData)
    {
        _context = servletContextHandler.getContext();
        _servletContextApi = _context.getServletContext();
        _connectionMetaData = connectionMetaData;
        _state = new ServletChannelState(this);
        _httpInput = new HttpInput(this);
        _httpOutput = new HttpOutput(this);
    }

    public ConnectionMetaData getConnectionMetaData()
    {
        return _connectionMetaData;
    }

    public Callback getCallback()
    {
        return _callback;
    }

    /**
     * Associate this channel with a specific request.
     * This method is called by the {@link ServletContextHandler} when a core {@link Request} is accepted and associated with
     * a servlet mapping. The association remains until {@link #recycle()} is called.
     * @param servletContextRequest The servlet context request to associate
     * @see #recycle()
     */
    public void associate(ServletContextRequest servletContextRequest)
    {
        // We need to recycle here sometimes as requests that are handled before the
        // ServletHandler (e.g. by SecurityHandler) are not recycled.
        if (_servletContextRequest != null)
            recycle();
        _httpInput.reopen();
        _request = _servletContextRequest = servletContextRequest;
        _response = _servletContextRequest.getServletContextResponse();
        _expects100Continue = servletContextRequest.getHeaders().contains(HttpHeader.EXPECT, HttpHeaderValue.CONTINUE.asString());

        if (LOG.isDebugEnabled())
            LOG.debug("associate {} -> {} : {}",
                this,
                _servletContextRequest,
                _state);
    }

    /**
     * Associate this channel with possibly wrapped values for
     * {@link #getRequest()}, {@link #getResponse()} and {@link #getCallback()}.
     * This is called by the {@link ServletHandler} immediately before calling {@link #handle()} on the
     * initial dispatch.  This allows for handlers between the {@link ServletContextHandler} and the
     * {@link ServletHandler} to wrap the instances.
     * @param request The request, which may have been wrapped
     *                after #{@link ServletContextHandler#wrapRequest(Request, Response)}
     * @param response The response, which may have been wrapped
     *                 after #{@link ServletContextHandler#wrapResponse(ContextRequest, Response)}
     * @param callback The context, which may have been wrapped
     *                 after {@link ServletContextHandler#handle(Request, Response, Callback)}
     */
    public void associate(Request request, Response response, Callback callback)
    {
        if (_callback != null)
            throw new IllegalStateException();

        if (request != _request && Request.as(request, ServletContextRequest.class) != _servletContextRequest)
            throw new IllegalStateException();
        _request = request;
        _response = response;
        _callback = callback;

        if (LOG.isDebugEnabled())
            LOG.debug("associate {} -> {},{},{}",
                this,
                _request,
                _response,
                _callback);
    }

    public ServletContextHandler.ServletScopedContext getContext()
    {
        return _context;
    }

    public ServletContextHandler getServletContextHandler()
    {
        return _context.getContextHandler();
    }

    public ServletContextHandler.ServletContextApi getServletContextApi()
    {
        return _servletContextApi;
    }

    public HttpOutput getHttpOutput()
    {
        return _httpOutput;
    }

    public HttpInput getHttpInput()
    {
        return _httpInput;
    }

    public ServletContextHandler.ServletContextApi getServletContextContext()
    {
        return _servletContextApi;
    }

    public boolean isSendError()
    {
        return _state.isSendError();
    }

    /** Format the address or host returned from Request methods
     * @param addr The address or host
     * @return Default implementation returns {@link HostPort#normalizeHost(String)}
     */
    protected String formatAddrOrHost(String addr)
    {
        return HostPort.normalizeHost(addr);
    }

    public ServletChannelState getServletRequestState()
    {
        return _state;
    }

    public long getBytesWritten()
    {
        return Response.getContentBytesWritten(getServletContextResponse());
    }

    /**
     * Get the idle timeout.
     * <p>This is implemented as a call to {@link EndPoint#getIdleTimeout()}, but may be
     * overridden by channels that have timeouts different from their connections.
     *
     * @return the idle timeout (in milliseconds)
     */
    public long getIdleTimeout()
    {
        return _connectionMetaData.getConnection().getEndPoint().getIdleTimeout();
    }

    /**
     * Set the idle timeout.
     * <p>This is implemented as a call to {@link EndPoint#setIdleTimeout(long)}, but may be
     * overridden by channels that have timeouts different from their connections.
     *
     * @param timeoutMs the idle timeout in milliseconds
     */
    public void setIdleTimeout(long timeoutMs)
    {
        _connectionMetaData.getConnection().getEndPoint().setIdleTimeout(timeoutMs);
    }

    public HttpConfiguration getHttpConfiguration()
    {
        return _connectionMetaData.getHttpConfiguration();
    }

    public Server getServer()
    {
        return _context.getContextHandler().getServer();
    }

    /**
     * @return The {@link ServletContextRequest} as wrapped by the {@link ServletContextHandler}.
     * @see #getRequest()
     */
    public ServletContextRequest getServletContextRequest()
    {
        return _servletContextRequest;
    }

    /**
     * @return The core {@link Request} associated with the request. This may differ from {@link #getServletContextRequest()}
     *         if the request was wrapped by another handler after the {@link ServletContextHandler} and passed
     *         to {@link ServletChannel#associate(Request, Response, Callback)}.
     * @see #getServletContextRequest()
     * @see #associate(Request, Response, Callback)
     */
    public Request getRequest()
    {
        return _request;
    }

    /**
     * @return The ServetContextResponse as wrapped by the {@link ServletContextHandler}.
     * @see #getResponse()
     */
    public ServletContextResponse getServletContextResponse()
    {
        ServletContextRequest request = _servletContextRequest;
        return request == null ? null : request.getServletContextResponse();
    }

    /**
     * @return The core {@link Response} associated with the API response.
     *         This may differ from {@link #getServletContextResponse()} if the response was wrapped by another handler
     *         after the {@link ServletContextHandler} and passed to {@link ServletChannel#associate(Request, Response, Callback)}.
     * @see #getServletContextResponse()
     * @see #associate(Request, Response, Callback)
     */
    public Response getResponse()
    {
        return _response;
    }

    public Connection getConnection()
    {
        return _connectionMetaData.getConnection();
    }

    public EndPoint getEndPoint()
    {
        return getConnection().getEndPoint();
    }

    /**
     * <p>Return the local name of the connected channel.</p>
     *
     * <p>
     * This is the host name after the connector is bound and the connection is accepted.
     * </p>
     * <p>
     * Value can be overridden by {@link HttpConfiguration#setLocalAddress(SocketAddress)}.
     * </p>
     * <p>
     * Note: some connectors are not based on IP networking, and default behavior here will
     * result in a null return.  Use {@link HttpConfiguration#setLocalAddress(SocketAddress)}
     * to set the value to an acceptable host name.
     * </p>
     *
     * @return the local name, or null
     */
    public String getLocalName()
    {
        HttpConfiguration httpConfiguration = getHttpConfiguration();
        if (httpConfiguration != null)
        {
            SocketAddress localAddress = httpConfiguration.getLocalAddress();
            if (localAddress instanceof InetSocketAddress)
                return ((InetSocketAddress)localAddress).getHostName();
        }

        InetSocketAddress local = getLocalAddress();
        if (local != null)
            return local.getHostString();

        return null;
    }

    /**
     * <p>Return the Local Port of the connected channel.</p>
     *
     * <p>
     * This is the port the connector is bound to and is accepting connections on.
     * </p>
     * <p>
     * Value can be overridden by {@link HttpConfiguration#setLocalAddress(SocketAddress)}.
     * </p>
     * <p>
     * Note: some connectors are not based on IP networking, and default behavior here will
     * result in a value of 0 returned.  Use {@link HttpConfiguration#setLocalAddress(SocketAddress)}
     * to set the value to an acceptable port.
     * </p>
     *
     * @return the local port, or 0 if unspecified
     */
    public int getLocalPort()
    {
        HttpConfiguration httpConfiguration = getHttpConfiguration();
        if (httpConfiguration != null)
        {
            SocketAddress localAddress = httpConfiguration.getLocalAddress();
            if (localAddress instanceof InetSocketAddress)
                return ((InetSocketAddress)localAddress).getPort();
        }

        InetSocketAddress local = getLocalAddress();
        return local == null ? 0 : local.getPort();
    }

    public InetSocketAddress getLocalAddress()
    {
        HttpConfiguration httpConfiguration = getHttpConfiguration();
        if (httpConfiguration != null)
        {
            SocketAddress localAddress = httpConfiguration.getLocalAddress();
            if (localAddress instanceof InetSocketAddress)
                return ((InetSocketAddress)localAddress);
        }

        SocketAddress local = getEndPoint().getLocalSocketAddress();
        if (local instanceof InetSocketAddress)
            return (InetSocketAddress)local;
        return null;
    }

    public InetSocketAddress getRemoteAddress()
    {
        SocketAddress remote = getEndPoint().getRemoteSocketAddress();
        if (remote instanceof InetSocketAddress)
            return (InetSocketAddress)remote;
        return null;
    }

    /**
     * @return return the HttpConfiguration server authority override
     */
    public HostPort getServerAuthority()
    {
        HttpConfiguration httpConfiguration = getHttpConfiguration();
        if (httpConfiguration != null)
            return httpConfiguration.getServerAuthority();

        return null;
    }

    /**
     * If the associated response has the Expect header set to 100 Continue,
     * then accessing the input stream indicates that the handler/servlet
     * is ready for the request body and thus a 100 Continue response is sent.
     *
     * @param available estimate of the number of bytes that are available
     * @throws IOException if the InputStream cannot be created
     */
    public void continue100(int available) throws IOException
    {
        if (isExpecting100Continue())
        {
            _expects100Continue = false;
            if (available == 0)
            {
                if (isCommitted())
                    throw new IOException("Committed before 100 Continue");
                try
                {
                    getServletContextResponse().writeInterim(HttpStatus.CONTINUE_100, HttpFields.EMPTY).get();
                }
                catch (Throwable x)
                {
                    throw IO.rethrow(x);
                }
            }
        }
    }

    /**
     * @see #associate(ServletContextRequest)
     */
    private void recycle()
    {
        _state.recycle();
        _httpInput.recycle();
        _httpOutput.recycle();
        _request = _servletContextRequest = null;
        _response = null;
        _callback = null;
        _expects100Continue = false;
    }

    /**
     * Handle the servlet request. This is called on the initial dispatch and then again on any asynchronous events.
     */
    public void handle()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("handle {} {} ", _servletContextRequest.getHttpURI(), this);

        Action action = _state.handling();

        // Loop here to handle async request redispatches.
        // The loop is controlled by the call to async.unhandle in the
        // finally block below.  Unhandle will return false only if an async dispatch has
        // already happened when unhandle is called.
        loop:
        while (!getServer().isStopped())
        {
            try
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("action {} {}", action, this);

                switch (action)
                {
                    case TERMINATED:
                        onCompleted();
                        break loop;

                    case WAIT:
                        // break loop without calling unhandle
                        break loop;

                    case DISPATCH:
                    {
                        reopen();
                        dispatch();
                        break;
                    }

                    case ASYNC_DISPATCH:
                    {
                        reopen();
                        dispatchAsync();
                        break;
                    }

                    case ASYNC_TIMEOUT:
                        _state.onTimeout();
                        break;

                    case SEND_ERROR:
                    {
                        Object errorException = _servletContextRequest.getAttribute((RequestDispatcher.ERROR_EXCEPTION));
                        Throwable cause = errorException instanceof Throwable throwable ? throwable : null;
                        try
                        {
                            // Get ready to send an error response
                            getServletContextResponse().resetContent();

                            // the following is needed as you cannot trust the response code and reason
                            // as those could have been modified after calling sendError
                            Integer code = (Integer)_servletContextRequest.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
                            if (code == null)
                                code = HttpStatus.INTERNAL_SERVER_ERROR_500;
                            getServletContextResponse().setStatus(code);

                            // The handling of the original dispatch failed, and we are now going to either generate
                            // and error response ourselves or dispatch for an error page.  If there is content left over
                            // from the failed dispatch, then we try to consume it here and if we fail we add a
                            // Connection:close.  This can't be deferred to COMPLETE as the response will be committed
                            // by then.
                            if (!_httpInput.consumeAvailable())
                                ResponseUtils.ensureNotPersistent(_servletContextRequest, _servletContextRequest.getServletContextResponse());

                            ContextHandler.ScopedContext context = (ContextHandler.ScopedContext)_servletContextRequest.getAttribute(ErrorHandler.ERROR_CONTEXT);
                            Request.Handler errorHandler = ErrorHandler.getErrorHandler(getServer(), context == null ? null : context.getContextHandler());

                            // If we can't have a body or have no ErrorHandler, then create a minimal error response.
                            if (HttpStatus.hasNoBody(getServletContextResponse().getStatus()) || errorHandler == null)
                            {
                                sendResponseAndComplete();
                            }
                            else
                            {
                                AtomicBoolean asyncCompletion = new AtomicBoolean(false);
                                Callback errorCallback = Callback.from(() ->
                                {
                                    if (!asyncCompletion.compareAndSet(false, true))
                                        _state.scheduleDispatch();
                                });

                                // We do not notify ServletRequestListener on this dispatch because it might not
                                // be dispatched to an error page, so we delegate this responsibility to the ErrorHandler.
                                reopen();
                                errorHandler.handle(getServletContextRequest(), getServletContextResponse(), errorCallback);

                                // If the callback has already been completed we should continue in handle loop.
                                // Otherwise, the callback will schedule a dispatch to handle().
                                if (asyncCompletion.compareAndSet(false, true))
                                    return;
                            }
                        }
                        catch (Throwable x)
                        {
                            if (cause == null)
                                cause = x;
                            else
                                ExceptionUtil.addSuppressedIfNotAssociated(cause, x);
                            if (LOG.isDebugEnabled())
                                LOG.debug("Could not perform ERROR dispatch, aborting", cause);
                            if (_state.isResponseCommitted())
                            {
                                abort(cause);
                            }
                            else
                            {
                                try
                                {
                                    getServletContextResponse().resetContent();
                                    sendResponseAndComplete();
                                }
                                catch (Throwable t)
                                {
                                    ExceptionUtil.addSuppressedIfNotAssociated(cause, t);
                                    abort(cause);
                                }
                            }
                        }
                        finally
                        {
                            // clean up the context that was set in Response.sendError
                            _servletContextRequest.removeAttribute(ErrorHandler.ERROR_CONTEXT);
                        }
                        break;
                    }

                    case ASYNC_ERROR:
                    {
                        throw _state.getAsyncContextEvent().getThrowable();
                    }

                    case READ_CALLBACK:
                    {
                        _context.run(() -> _servletContextRequest.getHttpInput().run());
                        break;
                    }

                    case WRITE_CALLBACK:
                    {
                        _context.run(() -> _servletContextRequest.getHttpOutput().run());
                        break;
                    }

                    case COMPLETE:
                    {
                        if (!getServletContextResponse().isCommitted())
                        {
                            // Indicate Connection:close if we can't consume all.
                            if (getServletContextResponse().getStatus() >= 200)
                                ResponseUtils.ensureConsumeAvailableOrNotPersistent(_servletContextRequest, _servletContextRequest.getServletContextResponse());
                        }

                        // RFC 7230, section 3.3.  We do this here so that a servlet error page can be sent.
                        if (!_servletContextRequest.isHead() && getServletContextResponse().getStatus() != HttpStatus.NOT_MODIFIED_304)
                        {
                            long written = getBytesWritten();
                            if (getServletContextResponse().isContentIncomplete(written) && sendErrorOrAbort("Insufficient content written %d < %d".formatted(written, getServletContextResponse().getContentLength())))
                                break;
                        }

                        // Set a close callback on the HttpOutput to make it an async callback
                        getServletContextResponse().completeOutput(Callback.from(NON_BLOCKING, () -> _state.completed(null), _state::completed));

                        break;
                    }

                    default:
                        throw new IllegalStateException(this.toString());
                }
            }
            catch (Throwable failure)
            {
                if ("org.eclipse.jetty.continuation.ContinuationThrowable".equals(failure.getClass().getName()))
                    LOG.trace("IGNORED", failure);
                else
                    handleException(failure);
            }

            action = _state.unhandle();
        }

        if (LOG.isDebugEnabled())
            LOG.debug("!handle {} {}", action, this);
    }

    private void reopen()
    {
        _servletContextRequest.getServletContextResponse().getHttpOutput().reopen();
        getHttpOutput().reopen();
    }

    /**
     * @param message the error message.
     * @return true if we have sent an error, false if we have aborted.
     */
    private boolean sendErrorOrAbort(String message)
    {
        try
        {
            if (isCommitted())
            {
                abort(new IOException(message));
                return false;
            }

            getServletContextResponse().getServletApiResponse().sendError(HttpStatus.INTERNAL_SERVER_ERROR_500, message);
            return true;
        }
        catch (Throwable x)
        {
            LOG.trace("IGNORED", x);
            abort(x);
        }
        return false;
    }

    /**
     * <p>Sends an error 500, performing a special logic to detect whether the request is suspended,
     * to avoid concurrent writes from the application.</p>
     * <p>It may happen that the application suspends, and then throws an exception, while an application
     * spawned thread writes the response content; in such case, we attempt to commit the error directly
     * bypassing the {@link ErrorHandler} mechanisms and the response OutputStream.</p>
     *
     * @param failure the Throwable that caused the problem
     */
    protected void handleException(Throwable failure)
    {
        // Unwrap wrapping Jetty and Servlet exceptions.
        Throwable quiet = unwrap(failure, QuietException.class);
        Throwable noStack = unwrap(failure, BadMessageException.class, IOException.class, TimeoutException.class);

        if (quiet != null || !getServer().isRunning())
        {
            if (LOG.isDebugEnabled())
                LOG.debug(_servletContextRequest.getServletApiRequest().getRequestURI(), failure);
        }
        else if (noStack != null)
        {
            // No stack trace unless there is debug turned on
            if (LOG.isDebugEnabled())
                LOG.warn("handleException {}", _servletContextRequest.getServletApiRequest().getRequestURI(), failure);
            else
                LOG.warn("handleException {} {}", _servletContextRequest.getServletApiRequest().getRequestURI(), noStack.toString());
        }
        else
        {
            ServletContextRequest request = _servletContextRequest;
            LOG.warn(request == null ? "unknown request" : request.getServletApiRequest().getRequestURI(), failure);
        }

        if (isCommitted())
        {
            abort(failure);
        }
        else
        {
            try
            {
                _state.onError(failure);
            }
            catch (IllegalStateException e)
            {
                abort(failure);
            }
        }
    }

    /**
     * Unwrap failure causes to find target class
     *
     * @param failure The throwable to have its causes unwrapped
     * @param targets Exception classes that we should not unwrap
     * @return A target throwable or null
     */
    protected Throwable unwrap(Throwable failure, Class<?>... targets)
    {
        while (failure != null)
        {
            for (Class<?> x : targets)
            {
                if (x.isInstance(failure))
                    return failure;
            }
            failure = failure.getCause();
        }
        return null;
    }

    public void sendResponseAndComplete()
    {
        try
        {
            _state.completing();
            getServletContextResponse().write(true, getServletContextResponse().getHttpOutput().getByteBuffer(), Callback.from(() -> _state.completed(null), _state::completed));
        }
        catch (Throwable x)
        {
            abort(x);
        }
    }

    public boolean isExpecting100Continue()
    {
        return _expects100Continue;
    }

    @Override
    public String toString()
    {
        if (_servletContextRequest == null)
        {
            return String.format("%s@%x{null}",
                getClass().getSimpleName(),
                hashCode());
        }

        long timeStamp = Request.getTimeStamp(_servletContextRequest);
        return String.format("%s@%x{s=%s,r=%s,c=%b/%b,a=%s,uri=%s,age=%d}",
            getClass().getSimpleName(),
            hashCode(),
            _state,
            _requests,
            isRequestCompleted(),
            isResponseCompleted(),
            _state.getState(),
            _servletContextRequest.getHttpURI(),
            timeStamp == 0 ? 0 : System.currentTimeMillis() - timeStamp);
    }

    void onTrailers(HttpFields trailers)
    {
        _servletContextRequest.setTrailers(trailers);
    }

    /**
     * @see #abort(Throwable)
     */
    public void onCompleted()
    {
        ServletApiRequest apiRequest = _servletContextRequest.getServletApiRequest();
        if (LOG.isDebugEnabled())
            LOG.debug("onCompleted for {} written={}", apiRequest.getRequestURI(), getBytesWritten());

        if (getServer().getRequestLog() instanceof CustomRequestLog)
        {
            CustomRequestLog.LogDetail logDetail = new CustomRequestLog.LogDetail(
                _servletContextRequest.getServletName(),
                apiRequest.getServletContext().getRealPath(Request.getPathInContext(_servletContextRequest)));
            _servletContextRequest.setAttribute(CustomRequestLog.LOG_DETAIL, logDetail);
        }

        // Callback will either be succeeded here or failed in abort().
        Callback callback = _callback;
        if (_state.completeResponse())
        {
            // Must recycle before callback notification to allow for reuse.
            recycle();
            callback.succeeded();
        }
        else
        {
            // Recycle always done here even if an abort is called.
            recycle();
        }
    }

    public boolean isCommitted()
    {
        return _state.isResponseCommitted();
    }

    /**
     * @return True if the request lifecycle is completed
     */
    public boolean isRequestCompleted()
    {
        return _state.isCompleted();
    }

    /**
     * @return True if the response is completely written.
     */
    public boolean isResponseCompleted()
    {
        return _state.isResponseCompleted();
    }

    protected void execute(Runnable task)
    {
        _context.execute(task);
    }

    /**
     * If a write or similar operation to this channel fails,
     * then this method should be called.
     *
     * @param failure the failure that caused the abort.
     * @see #onCompleted()
     */
    public void abort(Throwable failure)
    {
        // Callback will either be failed here or succeeded in onCompleted().
        if (_state.abortResponse())
        {
            if (LOG.isDebugEnabled())
                LOG.debug("abort {}", this, failure);
            _callback.failed(failure);
        }
    }

    private void dispatch() throws Exception
    {
        ServletContextHandler servletContextHandler = getServletContextHandler();
        ServletContextRequest servletContextRequest = getServletContextRequest();
        ServletApiRequest servletApiRequest = servletContextRequest.getServletApiRequest();
        try
        {
            servletContextHandler.requestInitialized(servletContextRequest, servletApiRequest);
            ServletHandler servletHandler = servletContextHandler.getServletHandler();
            ServletHandler.MappedServlet mappedServlet = servletContextRequest.getMatchedResource().getResource();
            mappedServlet.handle(servletHandler, Request.getPathInContext(servletContextRequest), servletApiRequest, servletContextRequest.getHttpServletResponse());
        }
        finally
        {
            servletContextHandler.requestDestroyed(servletContextRequest, servletApiRequest);
        }
    }

    public void dispatchAsync() throws Exception
    {
        ServletContextHandler servletContextHandler = getServletContextHandler();
        ServletContextRequest servletContextRequest = getServletContextRequest();
        ServletApiRequest servletApiRequest = servletContextRequest.getServletApiRequest();
        try
        {
            servletContextHandler.requestInitialized(servletContextRequest, servletApiRequest);

            HttpURI uri;
            String pathInContext;
            AsyncContextEvent asyncContextEvent = _state.getAsyncContextEvent();
            String dispatchString = asyncContextEvent.getDispatchPath();
            if (dispatchString != null)
            {
                String contextPath = _context.getContextPath();
                HttpURI.Immutable dispatchUri = HttpURI.from(dispatchString);
                pathInContext = URIUtil.canonicalPath(dispatchUri.getPath());
                uri = HttpURI.build(servletContextRequest.getHttpURI())
                    .path(URIUtil.addPaths(contextPath, pathInContext))
                    .query(dispatchUri.getQuery());
            }
            else
            {
                uri = asyncContextEvent.getBaseURI();
                if (uri == null)
                {
                    uri = servletContextRequest.getHttpURI();
                    pathInContext = Request.getPathInContext(servletContextRequest);
                }
                else
                {
                    pathInContext = uri.getCanonicalPath();
                    int length = _context.getContextPath().length();
                    if (length > 1)
                        pathInContext = pathInContext.substring(length);
                }
            }
            // We first worked with the core pathInContext above, but now need to convert to servlet style
            String decodedPathInContext = URIUtil.decodePath(pathInContext);
            Dispatcher dispatcher = new Dispatcher(servletContextHandler, uri, decodedPathInContext);
            dispatcher.async(asyncContextEvent.getSuppliedRequest(), asyncContextEvent.getSuppliedResponse());
        }
        finally
        {
            servletContextHandler.requestDestroyed(servletContextRequest, servletApiRequest);
        }
    }
}
