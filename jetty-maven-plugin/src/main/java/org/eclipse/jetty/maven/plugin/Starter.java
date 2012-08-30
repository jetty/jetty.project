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

package org.eclipse.jetty.maven.plugin;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.HandlerCollection;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceCollection;
import org.eclipse.jetty.xml.XmlConfiguration;

public class Starter
{
    public static final String PORT_SYSPROPERTY = "jetty.port";
    private static final Logger LOG = Log.getLogger(Starter.class);

    private List<File> jettyXmls; // list of jetty.xml config files to apply - Mandatory
    private File contextXml; //name of context xml file to configure the webapp - Mandatory

    private JettyServer server;
    private JettyWebAppContext webApp;
    private Monitor monitor;

    private int stopPort=0;
    private String stopKey=null;
    private Properties props;
    private String token;



    public void configureJetty () throws Exception
    {
        LOG.debug("Starting Jetty Server ...");

        this.server = new JettyServer();

        //apply any configs from jetty.xml files first
        applyJettyXml ();

        // if the user hasn't configured a connector in the jetty.xml
        //then use a default
        Connector[] connectors = this.server.getConnectors();
        if (connectors == null|| connectors.length == 0)
        {
            //if a SystemProperty -Djetty.port=<portnum> has been supplied, use that as the default port
            connectors = new Connector[] { this.server.createDefaultConnector(server, System.getProperty(PORT_SYSPROPERTY, null)) };
            this.server.setConnectors(connectors);
        }

        //check that everything got configured, and if not, make the handlers
        HandlerCollection handlers = (HandlerCollection) server.getChildHandlerByClass(HandlerCollection.class);
        if (handlers == null)
        {
            handlers = new HandlerCollection();
            server.setHandler(handlers);
        }

        //check if contexts already configured, create if not
        this.server.configureHandlers();

        webApp = new JettyWebAppContext();

        //configure webapp from properties file describing unassembled webapp
        configureWebApp();

        //set up the webapp from the context xml file provided
        //NOTE: just like jetty:run mojo this means that the context file can
        //potentially override settings made in the pom. Ideally, we'd like
        //the pom to override the context xml file, but as the other mojos all
        //configure a WebAppContext in the pom (the <webApp> element), it is
        //already configured by the time the context xml file is applied.
        if (contextXml != null)
        {
            XmlConfiguration xmlConfiguration = new XmlConfiguration(Resource.toURL(contextXml));
            xmlConfiguration.getIdMap().put("Server",server);
            xmlConfiguration.configure(webApp);
        }

        this.server.addWebApplication(webApp);

        System.err.println("STOP PORT="+stopPort+", STOP KEY="+stopKey);
        if(stopPort>0 && stopKey!=null)
        {
            monitor = new Monitor(stopPort, stopKey, new Server[]{server}, true);
        }
    }


    public void configureWebApp ()
    throws Exception
    {
        if (props == null)
            return;

        //apply a properties file that defines the things that we configure in the jetty:run plugin:
        // - the context path
        String str = (String)props.get("context.path");
        if (str != null)
            webApp.setContextPath(str);

        // - web.xml
        str = (String)props.get("web.xml");
        if (str != null)
            webApp.setDescriptor(str);

        // - the tmp directory
        str = (String)props.getProperty("tmp.dir");
        if (str != null)
            webApp.setTempDirectory(new File(str.trim()));

        // - the base directory
        str = (String)props.getProperty("base.dir");
        if (str != null && !"".equals(str.trim()))
            webApp.setWar(str);

        // - the multiple comma separated resource dirs
        str = (String)props.getProperty("res.dirs");
        if (str != null && !"".equals(str.trim()))
        {
            ResourceCollection resources = new ResourceCollection(str);
            webApp.setBaseResource(resources);
        }

        // - overlays
        str = (String)props.getProperty("overlay.files");
        if (str != null && !"".equals(str.trim()))
        {
            List<Resource> overlays = new ArrayList<Resource>();
            String[] names = str.split(",");
            for (int j=0; names != null && j < names.length; j++)
                overlays.add(Resource.newResource("jar:"+Resource.toURL(new File(names[j].trim())).toString()+"!/"));
            webApp.setOverlays(overlays);
        }

        // - the equivalent of web-inf classes
        str = (String)props.getProperty("classes.dir");
        if (str != null && !"".equals(str.trim()))
        {
            webApp.setClasses(new File(str));
        }

        str = (String)props.getProperty("testClasses.dir");
        if (str != null && !"".equals(str.trim()))
        {
            webApp.setTestClasses(new File(str));
        }


        // - the equivalent of web-inf lib
        str = (String)props.getProperty("lib.jars");
        if (str != null && !"".equals(str.trim()))
        {
            List<File> jars = new ArrayList<File>();
            String[] names = str.split(",");
            for (int j=0; names != null && j < names.length; j++)
                jars.add(new File(names[j].trim()));
            webApp.setWebInfLib(jars);
        }

    }

    public void getConfiguration (String[] args)
    throws Exception
    {
        for (int i=0; i<args.length; i++)
        {
            //--stop-port
            if ("--stop-port".equals(args[i]))
                stopPort = Integer.parseInt(args[++i]);

            //--stop-key
            if ("--stop-key".equals(args[i]))
                stopKey = args[++i];

            //--jettyXml
            if ("--jetty-xml".equals(args[i]))
            {
                jettyXmls = new ArrayList<File>();
                String[] names = args[++i].split(",");
                for (int j=0; names!= null && j < names.length; j++)
                {
                    jettyXmls.add(new File(names[j].trim()));
                }
            }

            //--context-xml
            if ("--context-xml".equals(args[i]))
            {
                contextXml = new File(args[++i]);
            }

            //--props
            if ("--props".equals(args[i]))
            {
                File f = new File(args[++i].trim());
                props = new Properties();
                props.load(new FileInputStream(f));
            }

            //--token
            if ("--token".equals(args[i]))
            {
                token = args[++i].trim();
            }
        }
    }


    public void run() throws Exception
    {
        if (monitor != null)
            monitor.start();

        LOG.info("Started Jetty Server");
        server.start();
    }


    public void join () throws Exception
    {
        server.join();
    }


    public void communicateStartupResult (Exception e)
    {
        if (token != null)
        {
            if (e==null)
                System.out.println(token);
            else
                System.out.println(token+"\t"+e.getMessage());
        }
    }


    public void applyJettyXml() throws Exception
    {
        if (jettyXmls == null)
            return;

        for ( File xmlFile : jettyXmls )
        {
            LOG.info( "Configuring Jetty from xml configuration file = " + xmlFile.getCanonicalPath() );
            XmlConfiguration xmlConfiguration = new XmlConfiguration(Resource.toURL(xmlFile));
            xmlConfiguration.configure(this.server);
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
           starter.communicateStartupResult(null);
           starter.join();
       }
       catch (Exception e)
       {
           starter.communicateStartupResult(e);
           e.printStackTrace();
           System.exit(1);
       }

    }
}
