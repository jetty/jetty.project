//
//  ========================================================================
//  Copyright (c) 1995-2012 Sabre Holdings and others.
//  ------------------------------------------------------------------------
//  This program and the accompanying materials are made available under the
//  terms of the Eclipse Public License v. 2.0 which is available at
//  https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
//  which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
//  SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
//  ========================================================================
//

package org.eclipse.jetty.ant;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import org.eclipse.jetty.ant.types.Connector;
import org.eclipse.jetty.ant.types.ContextHandlers;
import org.eclipse.jetty.ant.utils.ServerProxy;
import org.eclipse.jetty.ant.utils.TaskLog;
import org.eclipse.jetty.security.LoginService;
import org.eclipse.jetty.server.RequestLog;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.ShutdownMonitor;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.server.handler.HandlerCollection;
import org.eclipse.jetty.server.handler.HandlerList;
import org.eclipse.jetty.util.Scanner;
import org.eclipse.jetty.util.resource.PathResource;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.xml.XmlConfiguration;
import org.xml.sax.SAXException;

/**
 * A proxy class for interaction with Jetty server object. Used to have some
 * level of abstraction over standard Jetty classes.
 */
public class ServerProxyImpl implements ServerProxy
{

    /**
     * Proxied Jetty server object.
     */
    private Server server;

    /**
     * Temporary files directory.
     */
    private File tempDirectory;

    /**
     * Collection of context handlers (web application contexts).
     */
    private ContextHandlerCollection contexts;

    /**
     * Location of jetty.xml file.
     */
    private File jettyXml;

    /**
     * List of connectors.
     */
    private List<Connector> connectors;

    /**
     * Request logger.
     */
    private RequestLog requestLog;

    /**
     * User realms.
     */
    private List<LoginService> loginServices;

    /**
     * List of added web applications.
     */
    private List<AntWebAppContext> webApplications = new ArrayList<AntWebAppContext>();

    /**
     * other contexts to deploy
     */
    private ContextHandlers contextHandlers;

    /**
     * scan interval for changed files
     */
    private int scanIntervalSecs;

    /**
     * port to listen for stop command
     */
    private int stopPort;

    /**
     * security key for stop command
     */
    private String stopKey;

    /**
     * wait for all jetty threads to exit or continue
     */
    private boolean daemon;

    private boolean configured = false;

    /**
     * WebAppScannerListener
     *
     * Handle notifications that files we are interested in have changed
     * during execution.
     */
    public static class WebAppScannerListener implements Scanner.BulkListener
    {
        AntWebAppContext awc;

        public WebAppScannerListener(AntWebAppContext awc)
        {
            this.awc = awc;
        }

        @Override
        public void filesChanged(Set<String> changedFileNames)
        {
            boolean isScanned = false;
            try
            {
                Iterator<String> itor = changedFileNames.iterator();
                while (!isScanned && itor.hasNext())
                {
                    isScanned = awc.isScanned(Resource.newResource(itor.next()).getFile());
                }
                if (isScanned)
                {
                    awc.stop();
                    awc.start();
                }
            }
            catch (Exception e)
            {
                TaskLog.log(e.getMessage());
            }
        }
    }

    /**
     * Default constructor. Creates a new Jetty server with a standard connector
     * listening on a given port.
     */
    public ServerProxyImpl()
    {
        server = new Server();
        server.setStopAtShutdown(true);
    }

    @Override
    public void addWebApplication(AntWebAppContext webApp)
    {
        webApplications.add(webApp);
    }

    public int getStopPort()
    {
        return stopPort;
    }

    public void setStopPort(int stopPort)
    {
        this.stopPort = stopPort;
    }

    public String getStopKey()
    {
        return stopKey;
    }

    public void setStopKey(String stopKey)
    {
        this.stopKey = stopKey;
    }

    public File getJettyXml()
    {
        return jettyXml;
    }

    public void setJettyXml(File jettyXml)
    {
        this.jettyXml = jettyXml;
    }

    public List<Connector> getConnectors()
    {
        return connectors;
    }

    public void setConnectors(List<Connector> connectors)
    {
        this.connectors = connectors;
    }

    public RequestLog getRequestLog()
    {
        return requestLog;
    }

    public void setRequestLog(RequestLog requestLog)
    {
        this.requestLog = requestLog;
    }

    public List<LoginService> getLoginServices()
    {
        return loginServices;
    }

    public void setLoginServices(List<LoginService> loginServices)
    {
        this.loginServices = loginServices;
    }

    public List<AntWebAppContext> getWebApplications()
    {
        return webApplications;
    }

    public void setWebApplications(List<AntWebAppContext> webApplications)
    {
        this.webApplications = webApplications;
    }

    public File getTempDirectory()
    {
        return tempDirectory;
    }

    public void setTempDirectory(File tempDirectory)
    {
        this.tempDirectory = tempDirectory;
    }

    @Override
    public void start()
    {
        try
        {
            configure();

            configureWebApps();

            server.start();

            System.setProperty("jetty.ant.server.port", "" + ((ServerConnector)server.getConnectors()[0]).getLocalPort());

            String host = ((ServerConnector)server.getConnectors()[0]).getHost();

            if (host == null)
            {
                System.setProperty("jetty.ant.server.host", "localhost");
            }
            else
            {
                System.setProperty("jetty.ant.server.host", host);
            }

            startScanners();

            TaskLog.log("Jetty AntTask Started");

            if (!daemon)
                server.join();
        }
        catch (InterruptedException e)
        {
            new RuntimeException(e);
        }
        catch (Exception e)
        {
            e.printStackTrace();
            new RuntimeException(e);
        }
    }

    @Override
    public Object getProxiedObject()
    {
        return server;
    }

    /**
     * @return the daemon
     */
    public boolean isDaemon()
    {
        return daemon;
    }

    /**
     * @param daemon the daemon to set
     */
    public void setDaemon(boolean daemon)
    {
        this.daemon = daemon;
    }

    /**
     * @return the contextHandlers
     */
    public ContextHandlers getContextHandlers()
    {
        return contextHandlers;
    }

    /**
     * @param contextHandlers the contextHandlers to set
     */
    public void setContextHandlers(ContextHandlers contextHandlers)
    {
        this.contextHandlers = contextHandlers;
    }

    public int getScanIntervalSecs()
    {
        return scanIntervalSecs;
    }

    public void setScanIntervalSecs(int scanIntervalSecs)
    {
        this.scanIntervalSecs = scanIntervalSecs;
    }

    /**
     * Configures Jetty server before adding any web applications to it.
     */
    private void configure()
    {
        if (configured)
            return;

        configured = true;

        if (stopPort > 0 && stopKey != null)
        {
            ShutdownMonitor monitor = ShutdownMonitor.getInstance();
            monitor.setPort(stopPort);
            monitor.setKey(stopKey);
            monitor.setExitVm(false);
        }

        if (tempDirectory != null && !tempDirectory.exists())
            tempDirectory.mkdirs();

        // Applies external configuration via jetty.xml
        applyJettyXml();

        // Configures connectors for this server instance.
        if (connectors != null)
        {
            for (Connector c : connectors)
            {
                ServerConnector jc = new ServerConnector(server);

                jc.setPort(c.getPort());
                jc.setIdleTimeout(c.getMaxIdleTime());

                server.addConnector(jc);
            }
        }

        // Configures login services
        if (loginServices != null)
        {
            for (LoginService ls : loginServices)
            {
                server.addBean(ls);
            }
        }

        // Does not cache resources, to prevent Windows from locking files
        Resource.setDefaultUseCaches(false);

        // Set default server handlers
        configureHandlers();
    }

    /**
     *
     */
    private void configureHandlers()
    {
        if (requestLog != null)
            server.setRequestLog(requestLog);

        contexts = server.getChildHandlerByClass(ContextHandlerCollection.class);
        if (contexts == null)
        {
            contexts = new ContextHandlerCollection();
            HandlerCollection handlers = server.getChildHandlerByClass(HandlerCollection.class);
            if (handlers == null)
                server.setHandler(new HandlerList(contexts, new DefaultHandler()));
            else
                handlers.addHandler(contexts);
        }

        //if there are any extra contexts to deploy
        if (contextHandlers != null && contextHandlers.getContextHandlers() != null)
        {
            for (ContextHandler c : contextHandlers.getContextHandlers())
            {
                contexts.addHandler(c);
            }
        }
    }

    /**
     * Applies jetty.xml configuration to the Jetty server instance.
     */
    private void applyJettyXml()
    {
        if (jettyXml != null && jettyXml.exists())
        {
            TaskLog.log("Configuring jetty from xml configuration file = " + jettyXml.getAbsolutePath());
            XmlConfiguration configuration;
            try
            {
                configuration = new XmlConfiguration(new PathResource(jettyXml));
                configuration.configure(server);
            }
            catch (MalformedURLException e)
            {
                throw new RuntimeException(e);
            }
            catch (SAXException e)
            {
                throw new RuntimeException(e);
            }
            catch (IOException e)
            {
                throw new RuntimeException(e);
            }
            catch (Exception e)
            {
                throw new RuntimeException(e);
            }
        }
    }

    /**
     * Starts web applications' scanners.
     */
    private void startScanners() throws Exception
    {
        for (AntWebAppContext awc : webApplications)
        {
            if (scanIntervalSecs <= 0)
                return;

            TaskLog.log("Web application '" + awc + "': starting scanner at interval of " + scanIntervalSecs + " seconds.");
            Scanner.Listener changeListener = new WebAppScannerListener(awc);
            Scanner scanner = new Scanner();
            scanner.setScanInterval(scanIntervalSecs);
            scanner.addListener(changeListener);
            scanner.setScanDirs(awc.getScanFiles());
            scanner.setReportExistingFilesOnStartup(false);
            scanner.start();
        }
    }

    /**
     *
     */
    private void configureWebApps()
    {
        for (AntWebAppContext awc : webApplications)
        {
            awc.setAttribute(AntWebAppContext.BASETEMPDIR, tempDirectory);
            if (contexts != null)
                contexts.addHandler(awc);
        }
    }
}
