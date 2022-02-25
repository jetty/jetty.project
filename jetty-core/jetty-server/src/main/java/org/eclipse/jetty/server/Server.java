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

package org.eclipse.jetty.server;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.http.BadMessageException;
import org.eclipse.jetty.http.DateGenerator;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpGenerator;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.PreEncodedHttpField;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ErrorHandler;
import org.eclipse.jetty.server.handler.HandlerWrapper;
import org.eclipse.jetty.util.Attributes;
import org.eclipse.jetty.util.Jetty;
import org.eclipse.jetty.util.MultiException;
import org.eclipse.jetty.util.MultiMap;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.Uptime;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.annotation.Name;
import org.eclipse.jetty.util.component.AttributeContainerMap;
import org.eclipse.jetty.util.component.Graceful;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.thread.AutoLock;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.util.thread.ShutdownThread;
import org.eclipse.jetty.util.thread.ThreadPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Jetty HTTP Servlet Server.
 * This class is the main class for the Jetty HTTP Servlet server.
 * It aggregates Connectors (HTTP request receivers) and request Handlers.
 * The server is itself a handler and a ThreadPool.  Connectors use the ThreadPool methods
 * to run jobs that will eventually call the handle method.
 */
@ManagedObject(value = "Jetty HTTP Servlet server")
public class Server extends HandlerWrapper implements Attributes
{
    private static final Logger LOG = LoggerFactory.getLogger(Server.class);

    private final AttributeContainerMap _attributes = new AttributeContainerMap();
    private final ThreadPool _threadPool;
    private final List<Connector> _connectors = new CopyOnWriteArrayList<>();
    private SessionIdManager _sessionIdManager;
    private boolean _stopAtShutdown;
    private boolean _dumpAfterStart;
    private boolean _dumpBeforeStop;
    private ErrorHandler _errorHandler;
    private RequestLog _requestLog;
    private boolean _dryRun;
    private final AutoLock _dateLock = new AutoLock();
    private volatile DateField _dateField;
    private long _stopTimeout;

    public Server()
    {
        this((ThreadPool)null);
    }

    /**
     * Convenience constructor
     * Creates server and a {@link ServerConnector} at the passed port.
     *
     * @param port The port of a network HTTP connector (or 0 for a randomly allocated port).
     * @see NetworkConnector#getLocalPort()
     */
    public Server(@Name("port") int port)
    {
        this((ThreadPool)null);
        ServerConnector connector = new ServerConnector(this);
        connector.setPort(port);
        setConnectors(new Connector[]{connector});
    }

    /**
     * Convenience constructor
     * <p>
     * Creates server and a {@link ServerConnector} at the passed address.
     *
     * @param addr the inet socket address to create the connector from
     */
    public Server(@Name("address") InetSocketAddress addr)
    {
        this((ThreadPool)null);
        ServerConnector connector = new ServerConnector(this);
        connector.setHost(addr.getHostName());
        connector.setPort(addr.getPort());
        setConnectors(new Connector[]{connector});
    }

    public Server(@Name("threadpool") ThreadPool pool)
    {
        _threadPool = pool != null ? pool : new QueuedThreadPool();
        addBean(_threadPool);
        addBean(_attributes);
        setServer(this);
    }

    public boolean isDryRun()
    {
        return _dryRun;
    }

    public void setDryRun(boolean dryRun)
    {
        _dryRun = dryRun;
    }

    public RequestLog getRequestLog()
    {
        return _requestLog;
    }

    public ErrorHandler getErrorHandler()
    {
        return _errorHandler;
    }

    public void setRequestLog(RequestLog requestLog)
    {
        updateBean(_requestLog, requestLog);
        _requestLog = requestLog;
    }

    public void setErrorHandler(ErrorHandler errorHandler)
    {
        if (errorHandler instanceof ErrorHandler.ErrorPageMapper)
            throw new IllegalArgumentException("ErrorPageMapper is applicable only to ContextHandler");
        updateBean(_errorHandler, errorHandler);
        _errorHandler = errorHandler;
        if (errorHandler != null)
            errorHandler.setServer(this);
    }

    @ManagedAttribute("version of this server")
    public static String getVersion()
    {
        return Jetty.VERSION;
    }

    public void setStopTimeout(long stopTimeout)
    {
        _stopTimeout = stopTimeout;
    }

    public long getStopTimeout()
    {
        return _stopTimeout;
    }

    public boolean getStopAtShutdown()
    {
        return _stopAtShutdown;
    }

    /**
     * Set stop server at shutdown behaviour.
     *
     * @param stop If true, this server instance will be explicitly stopped when the
     * JVM is shutdown. Otherwise the JVM is stopped with the server running.
     * @see Runtime#addShutdownHook(Thread)
     * @see ShutdownThread
     */
    public void setStopAtShutdown(boolean stop)
    {
        //if we now want to stop
        if (stop)
        {
            //and we weren't stopping before
            if (!_stopAtShutdown)
            {
                //only register to stop if we're already started (otherwise we'll do it in doStart())
                if (isStarted())
                    ShutdownThread.register(this);
            }
        }
        else
            ShutdownThread.deregister(this);

        _stopAtShutdown = stop;
    }

    /**
     * @return Returns the connectors.
     */
    @ManagedAttribute(value = "connectors for this server", readonly = true)
    public Connector[] getConnectors()
    {
        List<Connector> connectors = new ArrayList<>(_connectors);
        return connectors.toArray(new Connector[connectors.size()]);
    }

    public void addConnector(Connector connector)
    {
        if (connector.getServer() != this)
            throw new IllegalArgumentException("Connector " + connector +
                " cannot be shared among server " + connector.getServer() + " and server " + this);
        _connectors.add(connector);
        addBean(connector);
    }

    /**
     * Convenience method which calls {@link #getConnectors()} and {@link #setConnectors(Connector[])} to
     * remove a connector.
     *
     * @param connector The connector to remove.
     */
    public void removeConnector(Connector connector)
    {
        if (_connectors.remove(connector))
            removeBean(connector);
    }

    /**
     * Set the connectors for this server.
     * Each connector has this server set as it's ThreadPool and its Handler.
     *
     * @param connectors The connectors to set.
     */
    public void setConnectors(Connector[] connectors)
    {
        if (connectors != null)
        {
            for (Connector connector : connectors)
            {
                if (connector.getServer() != this)
                    throw new IllegalArgumentException("Connector " + connector +
                        " cannot be shared among server " + connector.getServer() + " and server " + this);
            }
        }

        Connector[] oldConnectors = getConnectors();
        updateBeans(oldConnectors, connectors);
        _connectors.removeAll(Arrays.asList(oldConnectors));
        if (connectors != null)
            _connectors.addAll(Arrays.asList(connectors));
    }

    /**
     * Add a bean to all connectors on the server.
     * If the bean is an instance of {@link Connection.Listener} it will also be
     * registered as a listener on all connections accepted by the connectors.
     * @param bean the bean to be added.
     */
    public void addBeanToAllConnectors(Object bean)
    {
        for (Connector connector : getConnectors())
        {
            connector.addBean(bean);
        }
    }

    /**
     * @return Returns the threadPool.
     */
    @ManagedAttribute("the server thread pool")
    public ThreadPool getThreadPool()
    {
        return _threadPool;
    }

    /**
     * @return true if {@link #dumpStdErr()} is called after starting
     */
    @ManagedAttribute("dump state to stderr after start")
    public boolean isDumpAfterStart()
    {
        return _dumpAfterStart;
    }

    /**
     * @param dumpAfterStart true if {@link #dumpStdErr()} is called after starting
     */
    public void setDumpAfterStart(boolean dumpAfterStart)
    {
        _dumpAfterStart = dumpAfterStart;
    }

    /**
     * @return true if {@link #dumpStdErr()} is called before stopping
     */
    @ManagedAttribute("dump state to stderr before stop")
    public boolean isDumpBeforeStop()
    {
        return _dumpBeforeStop;
    }

    /**
     * @param dumpBeforeStop true if {@link #dumpStdErr()} is called before stopping
     */
    public void setDumpBeforeStop(boolean dumpBeforeStop)
    {
        _dumpBeforeStop = dumpBeforeStop;
    }

    public HttpField getDateField()
    {
        long now = System.currentTimeMillis();
        long seconds = now / 1000;
        DateField df = _dateField;

        if (df == null || df._seconds != seconds)
        {
            try (AutoLock lock = _dateLock.lock())
            {
                df = _dateField;
                if (df == null || df._seconds != seconds)
                {
                    HttpField field = new PreEncodedHttpField(HttpHeader.DATE, DateGenerator.formatDate(now));
                    _dateField = new DateField(seconds, field);
                    return field;
                }
            }
        }
        return df._dateField;
    }

    @Override
    protected void doStart() throws Exception
    {
        try
        {
            // Create an error handler if there is none
            if (_errorHandler == null)
                _errorHandler = getBean(ErrorHandler.class);
            if (_errorHandler == null)
                setErrorHandler(new ErrorHandler());
            if (_errorHandler instanceof ErrorHandler.ErrorPageMapper)
                LOG.warn("ErrorPageMapper not supported for Server level Error Handling");
            _errorHandler.setServer(this);

            //If the Server should be stopped when the jvm exits, register
            //with the shutdown handler thread.
            if (getStopAtShutdown())
                ShutdownThread.register(this);

            //Register the Server with the handler thread for receiving
            //remote stop commands
            ShutdownMonitor.register(this);

            //Start a thread waiting to receive "stop" commands.
            ShutdownMonitor.getInstance().start(); // initialize

            String gitHash = Jetty.GIT_HASH;
            String timestamp = Jetty.BUILD_TIMESTAMP;

            LOG.info("jetty-{}; built: {}; git: {}; jvm {}", getVersion(), timestamp, gitHash, System.getProperty("java.runtime.version", System.getProperty("java.version")));
            if (!Jetty.STABLE)
            {
                LOG.warn("THIS IS NOT A STABLE RELEASE! DO NOT USE IN PRODUCTION!");
                LOG.warn("Download a stable release from https://download.eclipse.org/jetty/");
            }

            HttpGenerator.setJettyVersion(HttpConfiguration.SERVER_VERSION);

            MultiException mex = new MultiException();

            // Open network connector to ensure ports are available
            if (!_dryRun)
            {
                _connectors.stream().filter(NetworkConnector.class::isInstance).map(NetworkConnector.class::cast).forEach(connector ->
                {
                    try
                    {
                        connector.open();
                    }
                    catch (Throwable th)
                    {
                        mex.add(th);
                    }
                });
                // Throw now if verified start sequence and there was an open exception
                mex.ifExceptionThrow();
            }

            // Start the server and components, but not connectors!
            // #start(LifeCycle) is overridden so that connectors are not started
            super.doStart();

            if (_dryRun)
            {
                LOG.info(String.format("Started(dry run) %s @%dms", this, Uptime.getUptime()));
                throw new StopException();
            }

            // start connectors
            for (Connector connector : _connectors)
            {
                try
                {
                    connector.start();
                }
                catch (Throwable e)
                {
                    mex.add(e);
                    // stop any started connectors
                    _connectors.stream().filter(LifeCycle::isRunning).map(Object.class::cast).forEach(LifeCycle::stop);
                }
            }

            mex.ifExceptionThrow();
            LOG.info(String.format("Started %s @%dms", this, Uptime.getUptime()));
        }
        catch (Throwable th)
        {
            // Close any connectors that were opened
            _connectors.stream().filter(NetworkConnector.class::isInstance).map(NetworkConnector.class::cast).forEach(nc ->
            {
                try
                {
                    nc.close();
                }
                catch (Throwable th2)
                {
                    if (th != th2)
                        th.addSuppressed(th2);
                }
            });
            throw th;
        }
        finally
        {
            if (isDumpAfterStart() && !(_dryRun && isDumpBeforeStop()))
                dumpStdErr();
        }
    }

    @Override
    protected void start(LifeCycle l) throws Exception
    {
        // start connectors last
        if (!(l instanceof Connector))
            super.start(l);
    }

    @Override
    protected void doStop() throws Exception
    {
        if (isDumpBeforeStop())
            dumpStdErr();

        LOG.info(String.format("Stopped %s", this));
        if (LOG.isDebugEnabled())
            LOG.debug("doStop {}", this);

        MultiException mex = new MultiException();

        if (getStopTimeout() > 0)
        {
            long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(getStopTimeout());
            try
            {
                Graceful.shutdown(this).get(getStopTimeout(), TimeUnit.MILLISECONDS);
            }
            catch (Throwable e)
            {
                mex.add(e);
            }
            QueuedThreadPool qtp = getBean(QueuedThreadPool.class);
            if (qtp != null)
                qtp.setStopTimeout(Math.max(1000L, TimeUnit.NANOSECONDS.toMillis(end - System.nanoTime())));
        }

        // Now stop the connectors (this will close existing connections)
        for (Connector connector : _connectors)
        {
            try
            {
                connector.stop();
            }
            catch (Throwable e)
            {
                mex.add(e);
            }
        }

        // And finally stop everything else
        try
        {
            super.doStop();
        }
        catch (Throwable e)
        {
            mex.add(e);
        }

        if (getStopAtShutdown())
            ShutdownThread.deregister(this);

        //Unregister the Server with the handler thread for receiving
        //remote stop commands as we are stopped already
        ShutdownMonitor.deregister(this);

        mex.ifExceptionThrow();
    }

    /* Handle a request from a connection.
     * Called to handle a request on the connection when either the header has been received,
     * or after the entire request has been received (for short requests of known length), or
     * on the dispatch of an async request.
     */
    public void handle(HttpChannel channel) throws IOException, ServletException
    {
        final String target = channel.getRequest().getPathInfo();
        final Request request = channel.getRequest();
        final Response response = channel.getResponse();

        if (LOG.isDebugEnabled())
            LOG.debug("{} {} {} ?{} on {}", request.getDispatcherType(), request.getMethod(), target, request.getQueryString(), channel);

        if (HttpMethod.OPTIONS.is(request.getMethod()) || "*".equals(target))
        {
            if (!HttpMethod.OPTIONS.is(request.getMethod()))
            {
                request.setHandled(true);
                response.sendError(HttpStatus.BAD_REQUEST_400);
            }
            else
            {
                handleOptions(request, response);
                if (!request.isHandled())
                    handle(target, request, request, response);
            }
        }
        else
            handle(target, request, request, response);

        if (LOG.isDebugEnabled())
            LOG.debug("handled={} async={} committed={} on {}", request.isHandled(), request.isAsyncStarted(), response.isCommitted(), channel);
    }

    /* Handle Options request to server
     */
    protected void handleOptions(Request request, Response response) throws IOException
    {
    }

    /* Handle a request from a connection.
     * Called to handle a request on the connection when either the header has been received,
     * or after the entire request has been received (for short requests of known length), or
     * on the dispatch of an async request.
     */
    public void handleAsync(HttpChannel channel) throws IOException, ServletException
    {
        final HttpChannelState state = channel.getRequest().getHttpChannelState();
        final AsyncContextEvent event = state.getAsyncContextEvent();
        final Request baseRequest = channel.getRequest();

        HttpURI baseUri = event.getBaseURI();
        String encodedPathQuery = event.getDispatchPath();

        if (encodedPathQuery == null && baseUri == null)
        {
            // Simple case, no request modification or merging needed
            handleAsync(channel, event, baseRequest);
            return;
        }

        // this is a dispatch with either a provided URI and/or a dispatched path
        // We will have to modify the request and then revert
        final HttpURI oldUri = baseRequest.getHttpURI();
        final MultiMap<String> oldQueryParams = baseRequest.getQueryParameters();
        try
        {
            if (encodedPathQuery == null)
            {
                baseRequest.setHttpURI(baseUri);
            }
            else
            {
                ServletContext servletContext = event.getServletContext();
                if (servletContext != null)
                {
                    String encodedContextPath = servletContext instanceof ContextHandler.Context
                        ? ((ContextHandler.Context)servletContext).getContextHandler().getContextPathEncoded()
                        : URIUtil.encodePath(servletContext.getContextPath());
                    if (!StringUtil.isEmpty(encodedContextPath))
                    {
                        encodedPathQuery = URIUtil.canonicalPath(URIUtil.addEncodedPaths(encodedContextPath, encodedPathQuery));
                        if (encodedPathQuery == null)
                            throw new BadMessageException(500, "Bad dispatch path");
                    }
                }

                if (baseUri == null)
                    baseUri = oldUri;
                HttpURI.Mutable builder = HttpURI.build(baseUri, encodedPathQuery);
                if (StringUtil.isEmpty(builder.getParam()))
                    builder.param(baseUri.getParam());
                if (StringUtil.isEmpty(builder.getQuery()))
                    builder.query(baseUri.getQuery());
                baseRequest.setHttpURI(builder);

                if (baseUri.getQuery() != null && baseRequest.getQueryString() != null)
                    // TODO why can't the old map be passed?
                    baseRequest.mergeQueryParameters(oldUri.getQuery(), baseRequest.getQueryString());
            }

            baseRequest.setContext(null, baseRequest.getHttpURI().getDecodedPath());
            handleAsync(channel, event, baseRequest);
        }
        finally
        {
            baseRequest.setHttpURI(oldUri);
            baseRequest.setQueryParameters(oldQueryParams);
            baseRequest.resetParameters();
        }
    }

    private void handleAsync(HttpChannel channel, AsyncContextEvent event, Request baseRequest) throws IOException, ServletException
    {
        final String target = baseRequest.getPathInfo();
        final HttpServletRequest request = Request.unwrap(event.getSuppliedRequest());
        final HttpServletResponse response = Response.unwrap(event.getSuppliedResponse());

        if (LOG.isDebugEnabled())
            LOG.debug("{} {} {} on {}", request.getDispatcherType(), request.getMethod(), target, channel);
        handle(target, baseRequest, request, response);
        if (LOG.isDebugEnabled())
            LOG.debug("handledAsync={} async={} committed={} on {}", channel.getRequest().isHandled(), request.isAsyncStarted(), response.isCommitted(), channel);
    }

    public void join() throws InterruptedException
    {
        getThreadPool().join();
    }

    /**
     * @return Returns the sessionIdManager.
     */
    public SessionIdManager getSessionIdManager()
    {
        return _sessionIdManager;
    }

    /**
     * @param sessionIdManager The sessionIdManager to set.
     */
    public void setSessionIdManager(SessionIdManager sessionIdManager)
    {
        updateBean(_sessionIdManager, sessionIdManager);
        _sessionIdManager = sessionIdManager;
    }

    @Override
    public void clearAttributes()
    {
        _attributes.clearAttributes();
    }

    @Override
    public Object getAttribute(String name)
    {
        return _attributes.getAttribute(name);
    }

    @Override
    public Set<String> getAttributeNamesSet()
    {
        return _attributes.getAttributeNamesSet();
    }

    @Override
    public Object removeAttribute(String name)
    {
        return _attributes.removeAttribute(name);
    }

    @Override
    public Object setAttribute(String name, Object attribute)
    {
        return _attributes.setAttribute(name, attribute);
    }

    /**
     * @return The URI of the first {@link NetworkConnector} and first {@link ContextHandler}, or null
     */
    public URI getURI()
    {
        NetworkConnector connector = null;
        for (Connector c : _connectors)
        {
            if (c instanceof NetworkConnector)
            {
                connector = (NetworkConnector)c;
                break;
            }
        }

        if (connector == null)
            return null;

        ContextHandler context = getChildHandlerByClass(ContextHandler.class);

        try
        {
            String protocol = connector.getDefaultConnectionFactory().getProtocol();
            String scheme = "http";
            if (protocol.startsWith("SSL-") || protocol.equals("SSL"))
                scheme = "https";

            String host = connector.getHost();
            if (context != null && context.getVirtualHosts() != null && context.getVirtualHosts().length > 0)
                host = context.getVirtualHosts()[0];
            if (host == null)
                host = InetAddress.getLocalHost().getHostAddress();

            String path = context == null ? null : context.getContextPath();
            if (path == null)
                path = "/";
            return new URI(scheme, null, host, connector.getLocalPort(), path, null, null);
        }
        catch (Exception e)
        {
            LOG.warn("Unable to build server URI", e);
            return null;
        }
    }

    @Override
    public String toString()
    {
        return String.format("%s[%s,sto=%d]", super.toString(), getVersion(), getStopTimeout());
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        dumpObjects(out, indent, new ClassLoaderDump(this.getClass().getClassLoader()));
    }

    public static void main(String... args) throws Exception
    {
        System.err.println(getVersion());
    }

    private static class DateField
    {
        final long _seconds;
        final HttpField _dateField;

        public DateField(long seconds, HttpField dateField)
        {
            super();
            _seconds = seconds;
            _dateField = dateField;
        }
    }
}
