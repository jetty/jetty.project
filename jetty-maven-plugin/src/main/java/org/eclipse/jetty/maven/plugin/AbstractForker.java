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
import java.util.List;
import java.util.Map;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * AbstractForker
 *
 * Base class for forking jetty.
 */
public abstract class AbstractForker
{
    private static final Logger LOG = Log.getLogger(AbstractForker.class);
    
    protected Map<String,String> env;
    
    protected String jvmArgs;
    
    protected boolean exitVm;
    
    protected boolean stopAtShutdown;
    
    protected List<File> jettyXmlFiles;
    
    protected Map<String,String> jettyProperties;
    
    protected int stopPort;
    
    protected String stopKey;
    
    protected File jettyOutputFile;
    
    protected boolean waitForChild;
    
    protected int maxChildChecks;
    
    protected long maxChildCheckInterval;
    
    protected File tokenFile;
    
    protected File workDir;
    

    public File getWorkDir()
    {
        return workDir;
    }

    public void setWorkDir(File workDir)
    {
        this.workDir = workDir;
    }

    public Map<String, String> getEnv()
    {
        return env;
    }

    public void setEnv(Map<String, String> env)
    {
        this.env = env;
    }

    public String getJvmArgs()
    {
        return jvmArgs;
    }

    public void setJvmArgs(String jvmArgs)
    {
        this.jvmArgs = jvmArgs;
    }

    public boolean isExitVm()
    {
        return exitVm;
    }

    public void setExitVm(boolean exitVm)
    {
        this.exitVm = exitVm;
    }

    public boolean isStopAtShutdown()
    {
        return stopAtShutdown;
    }

    public void setStopAtShutdown(boolean stopAtShutdown)
    {
        this.stopAtShutdown = stopAtShutdown;
    }

    public List<File> getJettyXmlFiles()
    {
        return jettyXmlFiles;
    }

    public void setJettyXmlFiles(List<File> jettyXmlFiles)
    {
        this.jettyXmlFiles = jettyXmlFiles;
    }

    public Map<String, String> getJettyProperties()
    {
        return jettyProperties;
    }

    public void setJettyProperties(Map<String, String> jettyProperties)
    {
        this.jettyProperties = jettyProperties;
    }

    public int getStopPort()
    {
        return stopPort;
    }

    public void setStopPort(int stopPort)
    {
        this.stopPort = stopPort;
    }

    public String getStopKey()
    {
        return stopKey;
    }

    public void setStopKey(String stopKey)
    {
        this.stopKey = stopKey;
    }

    public File getJettyOutputFile()
    {
        return jettyOutputFile;
    }

    public void setJettyOutputFile(File jettyOutputFile)
    {
        this.jettyOutputFile = jettyOutputFile;
    }

    public boolean isWaitForChild()
    {
        return waitForChild;
    }

    public void setWaitForChild(boolean waitForChild)
    {
        this.waitForChild = waitForChild;
    }

    public int getMaxChildChecks()
    {
        return maxChildChecks;
    }

    public void setMaxChildChecks(int maxChildChecks)
    {
        this.maxChildChecks = maxChildChecks;
    }

    public long getMaxChildCheckInterval()
    {
        return maxChildCheckInterval;
    }

    public void setMaxChildCheckInterval(long maxChildCheckInterval)
    {
        this.maxChildCheckInterval = maxChildCheckInterval;
    }

    public File getTokenFile()
    {
        return tokenFile;
    }

    public void setTokenFile(File tokenFile)
    {
        this.tokenFile = tokenFile;
    }

    public abstract ProcessBuilder  createCommand();
    

    public void start ()
    throws Exception
    {
        //Create the command to fork
        ProcessBuilder command = createCommand();
        Process process = command.start();
        
        System.err.println("Started command");

        if (waitForChild)
        {
            //keep executing until the child dies
            System.err.println("Waiting for child");
            process.waitFor();
        }
        else
        {
            System.err.println("NOT WAITING FOR CHILD");
            //just wait until the child has started successfully
            int attempts = maxChildChecks;
            while (!tokenFile.exists() && attempts > 0)
            {
                Thread.currentThread().sleep(maxChildCheckInterval);
                --attempts;
            }
            if (attempts <=0 )
                LOG.info("Couldn't verify success of child startup");
        }
    }
}
