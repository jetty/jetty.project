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

package org.eclipse.jetty.maven.plugin;

import java.io.File;
import java.util.List;
import java.util.Map;

import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AbstractForker
 *
 * Base class for forking jetty.
 */
public abstract class AbstractForker extends AbstractLifeCycle
{
    private static final Logger LOG = LoggerFactory.getLogger(AbstractForker.class);
    
    protected Map<String, String> env;
    
    protected String jvmArgs;
    
    protected boolean exitVm;
    
    protected boolean stopAtShutdown;
    
    protected List<File> jettyXmlFiles;
    
    protected Map<String, String> jettyProperties;
    
    protected int stopPort;
    
    protected String stopKey;
    
    protected File jettyOutputFile;
    
    protected boolean waitForChild;
    
    protected int maxChildStartChecks = 10; //check up to 10 times for child to start
    
    protected long maxChildStartCheckMs = 200; //wait 200ms between checks
    
    protected File tokenFile;
    
    protected File workDir;
    
    protected Map<String, String> systemProperties;
    
    protected abstract ProcessBuilder  createCommand();
    
    protected abstract void redeployWebApp() throws Exception;
    
    public File getWorkDir()
    {
        return workDir;
    }

    public void setWorkDir(File workDir)
    {
        this.workDir = workDir;
    }

    /**
     * @return the systemProperties
     */
    public Map<String, String> getSystemProperties()
    {
        return systemProperties;
    }
    
    /**
     * @param systemProperties the systemProperties to set
     */
    public void setSystemProperties(Map<String, String> systemProperties)
    {
        this.systemProperties = systemProperties;
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

    public int getMaxChildtartChecks()
    {
        return maxChildStartChecks;
    }

    public void setMaxChildStartChecks(int maxChildStartChecks)
    {
        this.maxChildStartChecks = maxChildStartChecks;
    }

    public long getMaxChildStartCheckMs()
    {
        return maxChildStartCheckMs;
    }

    public void setMaxChildStartCheckMs(long maxChildStartCheckMs)
    {
        this.maxChildStartCheckMs = maxChildStartCheckMs;
    }

    public File getTokenFile()
    {
        return tokenFile;
    }

    public void setTokenFile(File tokenFile)
    {
        this.tokenFile = tokenFile;
    }

    public void doStart()
        throws Exception
    {
        super.doStart();

        //Create the command to fork
        ProcessBuilder command = createCommand();
        Process process = command.start();
        
        if (waitForChild)
        {
            //keep executing until the child dies
            process.waitFor();
        }
        else
        {
            //just wait until the child has started successfully
            int attempts = maxChildStartChecks;
            while (!tokenFile.exists() && attempts > 0)
            {
                Thread.sleep(maxChildStartCheckMs);
                --attempts;
            }
            if (attempts <= 0)
                LOG.info("Couldn't verify success of child startup");
        }
    }
}
