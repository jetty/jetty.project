//
//  ========================================================================
//  Copyright (c) 1995-2012 Sabre Holdings.
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


package org.eclipse.jetty.ant;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.eclipse.jetty.ant.types.Connectors.Connector;
import org.eclipse.jetty.ant.utils.ServerProxy;
import org.eclipse.jetty.ant.utils.TaskLog;
import org.eclipse.jetty.ant.utils.WebApplicationProxy;
import org.eclipse.jetty.security.LoginService;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.RequestLog;
import org.eclipse.jetty.server.SelectChannelConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.server.handler.HandlerCollection;
import org.eclipse.jetty.server.handler.RequestLogHandler;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.xml.XmlConfiguration;
import org.xml.sax.SAXException;

/**
 * A proxy class for interaction with Jetty server object. Used to have some
 * level of abstraction over standard Jetty classes.
 *
 * @author Jakub Pawlowicz
 */
public class ServerProxyImpl implements ServerProxy
{

    /** Proxied Jetty server object. */
    private Server server;

    /** Collection of context handlers (web application contexts). */
    private ContextHandlerCollection contexts;

    /** Location of jetty.xml file. */
    private File jettyXml;

    /** List of connectors. */
    private List connectors;

    /** Request logger. */
    private RequestLog requestLog;

    /** User realms. */
    private List loginServices;

    /** List of added web applications. */
    private Map webApplications = new HashMap();

    /**
     * Default constructor. Creates a new Jetty server with a standard connector
     * listening on a given port.
     *
     * @param connectors
     * @param loginServicesList
     * @param requestLog
     * @param jettyXml
     */
    public ServerProxyImpl(List connectors, List loginServicesList, RequestLog requestLog,
            File jettyXml)
    {
        server = new Server();
        server.setStopAtShutdown(true);

        this.connectors = connectors;
        this.loginServices = loginServicesList;
        this.requestLog = requestLog;
        this.jettyXml = jettyXml;
        configure();
    }

    /**
     * @see org.eclipse.jetty.ant.utils.ServerProxy#addWebApplication(WebApplicationProxy,
     *      int)
     */
    public void addWebApplication(WebApplicationProxy webApp, int scanIntervalSeconds)
    {
        webApp.createApplicationContext(contexts);

        if (scanIntervalSeconds > 0)
        {
            webApplications.put(webApp, new Integer(scanIntervalSeconds));
        }
    }

    /**
     * Configures Jetty server before adding any web applications to it.
     */
    private void configure()
    {
        // Applies external configuration via jetty.xml
        applyJettyXml();

        // Configures connectors for this server instance.
        Iterator<Connector> connectorIterator = connectors.iterator();
        while (connectorIterator.hasNext())
        {
            Connector jettyConnector = (Connector) connectorIterator.next();
            SelectChannelConnector jc = new SelectChannelConnector(server);
            
            jc.setPort(jettyConnector.getPort());
            jc.setIdleTimeout(jettyConnector.getMaxIdleTime());
            
            server.addConnector(jc);
        }

        // Configures login services
        Iterator servicesIterator = loginServices.iterator();
        while (servicesIterator.hasNext())
        {
            LoginService service = (LoginService) servicesIterator.next();
            server.addBean(service);
        }

        // Does not cache resources, to prevent Windows from locking files
        Resource.setDefaultUseCaches(false);

        // Set default server handlers
        configureHandlers();
    }

    private void configureHandlers()
    {
        RequestLogHandler requestLogHandler = new RequestLogHandler();
        if (requestLog != null)
        {
            requestLogHandler.setRequestLog(requestLog);
        }

        contexts = (ContextHandlerCollection) server
                .getChildHandlerByClass(ContextHandlerCollection.class);
        if (contexts == null)
        {
            contexts = new ContextHandlerCollection();
            HandlerCollection handlers = (HandlerCollection) server
                    .getChildHandlerByClass(HandlerCollection.class);
            if (handlers == null)
            {
                handlers = new HandlerCollection();
                server.setHandler(handlers);
                handlers.setHandlers(new Handler[] { contexts, new DefaultHandler(),
                        requestLogHandler });
            }
            else
            {
                handlers.addHandler(contexts);
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
            TaskLog.log("Configuring jetty from xml configuration file = "
                    + jettyXml.getAbsolutePath());
            XmlConfiguration configuration;
            try
            {
                configuration = new XmlConfiguration(Resource.toURL(jettyXml));
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
     * @see org.eclipse.jetty.ant.utils.ServerProxy#start()
     */
    public void start()
    {
        try
        {
            server.start();
            startScanners();
            server.join();

        }
        catch (InterruptedException e)
        {
            new RuntimeException(e);
        }
        catch (Exception e)
        {
            new RuntimeException(e);
        }
    }

    /**
     * Starts web applications' scanners.
     */
    private void startScanners() throws Exception
    {
        Iterator i = webApplications.keySet().iterator();
        while (i.hasNext())
        {
            WebApplicationProxyImpl webApp = (WebApplicationProxyImpl) i.next();
            Integer scanIntervalSeconds = (Integer) webApplications.get(webApp);
            JettyRunTask.startScanner(webApp, scanIntervalSeconds.intValue());
        }
    }

    /**
     * @see org.eclipse.jetty.ant.utils.ServerProxy#getProxiedObject()
     */
    public Object getProxiedObject()
    {
        return server;
    }
}
