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

package org.eclipse.jetty.runner;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Properties;
import java.util.Random;

import javax.transaction.UserTransaction;

import org.eclipse.jetty.util.security.Constraint;
import org.eclipse.jetty.plus.jndi.Transaction;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.NCSARequestLog;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.server.handler.HandlerCollection;
import org.eclipse.jetty.server.handler.RequestLogHandler;
import org.eclipse.jetty.server.handler.StatisticsHandler;
import org.eclipse.jetty.server.SelectChannelConnector;
import org.eclipse.jetty.server.session.SessionHandler;
import org.eclipse.jetty.security.ConstraintMapping;
import org.eclipse.jetty.security.ConstraintSecurityHandler;
import org.eclipse.jetty.security.HashLoginService;
import org.eclipse.jetty.security.authentication.BasicAuthenticator;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.servlet.StatisticsServlet;
import org.eclipse.jetty.util.RolloverFileOutputStream;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.webapp.WebAppContext;
import org.eclipse.jetty.xml.XmlConfiguration;



public class Runner
{
    private static final Logger LOG = Log.getLogger(Runner.class);

    public static final String[] __plusConfigurationClasses = new String[] {
            org.eclipse.jetty.webapp.WebInfConfiguration.class.getCanonicalName(),
            org.eclipse.jetty.webapp.WebXmlConfiguration.class.getCanonicalName(),
            org.eclipse.jetty.webapp.MetaInfConfiguration.class.getCanonicalName(),
            org.eclipse.jetty.webapp.FragmentConfiguration.class.getCanonicalName(),
            org.eclipse.jetty.plus.webapp.EnvConfiguration.class.getCanonicalName(),
            org.eclipse.jetty.plus.webapp.PlusConfiguration.class.getCanonicalName(),
            org.eclipse.jetty.annotations.AnnotationConfiguration.class.getCanonicalName(),
            org.eclipse.jetty.webapp.JettyWebXmlConfiguration.class.getCanonicalName(),
            org.eclipse.jetty.webapp.TagLibConfiguration.class.getCanonicalName() 
            };

    protected Server _server;
    protected Monitor _monitor;
    protected URLClassLoader _classLoader;
    protected List<URL> _classpath=new ArrayList<URL>();
    protected ContextHandlerCollection _contexts;
    protected RequestLogHandler _logHandler;
    protected String _logFile;
    protected String _configFile;
    protected UserTransaction _ut;
    protected String _utId;
    protected String _txMgrPropertiesFile;
    protected Random _random = new Random();
    protected boolean _isTxServiceAvailable=false;
    protected boolean _enableStatsGathering=false;
    protected String _statsPropFile;
    protected boolean _clusteredSessions=true;


    public Runner()
    {

    }


    public void usage(String error)
    {
        if (error!=null)
            System.err.println("ERROR: "+error);
        System.err.println("Usage: java [-DDEBUG] [-Djetty.home=dir] -jar jetty-runner.jar [--help|--version] [ server opts] [[ context opts] context ...] ");
        System.err.println("Server Options:");
        System.err.println(" --version                          - display version and exit");
        System.err.println(" --log file                         - request log filename (with optional 'yyyy_mm_dd' wildcard");
        System.err.println(" --out file                         - info/warn/debug log filename (with optional 'yyyy_mm_dd' wildcard");
        System.err.println(" --port n                           - port to listen on (default 8080)");
        System.err.println(" --stop-port n                      - port to listen for stop command");
        System.err.println(" --stop-key n                       - security string for stop command (required if --stop-port is present)");
        System.err.println(" --jar file                         - a jar to be added to the classloader");
        System.err.println(" --jdbc classname properties jndiname - classname of XADataSource or driver; properties string; name to register in jndi");
        System.err.println(" --lib dir                          - a directory of jars to be added to the classloader");
        System.err.println(" --classes dir                      - a directory of classes to be added to the classloader");
        System.err.println(" --txFile                           - override properties file for Atomikos");
        System.err.println(" --stats [unsecure|realm.properties] - enable stats gathering servlet context");
        System.err.println(" --config file                      - a jetty xml config file to use instead of command line options");
        System.err.println("Context Options:");
        System.err.println(" --path /path       - context path (default /)");
        System.err.println(" context            - WAR file, web app dir or context.xml file");
        System.exit(1);
    }

    public void configure(String[] args) throws Exception
    {
        // handle classpath bits first so we can initialize the log mechanism.
        for (int i=0;i<args.length;i++)
        {
            if ("--version".equals(args[i]))
            {
                
            }
            
            if ("--lib".equals(args[i]))
            {
                Resource lib = Resource.newResource(args[++i]);
                if (!lib.exists() || !lib.isDirectory())
                    usage("No such lib directory "+lib);
                expandJars(lib);
            }
            else if ("--jar".equals(args[i]))
            {
                Resource jar = Resource.newResource(args[++i]);
                if (!jar.exists() || jar.isDirectory())
                    usage("No such jar "+jar);
                _classpath.add(jar.getURL());
            }
            else if ("--classes".equals(args[i]))
            {
                Resource classes = Resource.newResource(args[++i]);
                if (!classes.exists() || !classes.isDirectory())
                    usage("No such classes directory "+classes);
                _classpath.add(classes.getURL());
            }
            else if (args[i].startsWith("--"))
                i++;
        }

        initClassLoader();

        try
        {
            if (Thread.currentThread().getContextClassLoader().loadClass("com.atomikos.icatch.jta.UserTransactionImp")!=null)
                _isTxServiceAvailable=true;
        }
        catch (ClassNotFoundException e)
        {
            _isTxServiceAvailable=false;
        }
        if (System.getProperties().containsKey("DEBUG"))
            Log.getLog().setDebugEnabled(true);

        LOG.info("Runner");
        LOG.debug("Runner classpath {}",_classpath);

        String contextPath="/";
        boolean contextPathSet=false;
        int port=8080;
        int stopPort=0;
        String stopKey=null;

        boolean transactionManagerProcessed = false;
        boolean runnerServerInitialized = false;

        for (int i=0;i<args.length;i++)
        {
            if ("--port".equals(args[i]))
                port=Integer.parseInt(args[++i]);
            else if ("--stop-port".equals(args[i]))
                stopPort=Integer.parseInt(args[++i]);
            else if ("--stop-key".equals(args[i]))
                stopKey=args[++i];
            else if ("--log".equals(args[i]))
                _logFile=args[++i];
            else if ("--out".equals(args[i]))
            {
                String outFile=args[++i];
                PrintStream out = new PrintStream(new RolloverFileOutputStream(outFile,true,-1));
                LOG.info("Redirecting stderr/stdout to "+outFile);
                System.setErr(out);
                System.setOut(out);
            }
            else if ("--path".equals(args[i]))
            {
                contextPath=args[++i];
                contextPathSet=true;
            }
            else if ("--config".equals(args[i]))
            {
                _configFile=args[++i];
            }
            else if ("--lib".equals(args[i]))
            {
                ++i;//skip
            }
            else if ("--jar".equals(args[i]))
            {
                ++i; //skip
            }
            else if ("--classes".equals(args[i]))
            {
                ++i;//skip
            }
            else if ("--stats".equals( args[i]))
            {
                _enableStatsGathering = true;
                _statsPropFile = args[++i];
                _statsPropFile = ("unsecure".equalsIgnoreCase(_statsPropFile)?null:_statsPropFile);
            }
            else if ("--txFile".equals(args[i]))
            {
                _txMgrPropertiesFile=args[++i];
            }
            else if ("--jdbc".equals(args[i]))
            {
                i=configJDBC(args,i);
            }
            else // process contexts
            {
                if ( !transactionManagerProcessed ) // to be executed once upon starting to process contexts
                {
                    processTransactionManagement();
                    transactionManagerProcessed = true;
                }

                if (!runnerServerInitialized) // log handlers not registered, server maybe not created, etc
                {
                    if (_server == null) // server not initialized yet
                    {
                        // build the server
                        _server = new Server();

                    }

                    //apply a config file if there is one
                    if (_configFile != null)
                    {
                        XmlConfiguration xmlConfiguration = new XmlConfiguration(Resource.newResource(_configFile).getURL());
                        xmlConfiguration.configure(_server);
                    }

                    //check that everything got configured, and if not, make the handlers
                    HandlerCollection handlers = (HandlerCollection) _server.getChildHandlerByClass(HandlerCollection.class);
                    if (handlers == null)
                    {
                        handlers = new HandlerCollection();
                        _server.setHandler(handlers);
                    }
                    
                    //check if contexts already configured
                    _contexts = (ContextHandlerCollection) handlers.getChildHandlerByClass(ContextHandlerCollection.class);
                    if (_contexts == null)
                    {
                        _contexts = new ContextHandlerCollection();
                        prependHandler(_contexts, handlers);
                    }
                    
                  

                    if (_enableStatsGathering)
                    {
                        //if no stats handler already configured
                        if (handlers.getChildHandlerByClass(StatisticsHandler.class) == null)
                        {
                            StatisticsHandler statsHandler = new StatisticsHandler();
                            prependHandler(statsHandler,handlers);
                            ServletContextHandler statsContext = new ServletContextHandler(_contexts, "/stats");
                            statsContext.addServlet(new ServletHolder(new StatisticsServlet()), "/");
                            statsContext.setSessionHandler(new SessionHandler());
                            if (_statsPropFile != null)
                            {
                                HashLoginService loginService = new HashLoginService("StatsRealm", _statsPropFile);
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
                  
                    //ensure a log handler is present
                    _logHandler = (RequestLogHandler)handlers.getChildHandlerByClass( RequestLogHandler.class );
                    if ( _logHandler == null )
                    {
                        _logHandler = new RequestLogHandler();
                        handlers.addHandler( _logHandler );
                    }
                    

                    //check a connector is configured to listen on
                    Connector[] connectors = _server.getConnectors();
                    if (connectors == null || connectors.length == 0)
                    {
                        Connector connector = new SelectChannelConnector();
                        connector.setPort(port);
                        _server.addConnector(connector);
                        if (_enableStatsGathering)
                            connector.setStatsOn(true);
                    }
                    else
                    {
                        if (_enableStatsGathering)
                        {
                            for (int j=0; j<connectors.length; j++)
                            {
                                connectors[j].setStatsOn(true);
                            }
                        }
                    }

                    runnerServerInitialized = true;
                }

                // Create a context
                Resource ctx = Resource.newResource(args[i]);
                if (!ctx.exists())
                    usage("Context '"+ctx+"' does not exist");

                // Configure the context
                if (!ctx.isDirectory() && ctx.toString().toLowerCase().endsWith(".xml"))
                {
                    // It is a context config file
                    XmlConfiguration xmlConfiguration=new XmlConfiguration(ctx.getURL());
                    xmlConfiguration.getIdMap().put("Server",_server);
                    ContextHandler handler=(ContextHandler)xmlConfiguration.configure();
                    _contexts.addHandler(handler);
                    if (contextPathSet)
                        handler.setContextPath(contextPath);
                    handler.setAttribute("org.eclipse.jetty.server.webapp.ContainerIncludeJarPattern",
                                         ".*/.*jsp-api-[^/]*\\.jar$|.*/.*jsp-[^/]*\\.jar$|.*/.*taglibs[^/]*\\.jar$"); 
                }
                else
                {
                    // assume it is a WAR file
                    if (contextPathSet && !(contextPath.startsWith("/")))
                        contextPath = "/"+contextPath;
                    
                    LOG.info("Deploying "+ctx.toString()+" @ "+contextPath);
                    WebAppContext webapp = new WebAppContext(_contexts,ctx.toString(),contextPath);
                    webapp.setConfigurationClasses(__plusConfigurationClasses);
                    webapp.setAttribute("org.eclipse.jetty.server.webapp.ContainerIncludeJarPattern",
                                        ".*/.*jsp-api-[^/]*\\.jar$|.*/.*jsp-[^/]*\\.jar$|.*/.*taglibs[^/]*\\.jar$"); 
                    System.err.println(Arrays.asList(_contexts.getHandlers()));
                }
            }
        }

        if (_server==null)
            usage("No Contexts defined");
        _server.setStopAtShutdown(true);
        _server.setSendServerVersion(true);

        switch ((stopPort > 0 ? 1 : 0) + (stopKey != null ? 2 : 0))
        {
            case 1:
                usage("Must specify --stop-key when --stop-port is specified");
                break;
                
            case 2:
                usage("Must specify --stop-port when --stop-key is specified");
                break;
                
            case 3:
                _monitor = new Monitor(stopPort, stopKey);
                break;
        }

        if (_logFile!=null)
        {
            NCSARequestLog requestLog = new NCSARequestLog(_logFile);
            requestLog.setExtended(false);
            _logHandler.setRequestLog(requestLog);
        }
    }
    
    
    protected void prependHandler (Handler handler, HandlerCollection handlers)
    {
        if (handler == null || handlers == null)
            return;
        
       Handler[] existing = handlers.getChildHandlers();
       Handler[] children = new Handler[existing.length + 1];
       children[0] = handler;
       System.arraycopy(existing, 0, children, 1, existing.length);
       handlers.setHandlers(children);
    }

    
    
    
    protected int configJDBC(String[] args,int i) throws Exception
    {
        String jdbcClass=null;
        String jdbcProperties=null;
        String jdbcJndiName=null;

        if (!_isTxServiceAvailable)
        {
            LOG.warn("JDBC TX support not found on classpath");
            i+=3;
        }
        else
        {
            jdbcClass=args[++i];
            jdbcProperties=args[++i];
            jdbcJndiName=args[++i];

            //check for jdbc resources to register
            if (jdbcClass!=null)
            {
                if (isXADataSource(jdbcClass))
                {
                    Class simpleDataSourceBeanClass = Thread.currentThread().getContextClassLoader().loadClass("com.atomikos.jdbc.SimpleDataSourceBean");
                    Object o = simpleDataSourceBeanClass.newInstance();
                    simpleDataSourceBeanClass.getMethod("setXaDataSourceClassName", new Class[] {String.class}).invoke(o, new Object[] {jdbcClass});
                    simpleDataSourceBeanClass.getMethod("setXaDataSourceProperties", new Class[] {String.class}).invoke(o, new Object[] {jdbcProperties});
                    simpleDataSourceBeanClass.getMethod("setUniqueResourceName", new Class[] {String.class}).invoke(o, new Object[] {jdbcJndiName});
                    org.eclipse.jetty.plus.jndi.Resource jdbcResource = new org.eclipse.jetty.plus.jndi.Resource(jdbcJndiName, o);

                }
                else
                {
                    String[] props = jdbcProperties.split(";");
                    String user=null;
                    String password=null;
                    String url=null;

                    for (int j=0;props!=null && j<props.length;j++)
                    {
                        String[] pair = props[j].split("=");
                        if (pair!=null && pair[0].equalsIgnoreCase("user"))
                            user=pair[1];
                        else if (pair!=null && pair[0].equalsIgnoreCase("password"))
                            password=pair[1];
                        else if (pair!=null && pair[0].equalsIgnoreCase("url"))
                            url=pair[1];

                    }

                    Class nonXADataSourceBeanClass = Thread.currentThread().getContextClassLoader().loadClass("com.atomikos.jdbc.nonxa.NonXADataSourceBean");
                    Object o = nonXADataSourceBeanClass.newInstance();
                    nonXADataSourceBeanClass.getMethod("setDriverClassName", new Class[] {String.class}).invoke(o, new Object[] {jdbcClass});
                    nonXADataSourceBeanClass.getMethod("setUniqueResourceName", new Class[] {String.class}).invoke(o, new Object[] {jdbcJndiName});
                    nonXADataSourceBeanClass.getMethod("setUrl", new Class[] {String.class}).invoke(o, new Object[] {url});
                    nonXADataSourceBeanClass.getMethod("setUser", new Class[] {String.class}).invoke(o, new Object[] {user});
                    nonXADataSourceBeanClass.getMethod("setPassword", new Class[] {String.class}).invoke(o, new Object[] {password});
                    org.eclipse.jetty.plus.jndi.Resource jdbcResource = new org.eclipse.jetty.plus.jndi.Resource(jdbcJndiName, o);
                }
            }
        }

        return i;
    }


    public void run() throws Exception
    {
        if (_monitor != null)
        {
            _monitor.start();
        }
        
        _server.start();
        _server.join();
    }

    protected void expandJars(Resource lib) throws IOException
    {
        String[] list = lib.list();
        if (list==null)
            return;

        for (String path : list)
        {
            if (".".equals(path) || "..".equals(path))
                continue;

            Resource item = lib.addPath(path);

            if (item.isDirectory())
                expandJars(item);
            else
            {
                if (path.toLowerCase().endsWith(".jar") ||
                    path.toLowerCase().endsWith(".zip"))
                {
                    URL url = item.getURL();
                    _classpath.add(url);
                }
            }
        }
    }

    protected void initClassLoader()
    {
        if (_classLoader==null && _classpath!=null && _classpath.size()>0)
        {
            ClassLoader context=Thread.currentThread().getContextClassLoader();

            if (context==null)
                _classLoader=new URLClassLoader(_classpath.toArray(new URL[_classpath.size()]));
            else
                _classLoader=new URLClassLoader(_classpath.toArray(new URL[_classpath.size()]),context);

            Thread.currentThread().setContextClassLoader(_classLoader);
        }
    }


    protected boolean isXADataSource (String classname)
    throws Exception
    {
        Class clazz = Thread.currentThread().getContextClassLoader().loadClass(classname);
        boolean isXA=false;
        while (!isXA && clazz!=null)
        {
            Class[] interfaces = clazz.getInterfaces();
            for (int i=0;interfaces!=null &&!isXA && i<interfaces.length; i++)
            {
                if (interfaces[i].getCanonicalName().equals("javax.sql.XADataSource"))
                    isXA=true;
            }
            clazz=clazz.getSuperclass();
        }
        LOG.debug(isXA?"XA":"!XA");
        return isXA;
    }

    private void processTransactionManagement() throws Exception
    {
        //set up a transaction manager
        if (!_isTxServiceAvailable)
        {
            LOG.warn("No tx manager found");
        }
        else
        {
            //this invocation of jetty needs a unique random number to identify the tx manager
            _utId = Integer.toHexString(_random.nextInt());
            if (_txMgrPropertiesFile == null)
            {
                //Use system properties to config atomikos
                System.setProperty("com.atomikos.icatch.no_file", "true");
                //create a directory for the tx mgr log and console files to go into that will be unique
                File tmpDir = new File(System.getProperty("java.io.tmpdir"));
                tmpDir = new File(tmpDir, _utId);
                tmpDir.mkdir();
                LOG.debug("Made " + tmpDir.getAbsolutePath());
                System.setProperty("com.atomikos.icatch.log_base_dir ", tmpDir.getCanonicalPath());
                System.setProperty("com.atomikos.icatch.console_file_name", "tm-debug.log");
                System.setProperty("com.atomikos.icatch.output_dir", tmpDir.getCanonicalPath());
                System.setProperty("com.atomikos.icatch.tm_unique_name", _utId);
            }
            else
            {
                System.setProperty("com.atomikos.icatch.file", _txMgrPropertiesFile);
            }

            //create UserTransaction
            Class utsClass = Thread.currentThread().getContextClassLoader().loadClass("com.atomikos.icatch.jta.UserTransactionImp");
            //register in JNDI
            Transaction txMgrResource = new Transaction((UserTransaction)utsClass.newInstance());
        }
    }


    public static void main(String[] args)
    {
        Runner runner = new Runner();

        try
        {
            if (args.length>0&&args[0].equalsIgnoreCase("--help"))
            {
                runner.usage(null);
            }
            else if (args.length>0&&args[0].equalsIgnoreCase("--version"))
            {
                System.err.println("org.mortbay.jetty.Runner: "+Server.getVersion());
                System.exit(1);
            }

            runner.configure(args);
            runner.run();

        }
        catch (Exception e)
        {
            e.printStackTrace();
            runner.usage(null);
        }
    }
}
