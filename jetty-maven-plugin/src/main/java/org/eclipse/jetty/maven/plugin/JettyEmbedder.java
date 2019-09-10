//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
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


package org.eclipse.jetty.maven.plugin;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.eclipse.jetty.quickstart.QuickStartConfiguration;
import org.eclipse.jetty.quickstart.QuickStartConfiguration.Mode;
import org.eclipse.jetty.security.LoginService;
import org.eclipse.jetty.server.RequestLog;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ShutdownMonitor;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.server.handler.HandlerCollection;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.resource.Resource;

/**
 * JettyEmbedded
 * 
 * Starts jetty within the current process. 
 *
 */
public class JettyEmbedder extends AbstractLifeCycle
{
    private static final Logger LOG = Log.getLogger(JettyEmbedder.class);
    
    
    protected ContextHandler[] contextHandlers;
    
    protected LoginService[] loginServices;

    protected RequestLog requestLog;
    
    protected MavenServerConnector httpConnector;
    
    protected Server server;
    
    protected JettyWebAppContext webApp;
    
    protected boolean exitVm;
    
    protected boolean stopAtShutdown;
    
    protected List<File> jettyXmlFiles;
    
    protected Map<String,String> jettyProperties;
    
    protected ShutdownMonitor shutdownMonitor;
    
    protected int stopPort;
    
    protected String stopKey;

    private String contextXml;


    private Properties webAppProperties;

   

    public ContextHandler[] getContextHandlers()
    {
        return contextHandlers;
    }

    public void setContextHandlers(ContextHandler[] contextHandlers)
    {
        this.contextHandlers = contextHandlers;
    }

    public LoginService[] getLoginServices()
    {
        return loginServices;
    }

    public void setLoginServices(LoginService[] loginServices)
    {
        this.loginServices = loginServices;
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

    public JettyWebAppContext getWebApp()
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
    
    public void setWebApp (JettyWebAppContext app)
    throws Exception
    {
        webApp = app;
    }
    
    public void setWebAppProperties (Properties props)
    {
        if (webAppProperties != null)
            webAppProperties.clear();
        
        if (props != null)
        {
            if (webAppProperties == null)
            {
                webAppProperties = new Properties();
                webAppProperties.putAll(props);
            }
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
    
    public void doStart()
    throws Exception
    {
        super.doStart();
        
        Resource.setDefaultUseCaches(false);
        
        configure();
   
        configureShutdownMonitor();
        
        server.start();
    }
    
    protected void redeployWebApp()
    throws Exception
    {
        if (!webApp.isStopped())
            webApp.stop();
        
        //regenerate config properties
        applyWebAppProperties();

        System.err.println("WEbapp is stopped: "+webApp.isStopped());
        webApp.start();
    }
    
    protected void join()
    throws InterruptedException
    {
        server.join();
    }
    
    
    /**
     * Configure the server and the webapp
     * @throws Exception
     */
    private void configure ()
    throws Exception
    {
        /* Configure the server */
        //apply any configs from jetty.xml files first 
        Server tmp = ServerSupport.applyXmlConfigurations(server, jettyXmlFiles, jettyProperties);
        if (server == null)
            server = tmp;

        if (server == null)
            server = new Server();

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
            webApp = new JettyWebAppContext();

        applyWebAppProperties();

        //TODO- this might be duplicating WebAppPropertyConverter. make it a quickstart if the quickstart-web.xml file exists
        if (webApp.getTempDirectory() != null)
        {
            File qs = new File (webApp.getTempDirectory(), "quickstart-web.xml");
            if (qs.exists() && qs.isFile())
            {
                webApp.setAttribute(QuickStartConfiguration.QUICKSTART_WEB_XML, Resource.newResource(qs));
                webApp.addConfiguration(new MavenQuickStartConfiguration());
                webApp.setAttribute(QuickStartConfiguration.MODE, Mode.QUICKSTART);
            }

        }

        //add the webapp to the server
        ServerSupport.addWebApplication(server, webApp);
    }
    
    private void applyWebAppProperties () throws Exception
    {
        //apply properties to the webapp if there are any
        if (contextXml != null)
        {
            if (webAppProperties == null)
            {
                webAppProperties = new Properties();
                webAppProperties.put("context.xml", contextXml);
            }
        }
        WebAppPropertyConverter.fromProperties(webApp, webAppProperties, server, jettyProperties);    
    }
    
    private void configureShutdownMonitor ()
    {
        if(stopPort>0 && stopKey!=null)
        {
            ShutdownMonitor monitor = ShutdownMonitor.getInstance();
            monitor.setPort(stopPort);
            monitor.setKey(stopKey);
            monitor.setExitVm(exitVm);
        }
    }
}
