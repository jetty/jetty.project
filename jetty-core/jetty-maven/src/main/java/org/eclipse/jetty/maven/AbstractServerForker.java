//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.maven;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.jetty.server.Server;

/**
 * AbstractServerForker
 *
 * Fork a jetty Server
 */
public abstract class AbstractServerForker extends AbstractForker
{
    protected File forkWebXml;
    protected Server server;
    protected String containerClassPath;
    protected File webAppPropsFile;
    protected String contextXml; 
    protected int scanInterval;
    protected String executionClassName;

    protected AbstractServerForker(String javaPath)
    {
        super(javaPath);
    }

    /**
     * @return the scan
     */
    public boolean isScan()
    {
        return scanInterval > 0;
    }

    /**
     * Set if true, the forked child will scan for changes at 1 second intervals.
     * @param scan if true, the forked child will scan for changes at 1 second intervals
     */
    public void setScan(boolean scan)
    {
        setScanInterval(scan ? 1 : 0);
    }

    public int getScanInterval()
    {
        return scanInterval;
    }

    public void setScanInterval(int sec)
    {
        scanInterval = sec;
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
        generateWebApp();
        super.doStart();
    }

    protected abstract void generateWebApp() throws Exception;

    protected abstract void redeployWebApp() throws Exception;

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

        cmd.add(executionClassName);

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

        if (scanInterval > 0)
        {
            cmd.add("--scanInterval");
            cmd.add(Integer.toString(scanInterval));
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
}
