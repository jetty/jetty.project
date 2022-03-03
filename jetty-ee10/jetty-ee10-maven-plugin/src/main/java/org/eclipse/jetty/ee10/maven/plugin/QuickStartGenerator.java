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

import org.eclipse.jetty.ee10.annotations.AnnotationConfiguration;
import org.eclipse.jetty.ee10.quickstart.QuickStartConfiguration;
import org.eclipse.jetty.ee10.quickstart.QuickStartConfiguration.Mode;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.thread.QueuedThreadPool;

/**
 * Run enough of jetty in order to generate a quickstart file for a
 * webapp. Optionally, some essential elements of the WebAppContext
 * configuration can also be converted to properties and saved to
 * a file after the quickstart generation.
 *
 */
public class QuickStartGenerator
{
    private File quickstartXml;
    private MavenWebAppContext webApp;
    private File webAppPropsFile;
    private String contextXml;
    private boolean prepared = false;
    private Server server;
    private QueuedThreadPool tpool;

    /**
     * @param quickstartXml the file to generate quickstart into
     * @param webApp the webapp for which to generate quickstart
     */
    public QuickStartGenerator(File quickstartXml, MavenWebAppContext webApp)
    {
        this.quickstartXml = quickstartXml;
        this.webApp = webApp;
    }

    /**
     * @return the webApp
     */
    public MavenWebAppContext getWebApp()
    {
        return webApp;
    }

    /**
     * @return the quickstartXml
     */
    public File getQuickstartXml()
    {
        return quickstartXml;
    }
    
    /**
     * @return the server
     */
    public Server getServer()
    {
        return server;
    }

    /**
     * @param server the server to use
     */
    public void setServer(Server server)
    {
        this.server = server;
    }

    public File getWebAppPropsFile()
    {
        return webAppPropsFile;
    }

    /**
     * @param webAppPropsFile properties file describing the webapp
     */
    public void setWebAppPropsFile(File webAppPropsFile)
    {
        this.webAppPropsFile = webAppPropsFile;
    }
    
    public String getContextXml()
    {
        return contextXml;
    }

    /**
     * @param contextXml a context xml file to apply to the webapp
     */
    public void setContextXml(String contextXml)
    {
        this.contextXml = contextXml;
    }
    
    /**
     * Configure the webapp in preparation for quickstart generation.
     * 
     * @throws Exception
     */
    private void prepareWebApp()
        throws Exception
    {
        if (webApp == null)
            webApp = new MavenWebAppContext();

        //set the webapp up to do very little other than generate the quickstart-web.xml
        webApp.addConfiguration(new MavenQuickStartConfiguration());
        webApp.setAttribute(QuickStartConfiguration.MODE, Mode.GENERATE);
        webApp.setAttribute(QuickStartConfiguration.QUICKSTART_WEB_XML, Resource.newResource(quickstartXml));
        webApp.setAttribute(QuickStartConfiguration.ORIGIN_ATTRIBUTE, "o");
        webApp.setCopyWebDir(false);
        webApp.setCopyWebInf(false);
    }

    /**
     * Run enough of jetty to generate a full quickstart xml file for the 
     * webapp. The tmp directory is persisted.
     * 
     * @throws Exception
     */
    public void generate()
        throws Exception
    {
        if (quickstartXml == null)
            throw new IllegalStateException("No quickstart xml output file");

        if (!prepared)
        {
            prepared = true;
            prepareWebApp();
            
            if (server == null)
                server = new Server();

            //ensure handler structure enabled
            ServerSupport.configureHandlers(server, null, null);

            ServerSupport.configureDefaultConfigurationClasses(server);
            
            //if our server has a thread pool associated we can do annotation scanning multithreaded,
            //otherwise scanning will be single threaded
            if (tpool == null)
                tpool = server.getBean(QueuedThreadPool.class);

            //add webapp to our fake server instance
            ServerSupport.addWebApplication(server, webApp);

            //leave everything unpacked for the forked process to use
            webApp.setPersistTempDirectory(true);
        }

        try
        {
            if (tpool != null)
                tpool.start();
            else
                webApp.setAttribute(AnnotationConfiguration.MULTI_THREADED, Boolean.FALSE.toString());

            webApp.start(); //just enough to generate the quickstart

            //save config of the webapp BEFORE we stop
            if (webAppPropsFile != null)
                WebAppPropertyConverter.toProperties(webApp, webAppPropsFile, contextXml);
        }
        finally
        {
            webApp.stop();        
            if (tpool != null)
                tpool.stop();
        }
    }
}
