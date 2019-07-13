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
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

import org.eclipse.jetty.util.PathWatcher;
import org.eclipse.jetty.util.PathWatcher.PathWatchEvent;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.resource.Resource;

/**
 * JettyForkedChild
 *
 * This is the class that is executed when the jetty maven plugin 
 * forks a process when runType=forked.
 */
public class JettyForkedChild extends AbstractLifeCycle
{
    private static final Logger LOG = Log.getLogger(JettyForkedChild.class);
    
    protected JettyEmbedder jetty;
    protected File tokenFile;
    protected PathWatcher scanner;
    protected File webAppPropsFile;

    public JettyForkedChild (String[] args)
    throws Exception
    {
        jetty = new JettyEmbedder();
        configure(args);
    }
    
    
    public void configure (String[] args)
    throws Exception
    {
        Map<String,String>jettyProperties = new HashMap<>();
        
        for (int i=0; i<args.length; i++)
        {
            //--stop-port
            if ("--stop-port".equals(args[i]))
            {
                jetty.setStopPort(Integer.parseInt(args[++i]));
                System.err.println("STOP PORT"+jetty.getStopPort());
                continue;
            }

            //--stop-key
            if ("--stop-key".equals(args[i]))
            {
                jetty.setStopKey(args[++i]);
                System.err.println ("STOP KEY="+jetty.getStopKey());
                continue;
            }

            //--jettyXml
            if ("--jetty-xml".equals(args[i]))
            {
                List<File>jettyXmls = new ArrayList<>();
                String[] names = StringUtil.csvSplit(args[++i]);
                for (int j=0; names!= null && j < names.length; j++)
                {
                    jettyXmls.add(new File(names[j].trim()));
                }
                jetty.setJettyXmlFiles(jettyXmls);
                System.err.println("JETTY XMLS="+jetty.getJettyXmlFiles());
                continue;
            }
            //--webprops
            if ("--webprops".equals(args[i]))
            {
                webAppPropsFile = new File(args[++i].trim());          
                jetty.setWebApp(null, loadWebAppProps());
                System.err.println("WEBPROPS="+webAppPropsFile);
                continue;
            }
            
            //--token
            if ("--token".equals(args[i]))
            {
                tokenFile = new File(args[++i].trim()); 
                System.err.println("TOKEN FILE="+tokenFile);
                continue;
            }
            

            //assume everything else is a jetty property to be passed in
            String[] tmp = args[i].trim().split("=");
            if (tmp.length == 2)
            {
                System.err.println("Setting jetty property "+tmp[0]+"="+tmp[1]);
                jettyProperties.put(tmp[0], tmp[1]);
            }
        }

        jetty.setJettyProperties(jettyProperties);
        jetty.setExitVm(true);
    }

    private Properties loadWebAppProps () throws FileNotFoundException, IOException
    {
        Properties props = new Properties();
        if (Objects.nonNull(webAppPropsFile))
            props.load(new FileInputStream(webAppPropsFile));
        return props;
    }
    
    public void doStart()
    throws Exception
    {
        super.doStart();
        
        //Start the embedded jetty instance
        jetty.start();
        
        //touch file to signify start of jetty
        Resource r = Resource.newResource(tokenFile);
        r.getFile().createNewFile();
        
        //Start a watcher on a file that will change if the
        //webapp is regenerated; stop the webapp, apply the
        //properties and restart it.
        scanner = new PathWatcher();
        scanner.setNotifyExistingOnStart(false);
        scanner.addListener(new PathWatcher.EventListListener()
        {
            @Override
            public void onPathWatchEvents(List<PathWatchEvent> events)
            {
                if (!Objects.isNull(scanner))
                {
                    try
                    {
                    scanner.stop();
                    if (!Objects.isNull(jetty.getWebApp()))
                    {
                        //stop the webapp
                        jetty.getWebApp().stop();

                        //reload the props
                        jetty.setWebApp(jetty.getWebApp(), loadWebAppProps());
                        
                        //restart the webapp
                        jetty.getWebApp().start();

                        //restart the scanner
                        scanner.start();
                    }
                    }
                    catch (Exception e)
                    {
                        LOG.warn("Error restarting webapp", e);
                    }
                }
            }
        });
        
        if (!Objects.isNull(webAppPropsFile))
            scanner.watch(webAppPropsFile.toPath());
        
        
        scanner.start();
        
        //wait for jetty to finish
        jetty.join();
    }
    
    

    /**
     * @param args
     * @throws Exception 
     */
    public static void main(String[] args)
    throws Exception
    {
        if (args == null)
            System.exit(1);

        JettyForkedChild child = new JettyForkedChild(args);
        child.start();
    }

}
