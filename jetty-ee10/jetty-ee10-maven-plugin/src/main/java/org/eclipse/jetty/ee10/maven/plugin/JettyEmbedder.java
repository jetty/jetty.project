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

package org.eclipse.jetty.ee10.maven.plugin;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.eclipse.jetty.ee10.quickstart.QuickStartConfiguration;
import org.eclipse.jetty.ee10.quickstart.QuickStartConfiguration.Mode;
import org.eclipse.jetty.ee10.servlet.security.LoginService;
import org.eclipse.jetty.server.RequestLog;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ShutdownMonitor;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.resource.ResourceFactory;

/**
 * JettyEmbedded
 * 
 * Starts jetty within the current process. 
 */
public class JettyEmbedder extends AbstractLifeCycle
{
    protected List<ContextHandler> contextHandlers;
    protected List<LoginService> loginServices;
    protected RequestLog requestLog;
    protected MavenServerConnector httpConnector;
    protected Server server;
    protected MavenWebAppContext webApp;
    protected boolean exitVm;
    protected boolean stopAtShutdown;
    protected List<File> jettyXmlFiles;
    protected Map<String, String> jettyProperties;
    protected ShutdownMonitor shutdownMonitor;
    protected int stopPort;
    protected String stopKey;
    private String contextXml;
    private Properties webAppProperties;

    public List<ContextHandler> getContextHandlers()
    {
        return contextHandlers;
    }

    public void setContextHandlers(List<ContextHandler> contextHandlers)
    {
        if (contextHandlers == null)
            this.contextHandlers = null;
        else
            this.contextHandlers = new ArrayList<>(contextHandlers);
    }

    public List<LoginService> getLoginServices()
    {
        return loginServices;
    }

    public void setLoginServices(List<LoginService> loginServices)
    {
        if (loginServices == null)
            this.loginServices = null;
        else
            this.loginServices = new ArrayList<>(loginServices);
    }

    public RequestLog getRequestLog()
    {
        return requestLog;
    }

    public void setRequestLog(RequestLog requestLog)
    {
        this.requestLog = requestLog;
    }

    public MavenServerConnector getHttpConnector()
    {
        return httpConnector;
    }

    public void setHttpConnector(MavenServerConnector httpConnector)
    {
        this.httpConnector = httpConnector;
    }

    public Server getServer()
    {
        return server;
    }

    public void setServer(Server server)
    {
        this.server = server;
    }

    public MavenWebAppContext getWebApp()
    {
        return webApp;
    }

    public boolean isExitVm()
    {
        return exitVm;
    }

    public void setExitVm(boolean exitVm)
    {
        this.exitVm = exitVm;
    }

    public boolean isStopAtShutdown()
    {
        return stopAtShutdown;
    }

    public void setStopAtShutdown(boolean stopAtShutdown)
    {
        this.stopAtShutdown = stopAtShutdown;
    }

    public List<File> getJettyXmlFiles()
    {
        return jettyXmlFiles;
    }

    public void setJettyXmlFiles(List<File> jettyXmlFiles)
    {
        this.jettyXmlFiles = jettyXmlFiles;
    }

    public Map<String, String> getJettyProperties()
    {
        return jettyProperties;
    }

    public void setJettyProperties(Map<String, String> jettyProperties)
    {
        this.jettyProperties = jettyProperties;
    }

    public ShutdownMonitor getShutdownMonitor()
    {
        return shutdownMonitor;
    }

    public void setShutdownMonitor(ShutdownMonitor shutdownMonitor)
    {
        this.shutdownMonitor = shutdownMonitor;
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
    
    public void setWebApp(MavenWebAppContext app) throws Exception
    {
        webApp = app;
    }
    
    public void setWebAppProperties(Properties props)
    {
        if (webAppProperties != null)
            webAppProperties.clear();

        if (props != null)
        {
            if (webAppProperties == null)
                webAppProperties = new Properties();

            webAppProperties.putAll(props);
        }
    }
    
    public String getContextXml()
    {
        return contextXml;
    }

    public void setContextXml(String contextXml)
    {
        this.contextXml = contextXml;
    }
    
    public void doStart() throws Exception
    {
        super.doStart();

        configure();
        configureShutdownMonitor();
        server.start();
    }
    
    protected void redeployWebApp() throws Exception
    {
        if (!webApp.isStopped())
            webApp.stop();
        
        //regenerate config properties
        applyWebAppProperties();

        webApp.start();
    }
    
    protected void join() throws InterruptedException
    {
        server.join();
    }

    /**
     * Configure the server and the webapp
     * @throws Exception
     */
    private void configure() throws Exception
    {
        // apply any configs from jetty.xml files first
        Server tmp = ServerSupport.applyXmlConfigurations(new Server(), jettyXmlFiles, jettyProperties);

        if (tmp != null)
            server = tmp;

        server.setStopAtShutdown(stopAtShutdown);

        //ensure there's a connector
        if (httpConnector != null)
            httpConnector.setServer(server);

        ServerSupport.configureConnectors(server, httpConnector, jettyProperties);

        //set up handler structure
        ServerSupport.configureHandlers(server, contextHandlers, requestLog);

        //Set up list of default Configurations to apply to a webapp
        ServerSupport.configureDefaultConfigurationClasses(server);

        // set up security realms
        ServerSupport.configureLoginServices(server, loginServices);

        /* Configure the webapp */
        if (webApp == null)
            webApp = new MavenWebAppContext();

        applyWebAppProperties();

        //If there is a quickstart file, then quickstart the webapp.
        if (webApp.getTempDirectory() != null)
        {
            Path qs = webApp.getTempDirectory().toPath().resolve("quickstart-web.xml");
            if (Files.exists(qs) && Files.isRegularFile(qs))
            {
                webApp.setAttribute(QuickStartConfiguration.QUICKSTART_WEB_XML, ResourceFactory.of(webApp).newResource(qs));
                webApp.addConfiguration(new MavenQuickStartConfiguration());
                webApp.setAttribute(QuickStartConfiguration.MODE, Mode.QUICKSTART);
            }
        }

        //add the webapp to the server
        ServerSupport.addWebApplication(server, webApp);
    }
    
    private void applyWebAppProperties() throws Exception
    {
        //apply properties to the webapp if there are any
        if (contextXml != null)
        {
            if (webAppProperties == null)
                webAppProperties = new Properties();

            webAppProperties.put("context.xml", contextXml);
        }
        WebAppPropertyConverter.fromProperties(webApp, webAppProperties, server, jettyProperties);    
    }
    
    private void configureShutdownMonitor()
    {
        if (stopPort > 0 && stopKey != null)
        {
            ShutdownMonitor monitor = ShutdownMonitor.getInstance();
            monitor.setPort(stopPort);
            monitor.setKey(stopKey);
            monitor.setExitVm(exitVm);
        }
    }
}
