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

package org.eclipse.jetty.server;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.http.DateGenerator;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.io.ArrayByteBufferPool;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ErrorHandler;
import org.eclipse.jetty.server.internal.ResponseHttpFields;
import org.eclipse.jetty.util.Attributes;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.DecoratedObjectFactory;
import org.eclipse.jetty.util.ExceptionUtil;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.Jetty;
import org.eclipse.jetty.util.NanoTime;
import org.eclipse.jetty.util.Uptime;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.annotation.Name;
import org.eclipse.jetty.util.component.AttributeContainerMap;
import org.eclipse.jetty.util.component.ClassLoaderDump;
import org.eclipse.jetty.util.component.DumpableAttributes;
import org.eclipse.jetty.util.component.DumpableCollection;
import org.eclipse.jetty.util.component.DumpableMap;
import org.eclipse.jetty.util.component.Environment;
import org.eclipse.jetty.util.component.Graceful;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.resource.FileSystemPool;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.eclipse.jetty.util.thread.AutoLock;
import org.eclipse.jetty.util.thread.Invocable;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.util.thread.ScheduledExecutorScheduler;
import org.eclipse.jetty.util.thread.Scheduler;
import org.eclipse.jetty.util.thread.ShutdownThread;
import org.eclipse.jetty.util.thread.ThreadPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ManagedObject
public class Server extends Handler.Wrapper implements Attributes
{
    private static final Logger LOG = LoggerFactory.getLogger(Server.class);
    private static final String __serverInfo = "jetty/" + Server.getVersion();

    private final AttributeContainerMap _attributes = new AttributeContainerMap();
    private final ThreadPool _threadPool;
    private final Scheduler _scheduler;
    private final ByteBufferPool _bufferPool;
    private final List<Connector> _connectors = new CopyOnWriteArrayList<>();
    private final Context _serverContext = new ServerContext();
    private final AutoLock _dateLock = new AutoLock();
    private final MimeTypes.Mutable _mimeTypes = new MimeTypes.Mutable();
    private String _serverInfo = __serverInfo;
    private boolean _openEarly = true;
    private boolean _stopAtShutdown;
    private boolean _dumpAfterStart;
    private boolean _dumpBeforeStop;
    private Handler _defaultHandler;
    private Request.Handler _errorHandler;
    private RequestLog _requestLog;
    private boolean _dryRun;
    private volatile DateField _dateField;
    private long _stopTimeout;
    private InvocationType _invocationType = InvocationType.NON_BLOCKING;
    private File _tempDirectory;

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
        addConnector(connector);
        installBean(_attributes);
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
        addConnector(connector);
    }

    public Server(@Name("threadPool") ThreadPool pool)
    {
        this(pool, null, null);
        installBean(new DumpableMap("System Properties", System.getProperties()));
    }

    public Server(@Name("threadPool") ThreadPool threadPool, @Name("scheduler") Scheduler scheduler, @Name("bufferPool") ByteBufferPool bufferPool)
    {
        _threadPool = threadPool != null ? threadPool : new QueuedThreadPool();
        installBean(_threadPool);
        _scheduler = scheduler != null ? scheduler : new ScheduledExecutorScheduler();
        installBean(_scheduler);
        _bufferPool = bufferPool != null ? bufferPool : new ArrayByteBufferPool();
        installBean(_bufferPool);
        setServer(this);
        installBean(FileSystemPool.INSTANCE, false);
    }

    public Handler getDefaultHandler()
    {
        return _defaultHandler;
    }

    /**
     * @param defaultHandler The handler to use if no other handler is set or has handled the request. This handler should
     *                       always accept the request, even if only to send a 404.
     */
    public void setDefaultHandler(Handler defaultHandler)
    {
        if (!isDynamic() && isStarted())
            throw new IllegalStateException(getState());
        if (defaultHandler != null)
            defaultHandler.setServer(this);
        Handler old = _defaultHandler;
        _defaultHandler = defaultHandler;
        updateBean(old, defaultHandler);
    }

    @Override
    public boolean handle(Request request, Response response, Callback callback) throws Exception
    {
        // Handle either with normal handler or default handler
        Handler next = getHandler();
        return next != null && next.handle(request, response, callback) || _defaultHandler != null && _defaultHandler.handle(request, response, callback);
    }

    public String getServerInfo()
    {
        return _serverInfo;
    }

    /**
     * <p>Convenience method to call {@link #setTempDirectory(File)} from a String representation
     * of the temporary directory.</p>
     * @param temp A string representation of the temporary directory.
     * @see #setTempDirectory(File)
     */
    public void setTempDirectory(String temp)
    {
        setTempDirectory(new File(temp));
    }

    /**
     * <p>Set the temporary directory returned by {@link Context#getTempDirectory()} for the root
     * {@link Context} returned {@link #getContext()}. If not set explicitly here, then the root
     * {@link Context#getTempDirectory()} will return either the directory found at
     * {@code new File(IO.asFile(System.getProperty("jetty.base")), "work")} if it exists,
     * else the JVMs temporary directory as {@code IO.asFile(System.getProperty("java.io.tmpdir"))}.
     * @param temp A directory that must exist and be writable or null to get the default.
     */
    public void setTempDirectory(File temp)
    {
        if (isStarted())
            throw new IllegalStateException(getState());
        if (temp != null && !temp.exists())
            throw new IllegalArgumentException("Does not exist: " + temp);
        if (temp != null && !temp.canWrite())
            throw new IllegalArgumentException("Cannot write: " + temp);
        _tempDirectory = temp;
    }

    /**
     * @return The server temporary directory if set, else null. To always obtain a non-null
     * temporary directory use {@link Context#getTempDirectory()} on {@link #getContext()}.
     * @see #getContext()
     * @see Context#getTempDirectory()
     */
    @ManagedAttribute(value = "The server temporary directory", readonly = true)
    public File getTempDirectory()
    {
        return _tempDirectory;
    }

    public void setServerInfo(String serverInfo)
    {
        _serverInfo = serverInfo;
    }

    /**
     * Get the {@link Context} associated with all {@link Request}s prior to being handled by a
     * {@link ContextHandler}. A {@code Server}'s {@link Context}:
     * <ul>
     *     <li>has a {@code null} {@link Context#getContextPath() context path}</li>
     *     <li>returns the {@link ClassLoader} that loaded the {@link Server} from {@link Context#getClassLoader()}.</li>
     *     <li>is an {@link java.util.concurrent.Executor} that delegates to the {@link Server#getThreadPool() Server ThreadPool}</li>
     *     <li>is a {@link org.eclipse.jetty.util.Decorator} using the {@link DecoratedObjectFactory} found
     *     as a {@link #getBean(Class) bean} of the {@link Server}</li>
     *     <li>has the same {@link #getTempDirectory() temporary director} of the {@link Server#getTempDirectory() server}</li>
     * </ul>
     */
    public Context getContext()
    {
        return _serverContext;
    }

    public MimeTypes.Mutable getMimeTypes()
    {
        return _mimeTypes;
    }

    @Override
    public InvocationType getInvocationType()
    {
        if (isDynamic())
            return InvocationType.BLOCKING;

        // Return cached type to avoid a full handler tree walk.
        if (isStarted())
            return _invocationType;

        InvocationType type = InvocationType.NON_BLOCKING;
        Handler handler = getHandler();
        if (handler != null)
            type = Invocable.combine(type, handler.getInvocationType());
        handler = getDefaultHandler();
        if (handler != null)
            type = Invocable.combine(type, handler.getInvocationType());

        return type;
    }

    public boolean isOpenEarly()
    {
        return _openEarly;
    }

    /**
     * Allows to disable early opening of network sockets. Network sockets are opened early by default.
     * @param openEarly If {@code openEarly} is {@code true} (default), network sockets are opened before
     * starting other components. If {@code openEarly} is {@code false}, network connectors open sockets
     * when they're started.
     */
    public void setOpenEarly(boolean openEarly)
    {
        _openEarly = openEarly;
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

    public Request.Handler getErrorHandler()
    {
        return _errorHandler;
    }

    public void setRequestLog(RequestLog requestLog)
    {
        updateBean(_requestLog, requestLog);
        _requestLog = requestLog;
    }

    public void setErrorHandler(Request.Handler errorHandler)
    {
        if (errorHandler instanceof Handler handler)
            handler.setServer(this);
        updateBean(_errorHandler, errorHandler);
        _errorHandler = errorHandler;
    }

    @ManagedAttribute("The version of this server")
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
        return connectors.toArray(new Connector[0]);
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
    @ManagedAttribute("The server Thread pool")
    public ThreadPool getThreadPool()
    {
        return _threadPool;
    }

    @ManagedAttribute("The server Scheduler")
    public Scheduler getScheduler()
    {
        return _scheduler;
    }

    @ManagedAttribute("The server ByteBuffer pool")
    public ByteBufferPool getByteBufferPool()
    {
        return _bufferPool;
    }

    /**
     * @return true if {@link #dumpStdErr()} is called after starting
     */
    @ManagedAttribute("Whether to dump the server to stderr after start")
    public boolean isDumpAfterStart()
    {
        return _dumpAfterStart;
    }

    /**
     * Set true if {@link #dumpStdErr()} is called after starting.
     * @param dumpAfterStart true if {@link #dumpStdErr()} is called after starting
     */
    public void setDumpAfterStart(boolean dumpAfterStart)
    {
        _dumpAfterStart = dumpAfterStart;
    }

    /**
     * @return true if {@link #dumpStdErr()} is called before stopping
     */
    @ManagedAttribute("Whether to dump the server to stderr before stop")
    public boolean isDumpBeforeStop()
    {
        return _dumpBeforeStop;
    }

    /**
     * Set true if {@link #dumpStdErr()} is called before stopping.
     * @param dumpBeforeStop true if {@link #dumpStdErr()} is called before stopping
     */
    public void setDumpBeforeStop(boolean dumpBeforeStop)
    {
        _dumpBeforeStop = dumpBeforeStop;
    }

    /**
     * @return A {@link HttpField} instance efficiently recording the current time to a second resolution,
     * that cannot be cleared from a {@link ResponseHttpFields} instance.
     * @see ResponseHttpFields.PersistentPreEncodedHttpField
     */
    public HttpField getDateField()
    {
        long now = System.currentTimeMillis();
        long seconds = now / 1000;
        DateField df = _dateField;

        if (df == null || df._seconds != seconds)
        {
            try (AutoLock ignore = _dateLock.lock())
            {
                df = _dateField;
                if (df == null || df._seconds != seconds)
                {
                    HttpField field = new ResponseHttpFields.PersistentPreEncodedHttpField(HttpHeader.DATE, DateGenerator.formatDate(now));
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
            //If the Server should be stopped when the jvm exits, register
            //with the shutdown handler thread.
            if (getStopAtShutdown())
                ShutdownThread.register(this);

            //Register the Server with the handler thread for receiving
            //remote stop commands
            ShutdownMonitor.register(this);

            //Start a thread waiting to receive "stop" commands.
            ShutdownMonitor.getInstance().start(); // initialize

            if (_errorHandler == null)
                setErrorHandler(new DynamicErrorHandler());

            String gitHash = Jetty.GIT_HASH;
            String timestamp = Jetty.BUILD_TIMESTAMP;

            LOG.info("jetty-{}; built: {}; git: {}; jvm {}", getVersion(), timestamp, gitHash, System.getProperty("java.runtime.version", System.getProperty("java.version")));
            if (!Jetty.STABLE)
            {
                LOG.warn("THIS IS NOT A STABLE RELEASE! DO NOT USE IN PRODUCTION!");
                LOG.warn("Download a stable release from https://jetty.org/download.html");
            }

            final ExceptionUtil.MultiException multiException = new ExceptionUtil.MultiException();

            // Open network connector to ensure ports are available
            if (!_dryRun && _openEarly)
            {
                _connectors.stream().filter(NetworkConnector.class::isInstance).map(NetworkConnector.class::cast).forEach(connector ->
                {
                    try
                    {
                        connector.open();
                    }
                    catch (Throwable th)
                    {
                        multiException.add(th);
                    }
                });
                // Throw now if verified start sequence and there was an open exception
                multiException.ifExceptionThrow();
            }

            // Start the server and components, but not connectors!
            // #start(LifeCycle) is overridden so that connectors are not started
            super.doStart();

            // Cache the invocation type to avoid runtime walk of handler tree
            // Handlers must check they don't change the InvocationType of a started server
            _invocationType = getInvocationType();

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
                    multiException.add(e);
                    // stop any started connectors
                    _connectors.stream().filter(LifeCycle::isRunning).map(Object.class::cast).forEach(LifeCycle::stop);
                }
            }

            multiException.ifExceptionThrow();
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

        Throwable multiException = null;

        if (getStopTimeout() > 0)
        {
            long end = NanoTime.now() + TimeUnit.MILLISECONDS.toNanos(getStopTimeout());
            try
            {
                Graceful.shutdown(this).get(getStopTimeout(), TimeUnit.MILLISECONDS);
            }
            catch (Throwable e)
            {
                multiException = ExceptionUtil.combine(multiException, e);
            }
            QueuedThreadPool qtp = getBean(QueuedThreadPool.class);
            if (qtp != null)
                qtp.setStopTimeout(Math.max(1000L, NanoTime.millisUntil(end)));
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
                multiException = ExceptionUtil.combine(multiException, e);
            }
        }

        // And finally stop everything else
        try
        {
            super.doStop();
        }
        catch (Throwable e)
        {
            multiException = ExceptionUtil.combine(multiException, e);
        }

        if (getErrorHandler() instanceof DynamicErrorHandler)
            setErrorHandler(null);

        if (getStopAtShutdown())
            ShutdownThread.deregister(this);

        //Unregister the Server with the handler thread for receiving
        //remote stop commands as we are stopped already
        ShutdownMonitor.deregister(this);

        ExceptionUtil.ifExceptionThrow(multiException);
    }

    public void join() throws InterruptedException
    {
        getThreadPool().join();
    }

    @Override
    public Object setAttribute(String name, Object attribute)
    {
        return _attributes.setAttribute(name, attribute);
    }

    @Override
    public Object removeAttribute(String name)
    {
        return _attributes.removeAttribute(name);
    }

    @Override
    public Object getAttribute(String name)
    {
        return _attributes.getAttribute(name);
    }

    @Override
    public Set<String> getAttributeNameSet()
    {
        return _attributes.getAttributeNameSet();
    }

    @Override
    public void clearAttributes()
    {
        _attributes.clearAttributes();
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

        ContextHandler context = getDescendant(ContextHandler.class);

        try
        {
            String protocol = connector.getDefaultConnectionFactory().getProtocol();
            String scheme = "http";
            if (protocol.startsWith("SSL-") || protocol.equals("SSL"))
                scheme = "https";

            String host = connector.getHost();
            if (host == null)
                host = InetAddress.getLocalHost().getHostAddress();
            int port = connector.getLocalPort();

            String path = "/";
            if (context != null)
            {
                Optional<String> vhost = context.getVirtualHosts().stream()
                    .filter(h -> !h.startsWith("*.") && !h.startsWith("@"))
                    .findFirst();
                if (vhost.isPresent())
                {
                    host = vhost.get();
                    int at = host.indexOf('@');
                    if (at > 0)
                        host = host.substring(0, at);
                }

                path = context.getContextPath();
            }
            return new URI(scheme, null, host, port, path, null, null);
        }
        catch (Exception e)
        {
            LOG.warn("Unable to build server URI", e);
            return null;
        }
    }

    @Override
    public Server getServer()
    {
        return this;
    }

    /**
     * Get the Default CSS
     *
     * @return the default CSS
     */
    public Resource getDefaultStyleSheet()
    {
        return newResource("/org/eclipse/jetty/server/jetty-dir.css");
    }

    /**
     * Get the default Favicon
     *
     * @return the default Favicon
     */
    public Resource getDefaultFavicon()
    {
        return newResource("/org/eclipse/jetty/server/favicon.ico");
    }

    /**
     * Create a new Resource representing a resources that is managed by the Server.
     *
     * @param name the name of the resource (relative to `/org/eclipse/jetty/server/`)
     * @return the Resource found, or null if not found.
     */
    private Resource newResource(String name)
    {
        URL url = getClass().getResource(name);
        if (url == null)
            throw new IllegalStateException("Missing server resource: " + name);
        return ResourceFactory.root().newMemoryResource(url);
    }

    @Override
    public String toString()
    {
        return String.format("%s[%s,sto=%d]", super.toString(), getVersion(), getStopTimeout());
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        dumpObjects(out, indent,
            new ClassLoaderDump(this.getClass().getClassLoader()),
            new DumpableCollection("environments", Environment.getAll()),
            new DumpableAttributes("attributes", _attributes),
            FileSystemPool.INSTANCE);
    }

    public static void main(String... args)
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

    private static class DynamicErrorHandler extends ErrorHandler {}

    class ServerContext extends Attributes.Wrapper implements Context
    {
        private final File jettyBase = IO.asFile(System.getProperty("jetty.base"));
        private final File workDir = jettyBase != null && jettyBase.isDirectory() && jettyBase.canWrite() ? new File(jettyBase, "work") : null;
        private final File tempDir = workDir != null && workDir.isDirectory() && workDir.canWrite() ? workDir : IO.asFile(System.getProperty("java.io.tmpdir"));

        private ServerContext()
        {
            super(Server.this);
        }

        @Override
        public String getContextPath()
        {
            return null;
        }

        @Override
        public MimeTypes getMimeTypes()
        {
            return _mimeTypes;
        }

        @Override
        public ClassLoader getClassLoader()
        {
            return Server.class.getClassLoader();
        }

        @Override
        public Resource getBaseResource()
        {
            return null;
        }

        @Override
        public List<String> getVirtualHosts()
        {
            return Collections.emptyList();
        }

        @Override
        public void run(Runnable runnable)
        {
            runnable.run();
        }

        @Override
        public void run(Runnable runnable, Request request)
        {
            runnable.run();
        }

        @Override
        public void execute(Runnable runnable)
        {
            getThreadPool().execute(runnable);
        }

        @Override
        public Request.Handler getErrorHandler()
        {
            return Server.this.getErrorHandler();
        }

        @Override
        public <T> T decorate(T o)
        {
            // TODO cache factory lookup?
            DecoratedObjectFactory factory = Server.this.getBean(DecoratedObjectFactory.class);
            if (factory != null)
                return factory.decorate(o);
            return o;
        }

        @Override
        public void destroy(Object o)
        {
            // TODO cache factory lookup?
            DecoratedObjectFactory factory = Server.this.getBean(DecoratedObjectFactory.class);
            if (factory != null)
                factory.destroy(o);
        }

        @Override
        public String getPathInContext(String canonicallyEncodedPath)
        {
            return canonicallyEncodedPath;
        }

        @Override
        public File getTempDirectory()
        {
            return Objects.requireNonNullElse(Server.this.getTempDirectory(), tempDir);
        }

        @Override
        public String toString()
        {
            return "ServerContext@%x".formatted(Server.this.hashCode());
        }
    }
}
