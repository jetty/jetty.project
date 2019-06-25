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
import org.eclipse.jetty.ant.types.Connector;
import org.eclipse.jetty.ant.types.Connectors;
import org.eclipse.jetty.ant.types.ContextHandlers;
import org.eclipse.jetty.ant.types.LoginServices;
import org.eclipse.jetty.ant.types.SystemProperties;
import org.eclipse.jetty.ant.utils.TaskLog;
import org.eclipse.jetty.security.LoginService;
import org.eclipse.jetty.server.RequestLog;
import org.eclipse.jetty.webapp.WebAppContext;

/**
 * Ant task for running a Jetty server.
 */
public class JettyRunTask extends Task
{
    private int scanIntervalSeconds;

    /**
     * Temporary files directory.
     */
    private File tempDirectory;

    /**
     * List of web applications to be deployed.
     */
    private List<AntWebAppContext> webapps = new ArrayList<>();

    /**
     * Location of jetty.xml file.
     */
    private File jettyXml;

    /**
     * List of server connectors.
     */
    private Connectors connectors = null;

    /**
     * Server request logger object.
     */
    private RequestLog requestLog;

    /**
     * List of login services.
     */
    private LoginServices loginServices;

    /**
     * List of system properties to be set.
     */
    private SystemProperties systemProperties;

    /**
     * List of other contexts to deploy
     */
    private ContextHandlers contextHandlers;

    /**
     * Port Jetty will use for the default connector
     */
    private int jettyPort = 8080;

    private int stopPort;

    private String stopKey;

    private boolean daemon;

    public JettyRunTask()
    {
        TaskLog.setTask(this);
    }

    /**
     * Creates a new <code>WebApp</code> Ant object.
     *
     * @param webapp the webapp context
     */
    public void addWebApp(AntWebAppContext webapp)
    {
        webapps.add(webapp);
    }

    /**
     * Adds a new Ant's connector tag object if it have not been created yet.
     *
     * @param connectors the connectors
     */
    public void addConnectors(Connectors connectors)
    {
        if (this.connectors != null)
            throw new BuildException("Only one <connectors> tag is allowed!");
        this.connectors = connectors;
    }

    public void addLoginServices(LoginServices services)
    {
        if (this.loginServices != null)
            throw new BuildException("Only one <loginServices> tag is allowed!");
        this.loginServices = services;
    }

    public void addSystemProperties(SystemProperties systemProperties)
    {
        if (this.systemProperties != null)
            throw new BuildException("Only one <systemProperties> tag is allowed!");
        this.systemProperties = systemProperties;
    }

    public void addContextHandlers(ContextHandlers handlers)
    {
        if (this.contextHandlers != null)
            throw new BuildException("Only one <contextHandlers> tag is allowed!");
        this.contextHandlers = handlers;
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
            this.requestLog = (RequestLog)Class.forName(className).getDeclaredConstructor().newInstance();
        }
        catch (ClassNotFoundException e)
        {
            throw new BuildException("Unknown request logger class: " + className);
        }
        catch (Exception e)
        {
            throw new BuildException("Request logger instantiation exception: " + e);
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
     * @throws BuildException if unable to build
     */
    @Override
    public void execute() throws BuildException
    {

        TaskLog.log("Configuring Jetty for project: " + getProject().getName());

        setSystemProperties();

        List<Connector> connectorsList = null;

        if (connectors != null)
            connectorsList = connectors.getConnectors();
        else
            connectorsList = new Connectors(jettyPort, 30000).getDefaultConnectors();

        List<LoginService> loginServicesList = (loginServices != null ? loginServices.getLoginServices() : new ArrayList<LoginService>());
        ServerProxyImpl server = new ServerProxyImpl();
        server.setConnectors(connectorsList);
        server.setLoginServices(loginServicesList);
        server.setRequestLog(requestLog);
        server.setJettyXml(jettyXml);
        server.setDaemon(daemon);
        server.setStopPort(stopPort);
        server.setStopKey(stopKey);
        server.setContextHandlers(contextHandlers);
        server.setTempDirectory(tempDirectory);
        server.setScanIntervalSecs(scanIntervalSeconds);

        try
        {
            for (WebAppContext webapp : webapps)
            {
                server.addWebApplication((AntWebAppContext)webapp);
            }
        }
        catch (Exception e)
        {
            throw new BuildException(e);
        }

        server.start();
    }

    public int getStopPort()
    {
        return stopPort;
    }

    public void setStopPort(int stopPort)
    {
        this.stopPort = stopPort;
        TaskLog.log("stopPort=" + stopPort);
    }

    public String getStopKey()
    {
        return stopKey;
    }

    public void setStopKey(String stopKey)
    {
        this.stopKey = stopKey;
        TaskLog.log("stopKey=" + stopKey);
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
        TaskLog.log("Daemon=" + daemon);
    }

    public int getScanIntervalSeconds()
    {
        return scanIntervalSeconds;
    }

    public void setScanIntervalSeconds(int secs)
    {
        scanIntervalSeconds = secs;
        TaskLog.log("scanIntervalSecs=" + secs);
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
                Property property = ((Property)propertiesIterator.next());
                SystemProperties.setIfNotSetAlready(property);
            }
        }
    }
}
