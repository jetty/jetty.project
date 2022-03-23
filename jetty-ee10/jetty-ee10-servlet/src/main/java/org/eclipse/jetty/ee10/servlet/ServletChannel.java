//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
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
import java.util.EventListener;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.eclipse.jetty.ee10.servlet.ServletRequestState.Action;
import org.eclipse.jetty.http.BadMessageException;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.QuietException;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Content;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.Blocking;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.HostPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.eclipse.jetty.util.thread.Invocable.InvocationType.NON_BLOCKING;

/**
 * HttpChannel represents a single endpoint for HTTP semantic processing.
 * The HttpChannel is both an HttpParser.RequestHandler, where it passively receives events from
 * an incoming HTTP request, and a Runnable, where it actively takes control of the request/response
 * life cycle and calls the application (perhaps suspending and resuming with multiple calls to run).
 * The HttpChannel signals the switch from passive mode to active mode by returning true to one of the
 * HttpParser.RequestHandler callbacks.   The completion of the active phase is signalled by a call to
 * HttpTransport.completed().
 */
public class ServletChannel implements Runnable
{
    public static Listener NOOP_LISTENER = new Listener() {};
    private static final Logger LOG = LoggerFactory.getLogger(ServletChannel.class);

    private final AtomicLong _requests = new AtomicLong();
    private Connector _connector;
    private Executor _executor;
    private HttpConfiguration _configuration;
    private EndPoint _endPoint;
    private ServletRequestState _state;
    private ServletContextHandler.ServletContextApi _servletContextApi;
    private ServletContextRequest _request;
    private long _oldIdleTimeout;
    private Callback _callback;

    // TODO:
    private final Listener _combinedListener = NOOP_LISTENER;

    /**
     * Bytes written after interception (eg after compression)
     */
    private long _written;

    public ServletChannel()
    {
    }

    public void setCallback(Callback callback)
    {
        if (_callback != null)
            throw new IllegalStateException();
        _callback = callback;
    }

    public boolean failAllContent(Throwable x)
    {
        // TODO: what to do with x?
        //       GW: I don't think we need to pass x in any more, as the content pooling is not dependent on
        //       success or failure.   This should just be releaseAllContent()
        while (true)
        {
            Content content = _request.readContent();
            if (content == null)
                return false;

            if (content.isSpecial())
            {
                content.release();
                return content.isLast();
            }

            content.release();
        }
    }

    public void init(ServletContextRequest request)
    {
        _servletContextApi = request.getContext().getServletContext();
        _request = request;
        _executor = request.getContext();
        _state = new ServletRequestState(this); // TODO can this be recycled?
        _endPoint = request.getConnectionMetaData().getConnection().getEndPoint();
        _connector = request.getConnectionMetaData().getConnector();

        // TODO: can we do this?
        _configuration = request.getConnectionMetaData().getHttpConfiguration();

        request.getHttpInput().init();

        if (LOG.isDebugEnabled())
            LOG.debug("new {} -> {},{}",
                this,
                _request,
                _state);
    }

    public ServletContextHandler.Context getContext()
    {
        return _request.getContext();
    }

    public ServletContextHandler getContextHandler()
    {
        return getContext().getContextHandler();
    }

    public ServletContextHandler.ServletContextApi getServletContext()
    {
        return _servletContextApi;
    }

    public HttpOutput getHttpOutput()
    {
        return _request.getHttpOutput();
    }

    public HttpInput getHttpInput()
    {
        return _request.getHttpInput();
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
        return _endPoint.getIdleTimeout();
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
        _endPoint.setIdleTimeout(timeoutMs);
    }

    public HttpConfiguration getHttpConfiguration()
    {
        return _configuration;
    }

    public Server getServer()
    {
        return _connector.getServer();
    }

    public ServletContextRequest getRequest()
    {
        return _request;
    }

    public ServletContextResponse getResponse()
    {
        return _request.getResponse();
    }

    public Connection getConnection()
    {
        return _endPoint.getConnection();
    }

    public EndPoint getEndPoint()
    {
        return _endPoint;
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

        SocketAddress local = _endPoint.getLocalSocketAddress();
        if (local instanceof InetSocketAddress)
            return (InetSocketAddress)local;
        return null;
    }

    public InetSocketAddress getRemoteAddress()
    {
        SocketAddress remote = _endPoint.getRemoteSocketAddress();
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
        throw new UnsupportedOperationException();
    }

    public void recycle()
    {
        _written = 0;
        _oldIdleTimeout = 0;
    }

    @Override
    public void run()
    {
        handle();
    }

    /**
     * @return True if the channel is ready to continue handling (ie it is not suspended)
     */
    public boolean handle()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("handle {} {} ", _request.getHttpURI(), this);

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
                        dispatch(DispatcherType.REQUEST, () ->
                        {
                            ServletContextHandler.ServletContextApi servletContextApi = getServletContextContext();
                            ServletHandler servletHandler = servletContextApi.getContext().getServletContextHandler().getServletHandler();
                            ServletHandler.MappedServlet mappedServlet = _request._mappedServlet;

                            mappedServlet.handle(servletHandler, _request.getHttpServletRequest(), _request.getHttpServletResponse());
                        });

                        break;
                    }

                    case ASYNC_DISPATCH:
                    {
                        dispatch(DispatcherType.ASYNC, () ->
                        {
                            AsyncContextEvent asyncContextEvent = _state.getAsyncContextEvent();
                            String dispatchPath = asyncContextEvent.getDispatchPath();
                            HttpURI baseURI = asyncContextEvent.getBaseURI();
                            if (baseURI == null)
                                baseURI = HttpURI.from(dispatchPath);

                            Dispatcher dispatcher = new Dispatcher(getContextHandler(), baseURI, dispatchPath);
                            dispatcher.async(_request.getHttpServletRequest(), getResponse().getHttpServletResponse());
                        });
                        break;
                    }

                    case ASYNC_TIMEOUT:
                        _state.onTimeout();
                        break;

                    case SEND_ERROR:
                    {
                        try
                        {
                            // Get ready to send an error response
                            getResponse().reset();

                            // the following is needed as you cannot trust the response code and reason
                            // as those could have been modified after calling sendError
                            Integer code = (Integer)_request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
                            if (code == null)
                                code = HttpStatus.INTERNAL_SERVER_ERROR_500;
                            getResponse().setStatus(code);

                            // The handling of the original dispatch failed and we are now going to either generate
                            // and error response ourselves or dispatch for an error page.  If there is content left over
                            // from the failed dispatch, then we try to consume it here and if we fail we add a
                            // Connection:close.  This can't be deferred to COMPLETE as the response will be committed
                            // by then.
                            ensureConsumeAllOrNotPersistent();

                            ContextHandler.Context context = (ContextHandler.Context)_request.getAttribute(ErrorHandler.ERROR_CONTEXT);
                            Request.Processor errorProcessor = ErrorHandler.getErrorProcessor(getServer(), context == null ? null : context.getContextHandler());

                            // If we can't have a body or have no processor, then create a minimal error response.
                            if (HttpStatus.hasNoBody(getResponse().getStatus()) || errorProcessor == null)
                            {
                                sendResponseAndComplete();
                            }
                            else
                            {
                                // TODO: do this non-blocking.
                                // Callback completeCallback = Callback.from(() -> _state.completed(null), _state::completed);
                                // _state.completing();
                                try (Blocking.Callback blocker = Blocking.callback())
                                {
                                    dispatch(DispatcherType.ERROR, () -> errorProcessor.process(_request, getResponse(), blocker));
                                    blocker.block();
                                }
                            }
                        }
                        catch (Throwable x)
                        {
                            if (LOG.isDebugEnabled())
                                LOG.debug("Could not perform ERROR dispatch, aborting", x);
                            if (_state.isResponseCommitted())
                                abort(x);
                            else
                            {
                                try
                                {
                                    getResponse().resetContent();
                                    sendResponseAndComplete();
                                }
                                catch (Throwable t)
                                {
                                    if (x != t)
                                        x.addSuppressed(t);
                                    abort(x);
                                }
                            }
                        }
                        finally
                        {
                            // clean up the context that was set in Response.sendError
                            _request.removeAttribute(ErrorHandler.ERROR_CONTEXT);
                        }
                        break;
                    }

                    case ASYNC_ERROR:
                    {
                        throw _state.getAsyncContextEvent().getThrowable();
                    }

                    case READ_CALLBACK:
                    {
                        ServletContextHandler handler = _state.getContextHandler();
                        handler.getContext().run(() -> _request.getHttpInput().run());
                        break;
                    }

                    case WRITE_CALLBACK:
                    {
                        ServletContextHandler handler = _state.getContextHandler();
                        handler.getContext().run(() -> _request.getHttpOutput().run());
                        break;
                    }

                    case COMPLETE:
                    {
                        if (!getResponse().isCommitted())
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
                            if (getResponse().getStatus() >= 200)
                                ensureConsumeAllOrNotPersistent();
                        }


                        // RFC 7230, section 3.3.
                        if (!_request.isHead() &&
                            getResponse().getStatus() != HttpStatus.NOT_MODIFIED_304 &&
                            !getResponse().isContentComplete(_request.getHttpOutput().getWritten()))
                        {
                            if (sendErrorOrAbort("Insufficient content written"))
                                break;
                        }

                        // If send error is called we need to break.
                        if (checkAndPrepareUpgrade())
                            break;

                        // Set a close callback on the HttpOutput to make it an async callback
                        getResponse().completeOutput(Callback.from(NON_BLOCKING, () -> _state.completed(null), _state::completed));

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

    private void ensureConsumeAllOrNotPersistent()
    {
        while (true)
        {
            Content content = getRequest().readContent();
            if (content == null)
                break;
            content.release();
            if (content.isLast())
                break;
        }
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

            Response.writeError(_request, getResponse(), _callback, HttpStatus.INTERNAL_SERVER_ERROR_500, message);
            return true;
        }
        catch (Throwable x)
        {
            LOG.trace("IGNORED", x);
            abort(x);
        }
        return false;
    }

    private void dispatch(DispatcherType type, Dispatchable dispatchable) throws Exception
    {
        try
        {
            _servletContextApi.getContext().getServletContextHandler().requestInitialized(_request, _request.getHttpServletRequest());
            _combinedListener.onBeforeDispatch(_request);
            dispatchable.dispatch();
        }
        catch (Throwable x)
        {
            _combinedListener.onDispatchFailure(_request, x);
            throw x;
        }
        finally
        {
            _combinedListener.onAfterDispatch(_request);
            _servletContextApi.getContext().getServletContextHandler().requestDestroyed(_request, _request.getHttpServletRequest());
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
                LOG.debug(_request.getHttpServletRequest().getRequestURI(), failure);
        }
        else if (noStack != null)
        {
            // No stack trace unless there is debug turned on
            if (LOG.isDebugEnabled())
                LOG.warn("handleException {}", _request.getHttpServletRequest().getRequestURI(), failure);
            else
                LOG.warn("handleException {} {}", _request.getHttpServletRequest().getRequestURI(), noStack.toString());
        }
        else
        {
            LOG.warn(_request.getHttpServletRequest().getRequestURI(), failure);
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
            getResponse().write(true, Callback.from(() -> _state.completed(null), _state::completed));
        }
        catch (Throwable x)
        {
            abort(x);
        }
    }

    public boolean isExpecting100Continue()
    {
        return false;
    }

    public boolean isExpecting102Processing()
    {
        return false;
    }

    @Override
    public String toString()
    {
        if (_request == null)
        {
            return String.format("%s@%x{null}",
                getClass().getSimpleName(),
                hashCode());
        }

        long timeStamp = _request.getTimeStamp();
        return String.format("%s@%x{s=%s,r=%s,c=%b/%b,a=%s,uri=%s,age=%d}",
            getClass().getSimpleName(),
            hashCode(),
            _state,
            _requests,
            isRequestCompleted(),
            isResponseCompleted(),
            _state.getState(),
            _request.getHttpURI(),
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

    public void onCompleted()
    {
        HttpServletRequest httpServletRequest = _request.getHttpServletRequest();
        if (LOG.isDebugEnabled())
            LOG.debug("onCompleted for {} written={}", httpServletRequest.getRequestURI(), getBytesWritten());

        long idleTO = _configuration.getIdleTimeout();
        if (idleTO >= 0 && getIdleTimeout() != _oldIdleTimeout)
            setIdleTimeout(_oldIdleTimeout);

        _callback.succeeded();
        _combinedListener.onComplete(_request);
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
        _executor.execute(task);
    }

    /**
     * If a write or similar operation to this channel fails,
     * then this method should be called.
     * <p>
     * The standard implementation calls {@code HttpTransport#abort(Throwable)}.
     *
     * @param failure the failure that caused the abort.
     */
    public void abort(Throwable failure)
    {
        if (_state.abortResponse())
        {
            _combinedListener.onResponseFailure(_request, failure);
            _callback.failed(failure);
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
    public interface Listener extends EventListener
    {
        // TODO do we need this class?

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
}
