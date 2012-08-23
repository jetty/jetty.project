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
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.Task;
import org.apache.tools.ant.taskdefs.Property;
import org.eclipse.jetty.ant.types.Connectors;
import org.eclipse.jetty.ant.types.LoginServices;
import org.eclipse.jetty.ant.types.SystemProperties;
import org.eclipse.jetty.ant.types.WebApp;
import org.eclipse.jetty.ant.utils.ServerProxy;
import org.eclipse.jetty.ant.utils.TaskLog;
import org.eclipse.jetty.server.RequestLog;
import org.eclipse.jetty.util.Scanner;

/**
 * Ant task for running a Jetty server.
 *
 * @author Jakub Pawlowicz
 */
public class JettyRunTask extends Task
{

    /** Temporary files directory. */
    private File tempDirectory;

    /** List of web applications to be deployed. */
    private List webapps = new ArrayList();

    /** Location of jetty.xml file. */
    private File jettyXml;

    /** List of server connectors. */
    private Connectors connectors = null;

    /** Server request logger object. */
    private RequestLog requestLog;

    /** List of login services. */
    private LoginServices loginServices;

    /** List of system properties to be set. */
    private SystemProperties systemProperties;

    /** Port Jetty will use for the default connector */
    private int jettyPort = 8080;


    public JettyRunTask()
    {
        TaskLog.setTask(this);
    }

    /**
     * Creates a new <code>WebApp</code> Ant object.
     *
     */
    public void addWebApp(WebApp webapp)
    {
        webapps.add(webapp);
    }

    /**
     * Adds a new Ant's connector tag object if it have not been created yet.
     */
    public void addConnectors(Connectors connectors)
    {
        if (this.connectors != null)
        {
            throw new BuildException("Only one <connectors> tag is allowed!");
        }

        this.connectors = connectors;
    }

    /**
     * @deprecated
     */
    public void addUserRealms(Object o)
    {
        TaskLog.log("User realms are deprecated.");
    }

    public void addLoginServices(LoginServices services)
    {        
        if (this.loginServices != null )
        {
            throw new BuildException("Only one <loginServices> tag is allowed!");
        }
        
        this.loginServices = services;  
    }

    public void addSystemProperties(SystemProperties systemProperties)
    {
        if (this.systemProperties != null)
        {
            throw new BuildException("Only one <systemProperties> tag is allowed!");
        }

        this.systemProperties = systemProperties;
    }

    public File getTempDirectory()
    {
        return tempDirectory;
    }

    public void setTempDirectory(File tempDirectory)
    {
        this.tempDirectory = tempDirectory;
    }

    public File getJettyXml()
    {
        return jettyXml;
    }

    public void setJettyXml(File jettyXml)
    {
        this.jettyXml = jettyXml;
    }

    public void setRequestLog(String className)
    {
        try
        {
            this.requestLog = (RequestLog) Class.forName(className).newInstance();
        }
        catch (InstantiationException e)
        {
            throw new BuildException("Request logger instantiation exception: " + e);
        }
        catch (IllegalAccessException e)
        {
            throw new BuildException("Request logger instantiation exception: " + e);
        }
        catch (ClassNotFoundException e)
        {
            throw new BuildException("Unknown request logger class: " + className);
        }
    }

    public String getRequestLog()
    {
        if (requestLog != null)
        {
            return requestLog.getClass().getName();
        }

        return "";
    }

    /**
     * Sets the port Jetty uses for the default connector.
     * 
     * @param jettyPort The port Jetty will use for the default connector
     */
    public void setJettyPort(final int jettyPort)
    {
        this.jettyPort = jettyPort;
    }

    /**
     * Executes this Ant task. The build flow is being stopped until Jetty
     * server stops.
     *
     * @throws BuildException
     */
    public void execute() throws BuildException
    {

        TaskLog.log("Configuring Jetty for project: " + getProject().getName());
        WebApplicationProxyImpl.setBaseTempDirectory(tempDirectory);
        setSystemProperties();

        List connectorsList = null;

        if (connectors != null)
        {
            connectorsList = connectors.getConnectors();
        }
        else
        {
            connectorsList = new Connectors(jettyPort,30000).getDefaultConnectors();
        }

        List loginServicesList = (loginServices != null?loginServices.getLoginServices():new ArrayList());
        ServerProxy server = new ServerProxyImpl(connectorsList,loginServicesList,requestLog,jettyXml);

        try
        {
            Iterator iterator = webapps.iterator();
            while (iterator.hasNext())
            {
                WebApp webAppConfiguration = (WebApp)iterator.next();
                WebApplicationProxyImpl webApp = new WebApplicationProxyImpl(webAppConfiguration.getName());
                webApp.setSourceDirectory(webAppConfiguration.getWarFile());
                webApp.setContextPath(webAppConfiguration.getContextPath());
                webApp.setWebXml(webAppConfiguration.getWebXmlFile());
                webApp.setJettyEnvXml(webAppConfiguration.getJettyEnvXml());
                webApp.setClassPathFiles(webAppConfiguration.getClassPathFiles());
                webApp.setLibrariesConfiguration(webAppConfiguration.getLibrariesConfiguration());
                webApp.setExtraScanTargetsConfiguration(webAppConfiguration.getScanTargetsConfiguration());
                webApp.setContextHandlers(webAppConfiguration.getContextHandlers());
                webApp.setAttributes(webAppConfiguration.getAttributes());
                webApp.setWebDefaultXmlFile(webAppConfiguration.getWebDefaultXmlFile());

                server.addWebApplication(webApp,webAppConfiguration.getScanIntervalSeconds());
            }
        }
        catch (Exception e)
        {
            throw new BuildException(e);
        }

        server.start();
    }

    /**
     * Starts a new thread which scans project files and automatically reloads a
     * container on any changes.
     *
     * @param scanIntervalSeconds
     *
     * @param webapp
     * @param appContext
     */
    static void startScanner(final WebApplicationProxyImpl webApp, int scanIntervalSeconds) throws Exception
    {
        List scanList = new ArrayList();
        scanList.add(webApp.getWebXmlFile());
        scanList.addAll(webApp.getLibraries());
        scanList.addAll(webApp.getExtraScanTargets());

        Scanner.Listener changeListener = new Scanner.BulkListener()
        {

            public void filesChanged(List changedFiles)
            {
                if (hasAnyFileChanged(changedFiles))
                {
                    try
                    {
                        webApp.stop();
                        webApp.applyConfiguration();
                        webApp.start();
                    }
                    catch (Exception e)
                    {
                        e.printStackTrace();
                    }
                }
            }

            /**
             * Checks if any file in this particular application has changed.
             * This is not that easy, because some applications may use the same
             * class'es directory.
             *
             * @param changedFiles list of changed files.
             * @return true if any of passed files has changed, false otherwise.
             */
            private boolean hasAnyFileChanged(List changedFiles)
            {
                Iterator changes = changedFiles.iterator();
                while (changes.hasNext())
                {
                    String className = (String) changes.next();
                    if (webApp.isFileScanned(className))
                    {
                        return true;
                    }
                }

                return false;
            }
        };

        TaskLog.log("Web application '" + webApp.getName() + "': starting scanner at interval of "
                + scanIntervalSeconds + " seconds.");

        Scanner scanner = new Scanner();
        scanner.setScanInterval(scanIntervalSeconds);
        scanner.addListener(changeListener);
        scanner.setScanDirs(scanList);
        scanner.setReportExistingFilesOnStartup(false);
        scanner.start();
    }

    /**
     * Sets the system properties.
     */
    private void setSystemProperties()
    {
        if (systemProperties != null)
        {
            Iterator propertiesIterator = systemProperties.getSystemProperties().iterator();
            while (propertiesIterator.hasNext())
            {
                Property property = ((Property) propertiesIterator.next());
                SystemProperties.setIfNotSetAlready(property);
            }
        }
    }
}
