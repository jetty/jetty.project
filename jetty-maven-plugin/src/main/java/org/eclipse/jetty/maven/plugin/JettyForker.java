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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.jetty.annotations.AnnotationConfiguration;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.thread.QueuedThreadPool;

/**
 * JettyForker
 *
 * Uses quickstart to generate a webapp and forks a process to run it.
 */
public class JettyForker extends AbstractForker
{
    private static final Logger LOG = Log.getLogger(JettyForker.class);
    
    protected File forkWebXml;
    
    protected Server server;
    
    protected JettyWebAppContext webApp;
    
    protected String containerClassPath;
    
    protected File webAppPropsFile;
    
    protected String contextXml;


    public File getWebAppPropsFile()
    {
        return webAppPropsFile;
    }


    public void setWebAppPropsFile(File webAppPropsFile)
    {
        this.webAppPropsFile = webAppPropsFile;
    }


    public File getForkWebXml()
    {
        return forkWebXml;
    }


    public void setForkWebXml(File forkWebXml)
    {
        this.forkWebXml = forkWebXml;
    }

    public String getContextXml()
    {
        return contextXml;
    }

    public void setContextXml(String contextXml)
    {
        this.contextXml = contextXml;
    }
    
    public String getContainerClassPath()
    {
        return containerClassPath;
    }


    public void setContainerClassPath(String containerClassPath)
    {
        this.containerClassPath = containerClassPath;
    }


    public void setWebApp (JettyWebAppContext app)
    {
        webApp = app;
    }

    public Server getServer()
    {
        return server;
    }


    public void setServer(Server server)
    {
        this.server = server;
    }


    @Override
    public void doStart ()
    throws Exception
    {
        //Run the webapp to create the quickstart file and properties file
        prepareWebApp();
        super.doStart();
    }
    
    
    private void prepareWebApp()
    throws Exception
    {
        if (server == null)
            server = new Server();

        //ensure handler structure enabled
        ServerSupport.configureHandlers(server, null, null);
        
        ServerSupport.configureDefaultConfigurationClasses(server);
               
        if (webApp == null)
            webApp = new JettyWebAppContext();
        
        //set the webapp up to do very little other than generate the quickstart-web.xml
        webApp.setCopyWebDir(false);
        webApp.setCopyWebInf(false);
        webApp.setGenerateQuickStart(true);

        if (webApp.getQuickStartWebDescriptor() == null)
        {
            if (!forkWebXml.getParentFile().exists())
                forkWebXml.getParentFile().mkdirs();
            if (!forkWebXml.exists())
                forkWebXml.createNewFile();

            webApp.setQuickStartWebDescriptor(Resource.newResource(forkWebXml));
        }
        
        //add webapp to our fake server instance
        ServerSupport.addWebApplication(server, webApp);
        
        //leave everything unpacked for the forked process to use
        webApp.setPersistTempDirectory(true);
        
        generateQuickStart();
    }
    
    protected void redeployWebApp ()
    throws Exception 
    {
        //regenerating the quickstart will be noticed by the JettyForkedChild process
        //which will redeploy the webapp
        generateQuickStart();
    }
    
    private void generateQuickStart()
    throws Exception
    {
        if (forkWebXml == null)
            throw new IllegalStateException ("No forkWebXml");
        
        if (webAppPropsFile == null)
            throw new IllegalStateException ("no webAppsPropsFile");
                   
        //if our server has a thread pool associated we can do annotation scanning multithreaded,
        //otherwise scanning will be single threaded
        QueuedThreadPool tpool = server.getBean(QueuedThreadPool.class);

        try
        {
            if (tpool != null)
                tpool.start();
            else
                webApp.setAttribute(AnnotationConfiguration.MULTI_THREADED, Boolean.FALSE.toString());

            webApp.start(); //just enough to generate the quickstart

            //save config of the webapp BEFORE we stop
            WebAppPropertyConverter.toProperties(webApp, webAppPropsFile, contextXml);
        }
        finally
        {
            webApp.stop();        
            if (tpool != null)
                tpool.stop();
        }
    }
    
    
    
    public ProcessBuilder createCommand ()
    {
        List<String> cmd = new ArrayList<String>();
        cmd.add(getJavaBin());
        
        if (jvmArgs != null)
        {
            String[] args = jvmArgs.split(" ");
            for (int i=0;args != null && i<args.length;i++)
            {
                if (args[i] !=null && !"".equals(args[i]))
                    cmd.add(args[i].trim());
            }
        }     

        if (containerClassPath != null && containerClassPath.length() > 0)
        {
            cmd.add("-cp");
            cmd.add(containerClassPath);
        }

        cmd.add(JettyForkedChild.class.getCanonicalName());

        if (stopPort > 0 && stopKey != null)
        {
            cmd.add("--stop-port");
            cmd.add(Integer.toString(stopPort));
            cmd.add("--stop-key");
            cmd.add(stopKey);
        }
        if (jettyXmlFiles != null)
        {
            cmd.add("--jetty-xml");
            StringBuilder tmp = new StringBuilder();
            for (File jettyXml:jettyXmlFiles)
            {
                if (tmp.length()!=0)
                    tmp.append(",");
                tmp.append(jettyXml.getAbsolutePath());
            }
            cmd.add(tmp.toString());
        }

        cmd.add("--webprops");
        cmd.add(webAppPropsFile.getAbsolutePath());

        cmd.add("--token");
        cmd.add(tokenFile.getAbsolutePath());

        if (jettyProperties != null)
        {
            for (Map.Entry<String, String> e:jettyProperties.entrySet())
            {
                cmd.add(e.getKey()+"="+e.getValue());
            }
        }

        ProcessBuilder command = new ProcessBuilder(cmd);
        command.directory(workDir);

        if (PluginLog.getLog().isDebugEnabled())
            PluginLog.getLog().debug("Forked cli:"+command.command());

        PluginLog.getLog().info("Forked process starting");

        //set up extra environment vars if there are any
        if (!env.isEmpty())
            command.environment().putAll(env);

        if (waitForChild)
        {
            System.err.println("Waiting and inheriting IO");
            command.inheritIO();
        }
        else
        {
            command.redirectOutput(jettyOutputFile);
            command.redirectErrorStream(true);
        }
        return command;
    }


    /**
     * @return
     */
    private String getJavaBin()
    {
        String javaexes[] = new String[]
        { "java", "java.exe" };

        File javaHomeDir = new File(System.getProperty("java.home"));
        for (String javaexe : javaexes)
        {
            File javabin = new File(javaHomeDir,fileSeparators("bin/" + javaexe));
            if (javabin.exists() && javabin.isFile())
            {
                return javabin.getAbsolutePath();
            }
        }

        return "java";
    }
    
    
    
    public static String fileSeparators(String path)
    {
        StringBuilder ret = new StringBuilder();
        for (char c : path.toCharArray())
        {
            if ((c == '/') || (c == '\\'))
            {
                ret.append(File.separatorChar);
            }
            else
            {
                ret.append(c);
            }
        }
        return ret.toString();
    }

    public static String pathSeparators(String path)
    {
        StringBuilder ret = new StringBuilder();
        for (char c : path.toCharArray())
        {
            if ((c == ',') || (c == ':'))
            {
                ret.append(File.pathSeparatorChar);
            }
            else
            {
                ret.append(c);
            }
        }
        return ret.toString();
    }

}
