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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ShutdownMonitor;
import org.eclipse.jetty.server.handler.HandlerCollection;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.resource.Resource;

/**
 * Starter Class which is exec'ed to create a new jetty process. Used by the JettyRunForked mojo.
 */
public class Starter
{
    private static final Logger LOG = Log.getLogger(Starter.class);

    private List<File> jettyXmls; // list of jetty.xml config files to apply
    private Server server;
    private JettyWebAppContext webApp;
    private Map<String, String> jettyProperties; //optional list of jetty properties to set

    private int stopPort = 0;
    private String stopKey = null;
    private File propsFile;
    private String token;

    public void configureJetty() throws Exception
    {
        LOG.debug("Starting Jetty Server ...");
        Resource.setDefaultUseCaches(false);

        //apply any configs from jetty.xml files first 
        applyJettyXml();

        //ensure there's a connector
        ServerSupport.configureConnectors(server, null);

        //check if contexts already configured, create if not
        ServerSupport.configureHandlers(server, null);

        //Set up list of default Configurations to apply to a webapp
        ServerSupport.configureDefaultConfigurationClasses(server);

        webApp = new JettyWebAppContext();

        //configure webapp from properties file describing unassembled webapp
        configureWebApp();

        //make it a quickstart if the quickstart-web.xml file exists
        if (webApp.getTempDirectory() != null)
        {
            File qs = new File(webApp.getTempDirectory(), "quickstart-web.xml");
            if (qs.exists() && qs.isFile())
                webApp.setQuickStartWebDescriptor(Resource.newResource(qs));
        }

        ServerSupport.addWebApplication(server, webApp);

        if (stopPort > 0 && stopKey != null)
        {
            ShutdownMonitor monitor = ShutdownMonitor.getInstance();
            monitor.setPort(stopPort);
            monitor.setKey(stopKey);
            monitor.setExitVm(true);
        }
    }

    public void configureWebApp()
        throws Exception
    {
        if (propsFile == null)
            return;

        //apply a properties file that defines the things that we configure in the jetty:run plugin
        WebAppPropertyConverter.fromProperties(webApp, propsFile, server, jettyProperties);
    }

    public void getConfiguration(String[] args)
        throws Exception
    {
        for (int i = 0; i < args.length; i++)
        {
            //--stop-port
            if ("--stop-port".equals(args[i]))
            {
                stopPort = Integer.parseInt(args[++i]);
                continue;
            }

            //--stop-key
            if ("--stop-key".equals(args[i]))
            {
                stopKey = args[++i];
                continue;
            }

            //--jettyXml
            if ("--jetty-xml".equals(args[i]))
            {
                jettyXmls = new ArrayList<File>();
                String[] names = StringUtil.csvSplit(args[++i]);
                for (int j = 0; names != null && j < names.length; j++)
                {
                    jettyXmls.add(new File(names[j].trim()));
                }
                continue;
            }

            //--props
            if ("--props".equals(args[i]))
            {
                propsFile = new File(args[++i].trim());
                continue;
            }

            //--token
            if ("--token".equals(args[i]))
            {
                token = args[++i].trim();
                continue;
            }

            //assume everything else is a jetty property to be passed in
            if (jettyProperties == null)
                jettyProperties = new HashMap<>();

            String[] tmp = args[i].trim().split("=");
            if (tmp.length == 2)
                jettyProperties.put(tmp[0], tmp[1]);
        }
    }

    public void run() throws Exception
    {
        LOG.info("Started Jetty Server");
        server.start();
    }

    public void join() throws Exception
    {
        server.join();
    }

    public void communicateStartupResult()
    {
        if (token != null)
        {
            try
            {
                Resource r = Resource.newResource(token);
                r.getFile().createNewFile();
            }
            catch (Exception x)
            {
                throw new IllegalStateException(x);
            }
        }
    }

    /**
     * Apply any jetty xml files given
     *
     * @throws Exception if unable to apply the xml
     */
    public void applyJettyXml() throws Exception
    {
        Server tmp = ServerSupport.applyXmlConfigurations(server, jettyXmls, jettyProperties);
        if (server == null)
            server = tmp;

        if (server == null)
            server = new Server();
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

    /**
     *
     */
    private List<String> fromCSV(String csv)
    {
        if (csv == null || "".equals(csv.trim()))
            return null;
        String[] atoms = StringUtil.csvSplit(csv);
        List<String> list = new ArrayList<String>();
        for (String a : atoms)
        {
            list.add(a.trim());
        }
        return list;
    }

    /**
     * @param args Starter arguments
     */
    public static final void main(String[] args)
    {
        if (args == null)
            System.exit(1);

        Starter starter = null;
        try
        {
            starter = new Starter();
            starter.getConfiguration(args);
            starter.configureJetty();
            starter.run();
            starter.communicateStartupResult();
            starter.join();
        }
        catch (Exception e)
        {
            e.printStackTrace();
            System.exit(1);
        }
    }
}
