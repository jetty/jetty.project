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

package org.eclipse.jetty.ee9.maven.plugin;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

import org.eclipse.jetty.util.Scanner;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JettyForkedChild
 *
 * This is the class that is executed when the jetty maven plugin 
 * forks a process when DeploymentMode=FORKED.
 */
public class JettyForkedChild extends ContainerLifeCycle
{
    private static final Logger LOG = LoggerFactory.getLogger(JettyForkedChild.class);
    
    protected JettyEmbedder jetty;
    protected File tokenFile; // TODO: convert to Path
    protected Scanner scanner;
    protected File webAppPropsFile; // TODO: convert to Path

    /**
     * @param args arguments that were passed to main
     * @throws Exception
     */
    public JettyForkedChild(String[] args)
        throws Exception
    {
        jetty = new JettyEmbedder();
        configure(args);
    }

    /**
     * Based on the args passed to the program, configure jetty.
     * 
     * @param args args that were passed to the program.
     * @throws Exception
     */
    public void configure(String[] args)
        throws Exception
    {
        Map<String, String> jettyProperties = new HashMap<>();
        
        for (int i = 0; i < args.length; i++)
        {
            //--stop-port
            if ("--stop-port".equals(args[i]))
            {
                jetty.setStopPort(Integer.parseInt(args[++i]));
                continue;
            }

            //--stop-key
            if ("--stop-key".equals(args[i]))
            {
                jetty.setStopKey(args[++i]);
                continue;
            }

            //--jettyXml
            if ("--jetty-xml".equals(args[i]))
            {
                List<File> jettyXmls = new ArrayList<>();
                String[] names = StringUtil.csvSplit(args[++i]);
                for (int j = 0; names != null && j < names.length; j++)
                {
                    jettyXmls.add(new File(names[j].trim()));
                }
                jetty.setJettyXmlFiles(jettyXmls);
                continue;
            }
            //--webprops
            if ("--webprops".equals(args[i]))
            {
                webAppPropsFile = new File(args[++i].trim());
                jetty.setWebAppProperties(loadWebAppProps());
                continue;
            }
            
            //--token
            if ("--token".equals(args[i]))
            {
                tokenFile = new File(args[++i].trim()); 
                continue;
            }

            if ("--scan".equals(args[i]))
            {
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
                                    jetty.setWebAppProperties(loadWebAppProps());
                                    jetty.setWebApp(jetty.getWebApp());
                                    //restart the webapp
                                    jetty.redeployWebApp();

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
            }

            //assume everything else is a jetty property to be passed in
            String[] tmp = args[i].trim().split("=");
            if (tmp.length == 2)
            {
                jettyProperties.put(tmp[0], tmp[1]);
            }
        }

        jetty.setJettyProperties(jettyProperties);
        jetty.setExitVm(true);
    }

    /**
     * Load properties from a file describing the webapp if one is
     * present.
     * 
     * @return file contents as properties
     * @throws FileNotFoundException
     * @throws IOException
     */
    private Properties loadWebAppProps() throws FileNotFoundException, IOException
    {
        Properties props = new Properties();
        if (Objects.nonNull(webAppPropsFile))
            props.load(new FileInputStream(webAppPropsFile));
        return props;
    }

    /**
     * Start a jetty instance and webapp. This thread will
     * wait until jetty exits.
     */
    public void doStart()
        throws Exception
    {
        super.doStart();

        //Start the embedded jetty instance
        jetty.start();

        //touch file to signify start of jetty
        Path tokenPath = tokenFile.toPath();
        Files.createFile(tokenPath);

        //Start a watcher on a file that will change if the
        //webapp is regenerated; stop the webapp, apply the
        //properties and restart it.
        if (scanner != null)
            scanner.start();

        //wait for jetty to finish
        jetty.join();
    }

    public static void main(String[] args)
        throws Exception
    {
        if (args == null)
            System.exit(1);

        JettyForkedChild child = new JettyForkedChild(args);
        child.start();
    }
}
