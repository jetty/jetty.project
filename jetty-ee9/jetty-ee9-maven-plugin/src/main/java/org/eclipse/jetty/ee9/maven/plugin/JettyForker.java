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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.jetty.server.Server;

/**
 * JettyForker
 *
 * Uses quickstart to generate a webapp and forks a process to run it.
 */
public class JettyForker extends AbstractForker
{
    protected File forkWebXml;
    protected Server server;
    protected MavenWebAppContext webApp;
    protected String containerClassPath;
    protected File webAppPropsFile;
    protected String contextXml; 
    protected boolean scan;
    QuickStartGenerator generator;

    /**
     * @return the scan
     */
    public boolean isScan()
    {
        return scan;
    }

    /**
     * @param scan if true, the forked child will scan for changes
     */
    public void setScan(boolean scan)
    {
        this.scan = scan;
    }

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

    public void setWebApp(MavenWebAppContext app)
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
    public void doStart()
        throws Exception
    {
        //Run the webapp to create the quickstart file and properties file
        generator = new QuickStartGenerator(forkWebXml.toPath(), webApp);
        generator.setContextXml(contextXml);
        generator.setWebAppPropsFile(webAppPropsFile.toPath());
        generator.setServer(server);
        generator.generate();

        super.doStart();
    }

    protected void redeployWebApp()
        throws Exception 
    {
        //regenerating the quickstart will be noticed by the JettyForkedChild process
        //which will redeploy the webapp
        generator.generate();
    }
 
    public ProcessBuilder createCommand()
    {
        List<String> cmd = new ArrayList<String>();
        cmd.add(getJavaBin());
        
        if (jvmArgs != null)
        {
            String[] args = jvmArgs.split(" ");
            for (int i = 0; args != null && i < args.length; i++)
            {
                if (args[i] != null && !"".equals(args[i]))
                    cmd.add(args[i].trim());
            }
        }     

        if (systemProperties != null)
        {
            for (Map.Entry<String, String> e:systemProperties.entrySet())
            {
                cmd.add("-D" + e.getKey() + "=" + e.getValue());
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
                if (tmp.length() != 0)
                    tmp.append(",");
                tmp.append(jettyXml.getAbsolutePath());
            }
            cmd.add(tmp.toString());
        }

        cmd.add("--webprops");
        cmd.add(webAppPropsFile.getAbsolutePath());

        cmd.add("--token");
        cmd.add(tokenFile.getAbsolutePath());

        if (scan)
        {
            cmd.add("--scan");
        }
        
        if (jettyProperties != null)
        {
            for (Map.Entry<String, String> e:jettyProperties.entrySet())
            {
                cmd.add(e.getKey() + "=" + e.getValue());
            }
        }
        
        ProcessBuilder command = new ProcessBuilder(cmd);
        command.directory(workDir);

        if (PluginLog.getLog().isDebugEnabled())
            PluginLog.getLog().debug("Forked cli:" + command.command());

        PluginLog.getLog().info("Forked process starting");

        //set up extra environment vars if there are any
        if (env != null && !env.isEmpty())
            command.environment().putAll(env);

        if (waitForChild)
        {
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
     * @return the location of the java binary
     */
    private String getJavaBin()
    {
        String[] javaexes = new String[]{"java", "java.exe"};

        File javaHomeDir = new File(System.getProperty("java.home"));
        for (String javaexe : javaexes)
        {
            File javabin = new File(javaHomeDir, fileSeparators("bin/" + javaexe));
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
