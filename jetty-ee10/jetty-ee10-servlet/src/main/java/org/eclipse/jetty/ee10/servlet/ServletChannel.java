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
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EventListener;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import jakarta.servlet.RequestDispatcher;
import org.eclipse.jetty.ee10.servlet.ServletRequestState.Action;
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
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.CustomRequestLog;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.ResponseUtils;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ContextRequest;
import org.eclipse.jetty.util.Blocker;
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
 * {@link ServletRequestState} to coordinate the states of dispatch state, input and
 * output according to the servlet specification.  The combined state so obtained
 * is reflected in the behaviour of the contained {@link HttpInput} implementation of
 * {@link jakarta.servlet.ServletInputStream}.
 *
 * @see ServletRequestState
 * @see HttpInput
 */
public class ServletChannel
{
    private static final Logger LOG = LoggerFactory.getLogger(ServletChannel.class);

    private final ServletRequestState _state;
    private final ServletContextHandler.ServletScopedContext _context;
    private final ServletContextHandler.ServletContextApi _servletContextApi;
    private final ConnectionMetaData _connectionMetaData;
    private final AtomicLong _requests = new AtomicLong();
    private final HttpInput _httpInput;
    private final HttpOutput _httpOutput;
    private final Listener _combinedListener;
    private ServletContextRequest _servletContextRequest;
    private Request _request;
    private Response _response;
    private Callback _callback;
    private boolean _expects100Continue;
    private long _written;

    public ServletChannel(ServletContextHandler servletContextHandler, Request request)
    {
        this(servletContextHandler, request.getConnectionMetaData());
    }

    public ServletChannel(ServletContextHandler servletContextHandler, ConnectionMetaData connectionMetaData)
    {
        _context = servletContextHandler.getContext();
        _servletContextApi = _context.getServletContext();
        _connectionMetaData = connectionMetaData;
        _state = new ServletRequestState(this);
        _httpInput = new HttpInput(this);
        _httpOutput = new HttpOutput(this);
        _combinedListener = new Listeners(_connectionMetaData.getConnector(), servletContextHandler);
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
     * This is called by the ServletContextHandler when a core {@link Request} is accepted and associated with
     * a servlet mapping.
     * @param servletContextRequest The servlet context request to associate
     * @see #recycle()
     */
    public void associate(ServletContextRequest servletContextRequest)
    {
        _state.recycle();
        _httpInput.reopen();
        _httpOutput.recycle();
        _request = _servletContextRequest = servletContextRequest;
        _response = _servletContextRequest.getResponse();
        _expects100Continue = servletContextRequest.getHeaders().contains(HttpHeader.EXPECT, HttpHeaderValue.CONTINUE.asString());

        if (LOG.isDebugEnabled())
            LOG.debug("new {} -> {},{}",
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
    }

    public ServletContextHandler.ServletScopedContext getContext()
    {
        return _context;
    }

    public ServletContextHandler getContextHandler()
    {
        return _context.getContextHandler();
    }

    public ServletContextHandler.ServletContextApi getServletContext()
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

    public ServletRequestState getState()
    {
        return _state;
    }

    public long getBytesWritten()
    {
        return _written;
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
     * @see #getResponse().
     */
    public ServletContextResponse getServletContextResponse()
    {
        ServletContextRequest request = _servletContextRequest;
        return request == null ? null : request.getResponse();
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
        _httpInput.recycle();
        _servletContextRequest = null;
        _callback = null;
        _written = 0;
    }

    /**
     * Handle the servlet request. This is called on the initial dispatch and then again on any asynchronous events.
     * @return True if the channel is ready to continue handling (ie it is not suspended)
     */
    public boolean handle()
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
                        dispatch(() ->
                        {
                            try
                            {
                                _context.getServletContextHandler().requestInitialized(_servletContextRequest, _servletContextRequest.getServletApiRequest());

                                ServletHandler servletHandler = _context.getServletContextHandler().getServletHandler();
                                ServletHandler.MappedServlet mappedServlet = _servletContextRequest.getMatchedResource().getResource();

                                mappedServlet.handle(servletHandler, Request.getPathInContext(_servletContextRequest), _servletContextRequest.getServletApiRequest(), _servletContextRequest.getHttpServletResponse());
                            }
                            finally
                            {
                                _context.getServletContextHandler().requestDestroyed(_servletContextRequest, _servletContextRequest.getServletApiRequest());
                            }
                        });

                        break;
                    }

                    case ASYNC_DISPATCH:
                    {
                        dispatch(() ->
                        {
                            try
                            {
                                _context.getServletContextHandler().requestInitialized(_servletContextRequest, _servletContextRequest.getServletApiRequest());

                                HttpURI uri;
                                String pathInContext;
                                AsyncContextEvent asyncContextEvent = _state.getAsyncContextEvent();
                                String dispatchString = asyncContextEvent.getDispatchPath();
                                if (dispatchString != null)
                                {
                                    String contextPath = _context.getContextPath();
                                    HttpURI.Immutable dispatchUri = HttpURI.from(dispatchString);
                                    pathInContext = URIUtil.canonicalPath(dispatchUri.getPath());
                                    uri = HttpURI.build(_servletContextRequest.getHttpURI())
                                        .path(URIUtil.addPaths(contextPath, pathInContext))
                                        .query(dispatchUri.getQuery());
                                }
                                else
                                {
                                    uri = asyncContextEvent.getBaseURI();
                                    if (uri == null)
                                    {
                                        uri = _servletContextRequest.getHttpURI();
                                        pathInContext = Request.getPathInContext(_servletContextRequest);
                                    }
                                    else
                                    {
                                        pathInContext = uri.getCanonicalPath();
                                        if (_context.getContextPath().length() > 1)
                                            pathInContext = pathInContext.substring(_context.getContextPath().length());
                                    }
                                }
                                // We first worked with the core pathInContext above, but now need to convert to servlet style
                                String decodedPathInContext = URIUtil.decodePath(pathInContext);

                                Dispatcher dispatcher = new Dispatcher(getContextHandler(), uri, decodedPathInContext);
                                dispatcher.async(asyncContextEvent.getSuppliedRequest(), asyncContextEvent.getSuppliedResponse());
                            }
                            finally
                            {
                                _context.getServletContextHandler().requestDestroyed(_servletContextRequest, _servletContextRequest.getServletApiRequest());
                            }
                        });
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
                                ResponseUtils.ensureNotPersistent(_servletContextRequest, _servletContextRequest.getResponse());

                            ContextHandler.ScopedContext context = (ContextHandler.ScopedContext)_servletContextRequest.getAttribute(ErrorHandler.ERROR_CONTEXT);
                            Request.Handler errorHandler = ErrorHandler.getErrorHandler(getServer(), context == null ? null : context.getContextHandler());

                            // If we can't have a body or have no ErrorHandler, then create a minimal error response.
                            if (HttpStatus.hasNoBody(getServletContextResponse().getStatus()) || errorHandler == null)
                            {
                                sendResponseAndComplete();
                            }
                            else
                            {
                                // TODO: do this non-blocking.
                                // Callback completeCallback = Callback.from(() -> _state.completed(null), _state::completed);
                                // _state.completing();
                                try (Blocker.Callback blocker = Blocker.callback())
                                {
                                    // We do not notify ServletRequestListener on this dispatch because it might not
                                    // be dispatched to an error page, so we delegate this responsibility to the ErrorHandler.
                                    dispatch(() -> errorHandler.handle(_servletContextRequest, getServletContextResponse(), blocker));
                                    blocker.block();
                                }
                            }
                        }
                        catch (Throwable x)
                        {
                            if (cause == null)
                                cause = x;
                            else if (ExceptionUtil.areNotAssociated(cause, x))
                                cause.addSuppressed(x);
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
                                    if (ExceptionUtil.areNotAssociated(cause, t))
                                        cause.addSuppressed(t);
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
                            /*
                            TODO: isHandled does not exist and HttpOutput might not be explicitly closed.
                            if (!_request.isHandled() && !_request.getHttpOutput().isClosed())
                            {
                                // The request was not actually handled
                                _response.writeError(HttpStatus.NOT_FOUND_404, _response.getCallback());
                                break;
                            }
                             */

                            // Indicate Connection:close if we can't consume all.
                            if (getServletContextResponse().getStatus() >= 200)
                                ResponseUtils.ensureConsumeAvailableOrNotPersistent(_servletContextRequest, _servletContextRequest.getResponse());
                        }


                        // RFC 7230, section 3.3.
                        if (!_servletContextRequest.isHead() &&
                            getServletContextResponse().getStatus() != HttpStatus.NOT_MODIFIED_304 &&
                            getServletContextResponse().isContentInComplete(_servletContextRequest.getHttpOutput().getWritten()))
                        {
                            if (sendErrorOrAbort("Insufficient content written"))
                                break;
                        }

                        // If send error is called we need to break.
                        // TODO: is this necessary? It always returns false.
                        if (checkAndPrepareUpgrade())
                            break;

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

        boolean suspended = action == Action.WAIT;
        return !suspended;
    }

    /**
     * @param message the error message.
     * @return true if we have sent an error, false if we have aborted.
     */
    public boolean sendErrorOrAbort(String message)
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

    private void dispatch(Dispatchable dispatchable) throws Exception
    {
        try
        {
            _servletContextRequest.getResponse().getHttpOutput().reopen();
            getHttpOutput().reopen();
            _combinedListener.onBeforeDispatch(_servletContextRequest);
            dispatchable.dispatch();
        }
        catch (Throwable x)
        {
            _combinedListener.onDispatchFailure(_servletContextRequest, x);
            throw x;
        }
        finally
        {
            _combinedListener.onAfterDispatch(_servletContextRequest);
        }
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

    /**
     * <p>Checks whether the processing of the request resulted in an upgrade,
     * and if so performs upgrade preparation steps <em>before</em> the upgrade
     * response is sent back to the client.</p>
     * <p>This avoids a race where the server is unprepared if the client sends
     * data immediately after having received the upgrade response.</p>
     * @return true if the channel is not complete and more processing is required,
     * typically because sendError has been called.
     */
    protected boolean checkAndPrepareUpgrade()
    {
        return false;
    }

    void onTrailers(HttpFields trailers)
    {
        _servletContextRequest.setTrailers(trailers);
        _combinedListener.onRequestTrailers(_servletContextRequest);
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
        ServletContextRequest servletContextRequest = _servletContextRequest;
        // Must recycle before notification to allow for reuse.
        // Recycle always done here even if an abort is called.
        recycle();
        if (_state.completeResponse())
        {
            _combinedListener.onComplete(servletContextRequest);
            callback.succeeded();
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
            Callback callback = _callback;
            _combinedListener.onResponseFailure(_servletContextRequest, failure);
            callback.failed(failure);
        }
    }

    interface Dispatchable
    {
        void dispatch() throws Exception;
    }

    /**
     * <p>Listener for Channel events.</p>
     * <p>HttpChannel will emit events for the various phases it goes through while
     * processing an HTTP request and response.</p>
     * <p>Implementations of this interface may listen to those events to track
     * timing and/or other values such as request URI, etc.</p>
     * <p>The events parameters, especially the {@link Request} object, may be
     * in a transient state depending on the event, and not all properties/features
     * of the parameters may be available inside a listener method.</p>
     * <p>It is recommended that the event parameters are <em>not</em> acted upon
     * in the listener methods, or undefined behavior may result. For example, it
     * would be a bad idea to try to read some content from the
     * {@link jakarta.servlet.ServletInputStream} in listener methods. On the other
     * hand, it is legit to store request attributes in one listener method that
     * may be possibly retrieved in another listener method in a later event.</p>
     * <p>Listener methods are invoked synchronously from the thread that is
     * performing the request processing, and they should not call blocking code
     * (otherwise the request processing will be blocked as well).</p>
     * <p>Listener instances that are set as a bean on the {@link Connector} are
     * also added.  If additional listeners are added
     * using the deprecated {@code HttpChannel#addListener(Listener)}</p> method,
     * then an instance of {@code TransientListeners} must be added to the connector
     * in order for them to be invoked.
     */
    // TODO: looks like a lot of these methods are never called.
    public interface Listener extends EventListener
    {
        /**
         * Invoked just after the HTTP request line and headers have been parsed.
         *
         * @param request the request object
         */
        default void onRequestBegin(Request request)
        {
        }

        /**
         * Invoked just before calling the application.
         *
         * @param request the request object
         */
        default void onBeforeDispatch(Request request)
        {
        }

        /**
         * Invoked when the application threw an exception.
         *
         * @param request the request object
         * @param failure the exception thrown by the application
         */
        default void onDispatchFailure(Request request, Throwable failure)
        {
        }

        /**
         * Invoked just after the application returns from the first invocation.
         *
         * @param request the request object
         */
        default void onAfterDispatch(Request request)
        {
        }

        /**
         * Invoked every time a request content chunk has been parsed, just before
         * making it available to the application.
         *
         * @param request the request object
         * @param content a {@link ByteBuffer#slice() slice} of the request content chunk
         */
        default void onRequestContent(Request request, ByteBuffer content)
        {
        }

        /**
         * Invoked when the end of the request content is detected.
         *
         * @param request the request object
         */
        default void onRequestContentEnd(Request request)
        {
        }

        /**
         * Invoked when the request trailers have been parsed.
         *
         * @param request the request object
         */
        default void onRequestTrailers(Request request)
        {
        }

        /**
         * Invoked when the request has been fully parsed.
         *
         * @param request the request object
         */
        default void onRequestEnd(Request request)
        {
        }

        /**
         * Invoked when the request processing failed.
         *
         * @param request the request object
         * @param failure the request failure
         */
        default void onRequestFailure(Request request, Throwable failure)
        {
        }

        /**
         * Invoked just before the response line is written to the network.
         *
         * @param request the request object
         */
        default void onResponseBegin(Request request)
        {
        }

        /**
         * Invoked just after the response is committed (that is, the response
         * line, headers and possibly some content have been written to the
         * network).
         *
         * @param request the request object
         */
        default void onResponseCommit(Request request)
        {
        }

        /**
         * Invoked after a response content chunk has been written to the network.
         *
         * @param request the request object
         * @param content a {@link ByteBuffer#slice() slice} of the response content chunk
         */
        default void onResponseContent(Request request, ByteBuffer content)
        {
        }

        /**
         * Invoked when the response has been fully written.
         *
         * @param request the request object
         */
        default void onResponseEnd(Request request)
        {
        }

        /**
         * Invoked when the response processing failed.
         *
         * @param request the request object
         * @param failure the response failure
         */
        default void onResponseFailure(Request request, Throwable failure)
        {
        }

        /**
         * Invoked when the request <em>and</em> response processing are complete.
         *
         * @param request the request object
         */
        default void onComplete(Request request)
        {
        }
    }

    private static class Listeners implements Listener
    {
        private final List<Listener> _listeners;

        private Listeners(Connector connector, ServletContextHandler servletContextHandler)
        {
            Collection<Listener> connectorListeners = connector.getBeans(Listener.class);
            List<Listener> handlerListeners = servletContextHandler.getEventListeners().stream()
                .filter(l -> l instanceof Listener)
                .map(Listener.class::cast)
                .toList();
            _listeners = new ArrayList<>(connectorListeners);
            _listeners.addAll(handlerListeners);
        }

        @Override
        public void onRequestBegin(Request request)
        {
            _listeners.forEach(l -> notify(l::onRequestBegin, request));
        }

        @Override
        public void onBeforeDispatch(Request request)
        {
            _listeners.forEach(l -> notify(l::onBeforeDispatch, request));
        }

        @Override
        public void onDispatchFailure(Request request, Throwable failure)
        {
            _listeners.forEach(l -> notify(l::onDispatchFailure, request, failure));
        }

        @Override
        public void onAfterDispatch(Request request)
        {
            _listeners.forEach(l -> notify(l::onAfterDispatch, request));
        }

        @Override
        public void onRequestContent(Request request, ByteBuffer content)
        {
            _listeners.forEach(l -> notify(l::onRequestContent, request, content));
        }

        @Override
        public void onRequestContentEnd(Request request)
        {
            _listeners.forEach(l -> notify(l::onRequestContentEnd, request));
        }

        @Override
        public void onRequestTrailers(Request request)
        {
            _listeners.forEach(l -> notify(l::onRequestTrailers, request));
        }

        @Override
        public void onRequestEnd(Request request)
        {
            _listeners.forEach(l -> notify(l::onRequestEnd, request));
        }

        @Override
        public void onRequestFailure(Request request, Throwable failure)
        {
            _listeners.forEach(l -> notify(l::onRequestFailure, request, failure));
        }

        @Override
        public void onResponseBegin(Request request)
        {
            _listeners.forEach(l -> notify(l::onResponseBegin, request));
        }

        @Override
        public void onResponseCommit(Request request)
        {
            _listeners.forEach(l -> notify(l::onResponseCommit, request));
        }

        @Override
        public void onResponseContent(Request request, ByteBuffer content)
        {
            _listeners.forEach(l -> notify(l::onResponseContent, request, content));
        }

        @Override
        public void onResponseEnd(Request request)
        {
            _listeners.forEach(l -> notify(l::onResponseEnd, request));
        }

        @Override
        public void onResponseFailure(Request request, Throwable failure)
        {
            _listeners.forEach(l -> notify(l::onResponseFailure, request, failure));
        }

        @Override
        public void onComplete(Request request)
        {
            _listeners.forEach(l -> notify(l::onComplete, request));
        }

        private void notify(Consumer<Request> consumer, Request request)
        {
            try
            {
                consumer.accept(request);
            }
            catch (Throwable x)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("failure while notifying %s event for %s".formatted(ServletChannel.Listener.class.getSimpleName(), request));
            }
        }

        private void notify(BiConsumer<Request, Throwable> consumer, Request request, Throwable failure)
        {
            try
            {
                consumer.accept(request, failure);
            }
            catch (Throwable x)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("failure while notifying %s event for %s".formatted(ServletChannel.Listener.class.getSimpleName(), request));
            }
        }

        private void notify(BiConsumer<Request, ByteBuffer> consumer, Request request, ByteBuffer byteBuffer)
        {
            try
            {
                consumer.accept(request, byteBuffer.slice());
            }
            catch (Throwable x)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("failure while notifying %s event for %s".formatted(ServletChannel.Listener.class.getSimpleName(), request));
            }
        }
    }
}
