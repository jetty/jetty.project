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

package org.eclipse.jetty.ee9.runner;

import java.io.IOException;
import java.io.PrintStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import org.eclipse.jetty.ee9.security.ConstraintMapping;
import org.eclipse.jetty.ee9.security.ConstraintSecurityHandler;
import org.eclipse.jetty.ee9.security.HashLoginService;
import org.eclipse.jetty.ee9.security.authentication.BasicAuthenticator;
import org.eclipse.jetty.ee9.servlet.ServletContextHandler;
import org.eclipse.jetty.ee9.servlet.ServletHolder;
import org.eclipse.jetty.ee9.servlet.StatisticsServlet;
import org.eclipse.jetty.ee9.webapp.MetaInfConfiguration;
import org.eclipse.jetty.ee9.webapp.WebAppContext;
import org.eclipse.jetty.io.ConnectionStatistics;
import org.eclipse.jetty.server.AbstractConnector;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.CustomRequestLog;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.ShutdownMonitor;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.server.handler.HandlerCollection;
import org.eclipse.jetty.server.handler.HandlerList;
import org.eclipse.jetty.server.handler.StatisticsHandler;
import org.eclipse.jetty.session.SessionHandler;
import org.eclipse.jetty.util.RolloverFileOutputStream;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.eclipse.jetty.util.security.Constraint;
import org.eclipse.jetty.xml.XmlConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runner
 * <p>
 * Combine jetty classes into a single executable jar and run webapps based on the args to it.
 *
 * @deprecated No replacement provided or available.  Migrate to jetty-home (and use {@code ${jetty.base}} directory).
 */
@Deprecated
public class Runner
{
    private static final Logger LOG = LoggerFactory.getLogger(Runner.class);

    public static final String[] PLUS_CONFIGURATION_CLASSES =
        {
            org.eclipse.jetty.ee9.webapp.WebInfConfiguration.class.getCanonicalName(),
            org.eclipse.jetty.ee9.webapp.WebXmlConfiguration.class.getCanonicalName(),
            org.eclipse.jetty.ee9.webapp.MetaInfConfiguration.class.getCanonicalName(),
            org.eclipse.jetty.ee9.webapp.FragmentConfiguration.class.getCanonicalName(),
            org.eclipse.jetty.ee9.plus.webapp.EnvConfiguration.class.getCanonicalName(),
            org.eclipse.jetty.ee9.plus.webapp.PlusConfiguration.class.getCanonicalName(),
            org.eclipse.jetty.ee9.annotations.AnnotationConfiguration.class.getCanonicalName(),
            org.eclipse.jetty.ee9.webapp.JettyWebXmlConfiguration.class.getCanonicalName(),
            org.eclipse.jetty.ee9.webapp.WebAppConfiguration.class.getCanonicalName(),
            org.eclipse.jetty.ee9.webapp.JspConfiguration.class.getCanonicalName()
        };
    public static final String CONTAINER_INCLUDE_JAR_PATTERN = ".*/jetty-runner-[^/]*\\.jar$";
    public static final String DEFAULT_CONTEXT_PATH = "/";
    public static final int DEFAULT_PORT = 8080;

    protected Server _server;
    protected URLClassLoader _classLoader;
    protected Classpath _classpath = new Classpath();
    protected ContextHandlerCollection _contexts;
    protected String _logFile;
    protected ArrayList<String> _configFiles;
    protected boolean _enableStats = false;
    protected String _statsPropFile;
    protected String _serverUriFile;

    /**
     * Classpath
     */
    public class Classpath
    {
        private List<URI> _classpath = new ArrayList<>();

        public void addJars(Resource lib) throws IOException
        {
            if (lib == null || !lib.exists())
                throw new IllegalStateException("No such lib: " + lib);

            String[] list = lib.list();
            if (list == null)
                return;

            for (String path : list)
            {
                if (".".equals(path) || "..".equals(path))
                    continue;

                try (Resource item = lib.addPath(path))
                {
                    if (item.isDirectory())
                        addJars(item);
                    else
                    {
                        String lowerCasePath = path.toLowerCase(Locale.ENGLISH);
                        if (lowerCasePath.endsWith(".jar") ||
                            lowerCasePath.endsWith(".zip"))
                        {
                            _classpath.add(item.getURI());
                        }
                    }
                }
            }
        }

        public void addPath(Resource path)
        {
            if (path == null || !path.exists())
                throw new IllegalStateException("No such path: " + path);
            _classpath.add(path.getURI());
        }

        public URI[] asArray()
        {
            return _classpath.toArray(new URI[0]);
        }
    }

    public Runner()
    {
    }

    /**
     * Generate helpful usage message and exit
     *
     * @param error the error header
     */
    public void usage(String error)
    {
        if (error != null)
            System.err.println("ERROR: " + error);
        System.err.println("Usage: java [-Djetty.home=dir] -jar jetty-runner.jar [--help|--version] [ server opts] [[ context opts] context ...] ");
        System.err.println("Server opts:");
        System.err.println(" --version                           - display version and exit");
        System.err.println(" --log file                          - request log filename (with optional 'yyyy_mm_dd' wildcard");
        System.err.println(" --out file                          - info/warn/debug log filename (with optional 'yyyy_mm_dd' wildcard");
        System.err.println(" --host name|ip                      - interface to listen on (default is all interfaces)");
        System.err.println(" --port n                            - port to listen on (default 8080)");
        System.err.println(" --server-uri-file path              - file to write a single line with server base URI");
        System.err.println(" --stop-port n                       - port to listen for stop command (or -DSTOP.PORT=n)");
        System.err.println(" --stop-key n                        - security string for stop command (required if --stop-port is present) (or -DSTOP.KEY=n)");
        System.err.println(" [--jar file]*n                      - each tuple specifies an extra jar to be added to the classloader");
        System.err.println(" [--lib dir]*n                       - each tuple specifies an extra directory of jars to be added to the classloader");
        System.err.println(" [--classes dir]*n                   - each tuple specifies an extra directory of classes to be added to the classloader");
        System.err.println(" --stats [unsecure|realm.properties] - enable stats gathering servlet context");
        System.err.println(" [--config file]*n                   - each tuple specifies the name of a jetty xml config file to apply (in the order defined)");
        System.err.println("Context opts:");
        System.err.println(" [[--path /path] context]*n          - WAR file, web app dir or context xml file, optionally with a context path");
        System.exit(1);
    }

    /**
     * Generate version message and exit
     */
    public void version()
    {
        System.err.println("org.eclipse.jetty.runner.Runner: " + Server.getVersion());
        System.exit(1);
    }

    /**
     * Configure a jetty instance and deploy the webapps presented as args
     *
     * @param args the command line arguments
     * @throws Exception if unable to configure
     */
    public void configure(String[] args) throws Exception
    {
        try (ResourceFactory.Closeable resourceFactory = ResourceFactory.closeable())
        {
            // handle classpath bits first so we can initialize the log mechanism.
            for (int i = 0; i < args.length; i++)
            {
                if ("--lib".equals(args[i]))
                {
                    Resource lib = resourceFactory.newResource(args[++i]);
                    if (!lib.exists() || !lib.isDirectory())
                        usage("No such lib directory " + lib);
                    _classpath.addJars(lib);
                }
                else if ("--jar".equals(args[i]))
                {
                    Resource jar = resourceFactory.newResource(args[++i]);
                    if (!jar.exists() || jar.isDirectory())
                        usage("No such jar " + jar);
                    _classpath.addPath(jar);
                }
                else if ("--classes".equals(args[i]))
                {
                    Resource classes = resourceFactory.newResource(args[++i]);
                    if (!classes.exists() || !classes.isDirectory())
                        usage("No such classes directory " + classes);
                    _classpath.addPath(classes);
                }
                else if (args[i].startsWith("--"))
                    i++;
            }
        }

        initClassLoader();

        LOG.info("Runner");
        LOG.debug("Runner classpath {}", _classpath);

        String contextPath = DEFAULT_CONTEXT_PATH;
        boolean contextPathSet = false;
        int port = DEFAULT_PORT;
        String host = null;
        int stopPort = Integer.getInteger("STOP.PORT", 0);
        String stopKey = System.getProperty("STOP.KEY", null);

        boolean runnerServerInitialized = false;

        for (int i = 0; i < args.length; i++)
        {
            switch (args[i])
            {
                case "--port":
                    port = Integer.parseInt(args[++i]);
                    break;
                case "--host":
                    host = args[++i];
                    break;
                case "--stop-port":
                    stopPort = Integer.parseInt(args[++i]);
                    break;
                case "--stop-key":
                    stopKey = args[++i];
                    break;
                case "--log":
                    _logFile = args[++i];
                    break;
                case "--out":
                    String outFile = args[++i];
                    PrintStream out = new PrintStream(new RolloverFileOutputStream(outFile, true, -1));
                    LOG.info("Redirecting stderr/stdout to {}", outFile);
                    System.setErr(out);
                    System.setOut(out);
                    break;
                case "--path":
                    contextPath = args[++i];
                    contextPathSet = true;
                    break;
                case "--config":
                    if (_configFiles == null)
                        _configFiles = new ArrayList<>();
                    _configFiles.add(args[++i]);
                    break;
                case "--lib":
                    ++i; //skip

                    break;
                case "--jar":
                    ++i; //skip

                    break;
                case "--classes":
                    ++i; //skip

                    break;
                case "--stats":
                    _enableStats = true;
                    _statsPropFile = args[++i];
                    _statsPropFile = ("unsecure".equalsIgnoreCase(_statsPropFile) ? null : _statsPropFile);
                    break;
                case "--server-uri-file":
                    _serverUriFile = args[++i];
                    break;
                default:
                    // process system property type argument so users can use in second args part
                    if (args[i].startsWith("-D"))
                    {
                        String[] sysProps = args[i].substring(2).split("=", 2);
                        if ("STOP.KEY".equals(sysProps[0]))
                        {
                            stopKey = sysProps[1];
                            break;
                        }
                        else if ("STOP.PORT".equals(sysProps[0]))
                        {
                            stopPort = Integer.parseInt(sysProps[1]);
                            break;
                        }
                    }

                    // process contexts

                    if (!runnerServerInitialized) // log handlers not registered, server maybe not created, etc
                    {
                        if (_server == null) // server not initialized yet
                        {
                            // build the server
                            _server = new Server();
                        }

                        //apply jetty config files if there are any
                        if (_configFiles != null)
                        {
                            for (String cfg : _configFiles)
                            {
                                Resource resource = ResourceFactory.of(_server).newResource(cfg);
                                XmlConfiguration xmlConfiguration = new XmlConfiguration(resource);
                                xmlConfiguration.configure(_server);
                            }
                        }

                        //check that everything got configured, and if not, make the handlers
                        HandlerCollection handlers = _server.getChildHandlerByClass(HandlerCollection.class);
                        if (handlers == null)
                        {
                            handlers = new HandlerList();
                            _server.setHandler(handlers);
                        }

                        //check if contexts already configured
                        _contexts = handlers.getChildHandlerByClass(ContextHandlerCollection.class);
                        if (_contexts == null)
                        {
                            _contexts = new ContextHandlerCollection();
                            prependHandler(_contexts, handlers);
                        }

                        if (_enableStats)
                        {
                            //if no stats handler already configured
                            if (handlers.getChildHandlerByClass(StatisticsHandler.class) == null)
                            {
                                StatisticsHandler statsHandler = new StatisticsHandler();

                                Handler oldHandler = _server.getHandler();
                                statsHandler.setHandler(oldHandler);
                                _server.setHandler(statsHandler);

                                ServletContextHandler statsContext = new ServletContextHandler(_contexts, "/stats");
                                statsContext.addServlet(new ServletHolder(new StatisticsServlet()), "/");
                                statsContext.setSessionHandler(new SessionHandler());
                                if (_statsPropFile != null)
                                {
                                    ResourceFactory resourceFactory = ResourceFactory.of(statsContext);
                                    Resource statsResource = resourceFactory.newResource(_statsPropFile);
                                    final HashLoginService loginService = new HashLoginService("StatsRealm", statsResource);
                                    Constraint constraint = new Constraint();
                                    constraint.setName("Admin Only");
                                    constraint.setRoles(new String[]{"admin"});
                                    constraint.setAuthenticate(true);

                                    ConstraintMapping cm = new ConstraintMapping();
                                    cm.setConstraint(constraint);
                                    cm.setPathSpec("/*");

                                    ConstraintSecurityHandler securityHandler = new ConstraintSecurityHandler();
                                    securityHandler.setLoginService(loginService);
                                    securityHandler.setConstraintMappings(Collections.singletonList(cm));
                                    securityHandler.setAuthenticator(new BasicAuthenticator());
                                    statsContext.setSecurityHandler(securityHandler);
                                }
                            }
                        }

                        //ensure a DefaultHandler is present
                        if (handlers.getChildHandlerByClass(DefaultHandler.class) == null)
                        {
                            handlers.addHandler(new DefaultHandler());
                        }

                        //check a connector is configured to listen on
                        Connector[] connectors = _server.getConnectors();
                        if (connectors == null || connectors.length == 0)
                        {
                            ServerConnector connector = new ServerConnector(_server);
                            connector.setPort(port);
                            if (host != null)
                                connector.setHost(host);
                            _server.addConnector(connector);
                            if (_enableStats)
                                connector.addBean(new ConnectionStatistics());
                        }
                        else
                        {
                            if (_enableStats)
                            {
                                for (Connector connector : connectors)
                                {
                                    ((AbstractConnector)connector).addBean(new ConnectionStatistics());
                                }
                            }
                        }

                        runnerServerInitialized = true;
                    }

                    // Create a context
                    Resource ctx = ResourceFactory.of(_server).newResource(args[i]);
                    if (!ctx.exists())
                        usage("Context '" + ctx + "' does not exist");

                    if (contextPathSet && !(contextPath.startsWith("/")))
                        contextPath = "/" + contextPath;

                    // Configure the context
                    if (!ctx.isDirectory() && ctx.toString().toLowerCase(Locale.ENGLISH).endsWith(".xml"))
                    {
                        // It is a context config file
                        XmlConfiguration xmlConfiguration = new XmlConfiguration(ctx);
                        xmlConfiguration.getIdMap().put("Server", _server);
                        ContextHandler handler = (ContextHandler)xmlConfiguration.configure();
                        if (contextPathSet)
                            handler.setContextPath(contextPath);
                        _contexts.addHandler(handler);
                        String containerIncludeJarPattern = (String)handler.getAttribute(MetaInfConfiguration.CONTAINER_JAR_PATTERN);
                        if (containerIncludeJarPattern == null)
                            containerIncludeJarPattern = CONTAINER_INCLUDE_JAR_PATTERN;
                        else
                        {
                            if (!containerIncludeJarPattern.contains(CONTAINER_INCLUDE_JAR_PATTERN))
                            {
                                containerIncludeJarPattern = containerIncludeJarPattern + (StringUtil.isBlank(containerIncludeJarPattern) ? "" : "|") + CONTAINER_INCLUDE_JAR_PATTERN;
                            }
                        }

                        handler.setAttribute(MetaInfConfiguration.CONTAINER_JAR_PATTERN, containerIncludeJarPattern);

                        //check the configurations, if not explicitly set up, then configure all of them
                        if (handler instanceof WebAppContext)
                        {
                            WebAppContext wac = (WebAppContext)handler;
                            if (wac.getConfigurationClasses() == null || wac.getConfigurationClasses().length == 0)
                                wac.setConfigurationClasses(PLUS_CONFIGURATION_CLASSES);
                        }
                    }
                    else
                    {
                        // assume it is a WAR file
                        WebAppContext webapp = new WebAppContext(_contexts, ctx.toString(), contextPath);
                        webapp.setConfigurationClasses(PLUS_CONFIGURATION_CLASSES);
                        webapp.setAttribute(MetaInfConfiguration.CONTAINER_JAR_PATTERN,
                            CONTAINER_INCLUDE_JAR_PATTERN);
                    }

                    //reset
                    contextPathSet = false;
                    contextPath = DEFAULT_CONTEXT_PATH;
                    break;
            }
        }

        if (_server == null)
            usage("No Contexts defined");
        _server.setStopAtShutdown(true);

        switch ((stopPort > 0 ? 1 : 0) + (stopKey != null ? 2 : 0))
        {
            case 1:
                usage("Must specify --stop-key when --stop-port is specified");
                break;

            case 2:
                usage("Must specify --stop-port when --stop-key is specified");
                break;

            case 3:
                ShutdownMonitor monitor = ShutdownMonitor.getInstance();
                monitor.setPort(stopPort);
                monitor.setKey(stopKey);
                monitor.setExitVm(true);
                break;

            default:
                break;
        }

        if (_logFile != null)
        {
            CustomRequestLog requestLog = new CustomRequestLog(_logFile);
            _server.setRequestLog(requestLog);
        }
    }

    protected void prependHandler(Handler handler, HandlerCollection handlers)
    {
        if (handler == null || handlers == null)
            return;

        Handler[] existing = handlers.getChildHandlers();
        Handler[] children = new Handler[existing.length + 1];
        children[0] = handler;
        System.arraycopy(existing, 0, children, 1, existing.length);
        handlers.setHandlers(children);
    }

    public void run() throws Exception
    {
        _server.start();
        if (_serverUriFile != null)
        {
            Path fileWithPort = Paths.get(_serverUriFile);
            Files.deleteIfExists(fileWithPort);
            String serverUri = _server.getURI().toString();
            Files.writeString(fileWithPort, serverUri);
        }
        _server.join();
    }

    private URL toURL(URI uri)
    {
        try
        {
            return uri.toURL();
        }
        catch (MalformedURLException e)
        {
            throw new RuntimeException(e);
        }
    }

    /**
     * Establish a classloader with custom paths (if any)
     */
    protected void initClassLoader()
    {
        URL[] paths = Arrays.stream(_classpath.asArray()).map(this::toURL).toArray(URL[]::new);

        if (_classLoader == null && paths.length > 0)
        {
            ClassLoader context = Thread.currentThread().getContextClassLoader();

            if (context == null)
            {
                _classLoader = new URLClassLoader(paths);
            }
            else
            {
                _classLoader = new URLClassLoader(paths, context);
            }

            Thread.currentThread().setContextClassLoader(_classLoader);
        }
    }

    public static void main(String[] args)
    {
        System.err.println("WARNING: jetty-runner is deprecated.");
        System.err.println("         See Jetty Documentation for startup options");
        System.err.println("         https://www.eclipse.org/jetty/documentation/");

        Runner runner = new Runner();

        try
        {
            if (args.length > 0 && args[0].equalsIgnoreCase("--help"))
            {
                runner.usage(null);
            }
            else if (args.length > 0 && args[0].equalsIgnoreCase("--version"))
            {
                runner.version();
            }
            else
            {
                runner.configure(args);
                runner.run();
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
            runner.usage(null);
        }
    }
}
