//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ShutdownMonitor;
import org.eclipse.jetty.server.handler.HandlerCollection;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceCollection;
import org.eclipse.jetty.xml.XmlConfiguration;



/**
 * Starter Class which is exec'ed to create a new jetty process. Used by the JettyRunForked mojo.
 */
public class Starter
{ 
    private static final Logger LOG = Log.getLogger(Starter.class);

    private List<File> jettyXmls; // list of jetty.xml config files to apply

    private Server server;
    private JettyWebAppContext webApp;

    
    private int stopPort=0;
    private String stopKey=null;
    private Properties props;
    private String token;


    
    public void configureJetty () throws Exception
    {
        LOG.debug("Starting Jetty Server ...");
        Resource.setDefaultUseCaches(false);
        
        //apply any configs from jetty.xml files first 
        applyJettyXml ();

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
            File qs = new File (webApp.getTempDirectory(), "quickstart-web.xml");
            if (qs.exists() && qs.isFile())
                webApp.setQuickStartWebDescriptor(Resource.newResource(qs));
        }
        


        ServerSupport.addWebApplication(server, webApp);

        if(stopPort>0 && stopKey!=null)
        {
            ShutdownMonitor monitor = ShutdownMonitor.getInstance();
            monitor.setPort(stopPort);
            monitor.setKey(stopKey);
            monitor.setExitVm(true);
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
        
        str = (String)props.get("quickstart.web.xml");
        if (str != null)
        {
            webApp.setQuickStartWebDescriptor(Resource.newResource(new File(str)));
            webApp.setConfigurationClasses(JettyWebAppContext.QUICKSTART_CONFIGURATION_CLASSES);
        }
        
        // - the tmp directory
        str = (String)props.getProperty("tmp.dir");
        if (str != null)
            webApp.setTempDirectory(new File(str.trim()));

        str = (String)props.getProperty("tmp.dir.persist");
        if (str != null)
            webApp.setPersistTempDirectory(Boolean.valueOf(str));
        
        //Get the calculated base dirs which includes the overlays
        str = (String)props.getProperty("base.dirs");
        if (str != null && !"".equals(str.trim()))
        {
            ResourceCollection bases = new ResourceCollection(StringUtil.csvSplit(str));
            webApp.setWar(null);
            webApp.setBaseResource(bases);
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
            String[] names = StringUtil.csvSplit(str);
            for (int j=0; names != null && j < names.length; j++)
                jars.add(new File(names[j].trim()));
            webApp.setWebInfLib(jars);
        }
        
        //set up the webapp from the context xml file provided
        //NOTE: just like jetty:run mojo this means that the context file can
        //potentially override settings made in the pom. Ideally, we'd like
        //the pom to override the context xml file, but as the other mojos all
        //configure a WebAppContext in the pom (the <webApp> element), it is 
        //already configured by the time the context xml file is applied.
        str = (String)props.getProperty("context.xml");
        if (!StringUtil.isBlank(str))
        {
            XmlConfiguration xmlConfiguration = new XmlConfiguration(Resource.newResource(str).getURI().toURL());
            xmlConfiguration.getIdMap().put("Server",server);
            xmlConfiguration.configure(webApp);
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
                String[] names = StringUtil.csvSplit(args[++i]);
                for (int j=0; names!= null && j < names.length; j++)
                {
                    jettyXmls.add(new File(names[j].trim()));
                }  
            }

            //--props
            if ("--props".equals(args[i]))
            {
                File f = new File(args[++i].trim());
                props = new Properties();
                try (InputStream in = new FileInputStream(f))
                {
                    props.load(in);
                }
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
    
    
    /**
     * Apply any jetty xml files given
     * @throws Exception if unable to apply the xml
     */
    public void applyJettyXml() throws Exception
    {
        Server tmp = ServerSupport.applyXmlConfigurations(server, jettyXmls);
        if (server == null)
            server = tmp;
        
        if (server == null)
            server = new Server();
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

    /**
     * @param csv
     * @return
     */
    private List<String> fromCSV (String csv)
    {
        if (csv == null || "".equals(csv.trim()))
            return null;
        String[] atoms = StringUtil.csvSplit(csv);
        List<String> list = new ArrayList<String>();
        for (String a:atoms)
        {
            list.add(a.trim());
        }
        return list;
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
