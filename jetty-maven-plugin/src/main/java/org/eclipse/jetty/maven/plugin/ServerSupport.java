//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jetty.security.LoginService;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.RequestLog;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.server.handler.HandlerCollection;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.webapp.Configuration;
import org.eclipse.jetty.webapp.WebAppContext;
import org.eclipse.jetty.xml.XmlConfiguration;

/**
 * ServerSupport
 *
 * Helps configure the Server instance.
 */
public class ServerSupport
{

    public static void configureDefaultConfigurationClasses(Server server)
    {
        server.setAttribute(Configuration.ATTR, JettyWebAppContext.DEFAULT_CONFIGURATION_CLASSES);
    }

    /**
     * Set up the handler structure to receive a webapp.
     * Also put in a DefaultHandler so we get a nice page
     * than a 404 if we hit the root and the webapp's
     * context isn't at root.
     *
     * @param server the server
     * @param requestLog the request log
     * @throws Exception if unable to configure the handlers
     */
    public static void configureHandlers(Server server, RequestLog requestLog) throws Exception
    {
        if (server == null)
            throw new IllegalArgumentException("Server is null");

        DefaultHandler defaultHandler = new DefaultHandler();
        if (requestLog != null)
            server.setRequestLog(requestLog);

        ContextHandlerCollection contexts = findContextHandlerCollection(server);
        if (contexts == null)
        {
            contexts = new ContextHandlerCollection();
            HandlerCollection handlers = server.getChildHandlerByClass(HandlerCollection.class);
            if (handlers == null)
            {
                handlers = new HandlerCollection();
                server.setHandler(handlers);
                handlers.setHandlers(new Handler[]{contexts, defaultHandler});
            }
            else
            {
                handlers.addHandler(contexts);
            }
        }
    }

    /**
     * Configure at least one connector for the server
     *
     * @param server the server
     * @param connector the connector
     */
    public static void configureConnectors(Server server, Connector connector)
    {
        if (server == null)
            throw new IllegalArgumentException("Server is null");

        //if a connector is provided, use it
        if (connector != null)
        {
            server.addConnector(connector);
            return;
        }

        // if the user hasn't configured the connectors in a jetty.xml file so use a default one
        Connector[] connectors = server.getConnectors();
        if (connectors == null || connectors.length == 0)
        {
            //Make a new default connector
            MavenServerConnector tmp = new MavenServerConnector();
            //use any jetty.http.port settings provided
            String port = System.getProperty(MavenServerConnector.PORT_SYSPROPERTY, System.getProperty("jetty.port", MavenServerConnector.DEFAULT_PORT_STR));
            tmp.setPort(Integer.parseInt(port.trim()));
            tmp.setServer(server);
            server.setConnectors(new Connector[]{tmp});
        }
    }

    /**
     * Set up any security LoginServices provided.
     *
     * @param server the server
     * @param loginServices the login services
     */
    public static void configureLoginServices(Server server, LoginService[] loginServices)
    {
        if (server == null)
            throw new IllegalArgumentException("Server is null");

        if (loginServices != null)
        {
            for (LoginService loginService : loginServices)
            {
                PluginLog.getLog().debug(loginService.getClass().getName() + ": " + loginService.toString());
                server.addBean(loginService);
            }
        }
    }

    public static void addWebApplication(Server server, WebAppContext webapp) throws Exception
    {
        if (server == null)
            throw new IllegalArgumentException("Server is null");
        ContextHandlerCollection contexts = findContextHandlerCollection(server);
        if (contexts == null)
            throw new IllegalStateException("ContextHandlerCollection is null");
        contexts.addHandler(webapp);
    }

    public static ContextHandlerCollection findContextHandlerCollection(Server server)
    {
        if (server == null)
            return null;

        return server.getChildHandlerByClass(ContextHandlerCollection.class);
    }

    /**
     * Apply xml files to server instance.
     *
     * @param server the server to apply the xml to
     * @param files the list of xml files
     * @param properties list of jetty properties
     * @return the Server implementation, after the xml is applied
     * @throws Exception if unable to apply the xml configuration
     */
    public static Server applyXmlConfigurations(Server server, List<File> files, Map<String, String> properties)
        throws Exception
    {
        if (files == null || files.isEmpty())
            return server;

        Map<String, Object> lastMap = new HashMap<>();

        if (server != null)
            lastMap.put("Server", server);

        for (File xmlFile : files)
        {
            if (PluginLog.getLog() != null)
                PluginLog.getLog().info("Configuring Jetty from xml configuration file = " + xmlFile.getCanonicalPath());

            XmlConfiguration xmlConfiguration = new XmlConfiguration(Resource.toURL(xmlFile));

            //add in any properties
            if (properties != null)
            {
                for (Map.Entry<String, String> e : properties.entrySet())
                {
                    xmlConfiguration.getProperties().put(e.getKey(), e.getValue());
                }
            }

            //chain ids from one config file to another
            if (lastMap != null)
                xmlConfiguration.getIdMap().putAll(lastMap);

            //Set the system properties each time in case the config file set a new one
            Enumeration<?> ensysprop = System.getProperties().propertyNames();
            while (ensysprop.hasMoreElements())
            {
                String name = (String)ensysprop.nextElement();
                xmlConfiguration.getProperties().put(name, System.getProperty(name));
            }
            xmlConfiguration.configure();
            lastMap = xmlConfiguration.getIdMap();
        }

        return (Server)lastMap.get("Server");
    }

    /**
     * Apply xml files to server instance.
     *
     * @param server the Server instance to configure
     * @param files the xml configs to apply
     * @return the Server after application of configs
     * @throws Exception if unable to apply the xml configuration
     */
    public static Server applyXmlConfigurations(Server server, List<File> files)
        throws Exception
    {
        return applyXmlConfigurations(server, files, null);
    }
}
