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
import java.util.Objects;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import org.eclipse.jetty.ee10.servlet.ServletChannelState.Action;
import org.eclipse.jetty.http.BadMessageException;
import org.eclipse.jetty.http.HttpFields;
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
import org.eclipse.jetty.util.StringUtil;
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
 * and is {@link #recycle(Throwable) recycled} after each use before being
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
    final HttpInput _httpInput;
    private final HttpOutput _httpOutput;
    private ServletContextRequest _servletContextRequest;
    private Request _request;
    private Response _response;
    private Callback _callback;

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
     * a servlet mapping. The association remains functional until {@link #recycle(Throwable)} is called,
     * and it remains readable until a call to {@link #recycle(Throwable)} or a subsequent call to {@code associate}.
     * @param servletContextRequest The servlet context request to associate
     * @see #recycle(Throwable)
     */
    public void associate(ServletContextRequest servletContextRequest)
    {
        _httpInput.reopen();
        _request = _servletContextRequest = servletContextRequest;
        _response = _servletContextRequest.getServletContextResponse();

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

        if (request != _request && Request.asInContext(request, ServletContextRequest.class) != _servletContextRequest)
            throw new IllegalStateException();
        _request = request;
        _response = response;
        _callback = callback;
        _state.openOutput();

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

    public boolean isAborted()
    {
        return _state.isAborted();
    }

    public boolean isSendError()
    {
        return _state.isSendError();
    }

    /**
     * Format the address or host returned from Request methods
     *
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

    private long getBytesWritten()
    {
        // This returns the bytes written to the network,
        // which may be different from those written by the
        // application as they might have been compressed.
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
        if (_servletContextRequest == null)
            throw new IllegalStateException("Request/Response does not exist (likely recycled)");
        return request.getServletContextResponse();
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
        if (_response == null)
            throw new IllegalStateException("Response does not exist (likely recycled)");
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
        InetSocketAddress local = getLocalAddress();
        if (local != null)
            return Request.getHostName(local);
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
        InetSocketAddress local = getLocalAddress();
        return local == null ? 0 : local.getPort();
    }

    public InetSocketAddress getLocalAddress()
    {
        return getRequest().getConnectionMetaData().getLocalSocketAddress() instanceof InetSocketAddress inetSocketAddress
            ? inetSocketAddress : null;
    }

    public InetSocketAddress getRemoteAddress()
    {
        return getRequest().getConnectionMetaData().getRemoteSocketAddress() instanceof InetSocketAddress inetSocketAddress
            ? inetSocketAddress : null;
    }

    /**
     * Get return the HttpConfiguration server authority override.
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
     * Prepare to be reused.
     * @param x Any completion exception, or null for successful completion.
     * @see #associate(ServletContextRequest)
     */
    void recycle(Throwable x)
    {
        // _httpInput must be recycled before _state.
        _httpInput.recycle();
        _httpOutput.recycle();
        _state.recycle();
        _servletContextRequest = null;
        _request = null;
        _response = null;
        _callback = null;
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

                            Request.Handler errorHandler = _servletContextRequest.getAttribute(ErrorHandler.ERROR_CONTEXT) instanceof ContextHandler.ScopedContext scopedContext
                                ? ErrorHandler.getErrorHandler(getServer(), scopedContext.getContextHandler()) : null;

                            // If we can't have a body or have no ErrorHandler, then create a minimal error response.
                            if (HttpStatus.hasNoBody(getServletContextResponse().getStatus()) || errorHandler == null)
                            {
                                sendErrorResponseAndComplete();
                            }
                            else
                            {
                                // We do not notify ServletRequestListener on this dispatch because it might not
                                // be dispatched to an error page, so we delegate this responsibility to the ErrorHandler.
                                reopen();
                                _state.errorHandling();

                                // TODO We currently directly call the errorHandler here, but this is not correct in the case of async errors,
                                //      because since a failure has already occurred, the errorHandler is unable to write a response.
                                //      Instead, we should fail the callback, so that it calls Response.writeError(...) with an ErrorResponse
                                //      that ignores existing failures.   However, the error handler needs to be able to call servlet pages,
                                //      so it will need to do a new call to associate(req,res,callback) or similar, to make the servlet request and
                                //      response wrap the error request and response.  Have to think about what callback is passed.
                                errorHandler.handle(getServletContextRequest(), getServletContextResponse(), Callback.from(() -> _state.errorHandlingComplete(null), _state::errorHandlingComplete));
                            }
                        }
                        catch (Throwable x)
                        {
                            if (cause == null)
                                cause = x;
                            else
                                ExceptionUtil.addSuppressedIfNotAssociated(cause, x);
                            if (LOG.isDebugEnabled())
                                LOG.debug("Could not perform error handling, aborting", cause);

                            try
                            {
                                if (_state.isResponseCommitted())
                                {
                                    // Perform the same behavior as when the callback is failed.
                                    _state.errorHandlingComplete(cause);
                                }
                                else
                                {
                                    getServletContextResponse().resetContent();
                                    sendErrorResponseAndComplete();
                                }
                            }
                            catch (Throwable t)
                            {
                                ExceptionUtil.addSuppressedIfNotAssociated(t, cause);
                                abort(t);
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
                        _context.run(_servletContextRequest.getHttpInput()::readCallback);
                        break;
                    }

                    case WRITE_CALLBACK:
                    {
                        _context.run(_servletContextRequest.getHttpOutput()::writeCallback);
                        break;
                    }

                    case COMPLETE:
                    {
                        ServletContextResponse response = getServletContextResponse();
                        if (!response.isCommitted())
                        {
                            // Indicate Connection:close if we can't consume all.
                            if (response.getStatus() >= 200)
                                ResponseUtils.ensureConsumeAvailableOrNotPersistent(_servletContextRequest, response);
                        }

                        // RFC 7230, section 3.3.  We do this here so that a servlet error page can be sent.
                        if (!_servletContextRequest.isHead() && response.getStatus() != HttpStatus.NOT_MODIFIED_304)
                        {
                            // Compare the bytes written by the application, even if
                            // they might be compressed (or changed) by child Handlers.
                            long written = response.getContentBytesWritten();
                            if (response.isContentIncomplete(written))
                            {
                                sendErrorOrAbort("Insufficient content written %d < %d".formatted(written, response.getContentLength()));
                                break;
                            }
                        }

                        // Set a close callback on the HttpOutput to make it an async callback
                        response.completeOutput(Callback.from(NON_BLOCKING, () -> _state.completed(null), _state::completed));
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

        try
        {
            boolean abort = _state.onError(failure);
            if (abort)
                abort(failure);
        }
        catch (Throwable x)
        {
            abort(failure);
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

    public void sendErrorResponseAndComplete()
    {
        try
        {
            _state.completing();
            getServletContextResponse().write(true, getServletContextResponse().getHttpOutput().getByteBuffer(), Callback.from(() -> _state.completed(null), _state::completed));
        }
        catch (Throwable x)
        {
            abort(x);
            _state.completed(x);
        }
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
            LOG.debug("onCompleted for {} written app={} net={}", apiRequest.getRequestURI(), getHttpOutput().getWritten(), getBytesWritten());

        if (getServer().getRequestLog() instanceof CustomRequestLog)
        {
            CustomRequestLog.LogDetail logDetail = new CustomRequestLog.LogDetail(
                _servletContextRequest.getServletName(),
                apiRequest.getServletContext().getRealPath(Request.getPathInContext(_servletContextRequest)));
            _servletContextRequest.setAttribute(CustomRequestLog.LOG_DETAIL, logDetail);
        }

        // Callback is completed only here.
        Callback callback = _callback;
        Throwable failure = _state.completeResponse();
        if (failure == null)
            callback.succeeded();
        else
            callback.failed(failure);
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

    protected void execute(Runnable task, Request request)
    {
        _context.execute(task, request);
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
        // Callback will be failed in onCompleted().
        _state.abort(failure);
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

    /**
     * An AsyncContext dispatch method was called. We may need to dispatch to
     * a different context.
     * @throws Exception
     */
    public void dispatchAsync() throws Exception
    {
        ServletContextHandler targetContextHandler = getServletContextHandler();
        ServletContextRequest servletContextRequest = getServletContextRequest();
        ServletApiRequest servletApiRequest = servletContextRequest.getServletApiRequest();
        try
        {
            AsyncContextEvent asyncContextEvent = _state.getAsyncContextEvent();
            String dispatchString = asyncContextEvent.getDispatchPath();

            if (asyncContextEvent.getDispatchContext() instanceof CrossContextServletContext crossContextServletContext)
            {
                dispatchCrossContextAsync(crossContextServletContext);
            }
            else if (asyncContextEvent.getDispatchContext() == null)
            {
                //the user dispatched to the current context
                targetContextHandler.requestInitialized(servletContextRequest, servletApiRequest);

                String pathInContext;
                HttpURI uri;

                if (dispatchString != null)
                {
                    //dispatch to different path in current context
                    String contextPath = _context.getContextPath();
                    HttpURI.Immutable dispatchUri = HttpURI.from(dispatchString);
                    pathInContext = URIUtil.canonicalPath(dispatchUri.getPath());
                    uri = HttpURI.build(servletContextRequest.getHttpURI())
                        .path(URIUtil.addPaths(contextPath, pathInContext))
                        .query(dispatchUri.getQuery());
                }
                else
                {
                    //dispatch to original path of event's request in current context
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
                Dispatcher dispatcher = new Dispatcher(targetContextHandler, uri, decodedPathInContext);
                dispatcher.async(asyncContextEvent.getSuppliedRequest(), asyncContextEvent.getSuppliedResponse());
            }
            else
            {
                //the container has dispatched to a different context
                ServletContext targetContext = getServletContextHandler().getServletContext().getContext(asyncContextEvent.getDispatchContext().getContextPath());
                if (targetContext instanceof CrossContextServletContext crossContextServletContext)
                    dispatchCrossContextAsync(crossContextServletContext);
            }
        }
        finally
        {
            targetContextHandler.requestDestroyed(servletContextRequest, servletApiRequest);
        }
    }

    /**
     * Async has been started, but dispatched to a different context. This can happen
     * either via application code calling {@link jakarta.servlet.AsyncContext#dispatch(ServletContext, String)}
     * or the container dispatching after application code returns without having performed a dispatch or otherwise
     * completed async.
     * @param crossContextServletContext the special context containing both the origin context and the destination context
     * @throws ServletException
     * @throws IOException
     */
    private void dispatchCrossContextAsync(CrossContextServletContext crossContextServletContext) throws ServletException, IOException
    {
        HttpURI uri;
        ServletContextHandler targetContextHandler = crossContextServletContext.getTargetContext().getContextHandler();
        AsyncContextEvent asyncContextEvent = _state.getAsyncContextEvent();
        String dispatchPathInContext = asyncContextEvent.getDispatchPath();

        if (dispatchPathInContext != null)
        {
            //dispatch is to a specific path
            String encodedPathQuery = URIUtil.normalizePath(URIUtil.addEncodedPaths(targetContextHandler.getContextPath(), dispatchPathInContext));
            if (encodedPathQuery == null)
                throw new BadMessageException(500, "Bad dispatch path");

            //Add in any query params
            if (asyncContextEvent.getBaseURI() != null)
            {
                HttpURI.Mutable builder = HttpURI.build(asyncContextEvent.getBaseURI(), encodedPathQuery);
                if (StringUtil.isEmpty(builder.getParam()))
                    builder.param(asyncContextEvent.getBaseURI().getParam());
                if (StringUtil.isEmpty(builder.getQuery()))
                    builder.query(asyncContextEvent.getBaseURI().getQuery());

                uri = builder.asImmutable();
                dispatchPathInContext = uri.getPathQuery();
            }
            else
            {
                //no base request URI, do our best with the context and dispatch path
                HttpURI.Mutable mutableHttpURI = HttpURI.build(dispatchPathInContext);

                String targetContextPath = targetContextHandler.getContextPath();
                if (!StringUtil.isEmpty(targetContextPath) && !targetContextPath.equals("/"))
                {
                    mutableHttpURI.path(URIUtil.addPaths(targetContextPath, mutableHttpURI.getPath()));
                }
                uri = mutableHttpURI.asImmutable();
            }
        }
        else
        {
            //no dispatch path, assume path as at time of start async
            uri = asyncContextEvent.getBaseURI();
            String contextPath;
            if (uri == null)
            {
                //use the current request's uri and the current context path to extract just the servlet path
                uri = getServletContextRequest().getHttpURI();
                contextPath = getContext().getContextPath();
            }
            else
            {
                //use the context path at the time of start async to extract just the servlet path
                contextPath = asyncContextEvent.getContext().getContextPath();
            }
            dispatchPathInContext = uri.getCanonicalPath();
            int length = contextPath.length();
            if (length > 1)
                dispatchPathInContext = dispatchPathInContext.substring(length);
            HttpURI.Mutable mutableHttpURI = HttpURI.build(dispatchPathInContext);
            mutableHttpURI.path(URIUtil.addPaths(targetContextHandler.getContextPath(), dispatchPathInContext));
            uri = mutableHttpURI.asImmutable();
        }

        CrossContextDispatcher dispatcher = new CrossContextDispatcher(crossContextServletContext, uri, dispatchPathInContext);
        dispatcher.async(asyncContextEvent.getSuppliedRequest(), asyncContextEvent.getSuppliedResponse());
    }
}
