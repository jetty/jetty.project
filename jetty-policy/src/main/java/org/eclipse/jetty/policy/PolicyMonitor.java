//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.policy;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jetty.policy.loader.DefaultPolicyLoader;
import org.eclipse.jetty.util.Scanner;
import org.eclipse.jetty.util.component.AbstractLifeCycle;

/**
 * PolicyMonitor watches a directory for files ending in the *.policy extension,
 * loads them and detects when they change.  PolicyGrants are peeped out the
 * onPolicyChange method to whoever is using this monitor.
 *
 */
public abstract class PolicyMonitor extends AbstractLifeCycle
{    

    /** 
     * the directory to be scanned for policy files.
     */
    private String _policyDirectory;
    
    /** 
     * instance of the scanner that detects policy files
     */
    private Scanner _scanner;

    /** 
     * true if updates to policy grants will be pushed through the 
     * onPolicyChange() method
     */
    private boolean _reload = true;
    
    /**
     * scan interval in seconds for policy file changes
     */
    private int _scanInterval = 1;
            
    /**
     * specialized listener enabling waitForScan() functionality
     */
    private LatchScannerListener _scanningListener;
    
    /**
     * true if the scanner has completed one cycle.
     */
    private boolean _initialized = false;
        
    /**
     * record of the number of scans that have been made
     */
    private AtomicInteger _scanCount = new AtomicInteger(0);
    
    /**
     * empty constructor
     */
    public PolicyMonitor()
    {
        
    }
    
    /**
     * construtor with a predetermined directory to monitor
     * 
     * @param directory
     */
    public PolicyMonitor( String directory )
    {
        this();
        _policyDirectory = directory;
    }
    
    /**
     * set the policy directory to scan on a non-running monitor
     * 
     * @param directory
     */
    public void setPolicyDirectory( String directory )
    {
        if (isRunning())
        {
            throw new PolicyException("policy monitor is running, unable to set policy directory");
        }
        
        _policyDirectory = directory;
    }
    
    /**
     * gets the scanner interval
     * 
     * @return the scan interval
     */
    public int getScanInterval()
    {
        return _scanInterval;
    }
    
    /**
     * sets the scanner interval on a non-running instance of the monitor
     * 
     * @param scanInterval in seconds
     * @see Scanner#setScanInterval(int)
     */
    public void setScanInterval( int scanInterval )
    {
        if (isRunning())
        {
            throw new PolicyException("policy monitor is running, unable to set scan interval");
        }
        
        _scanInterval = scanInterval;
    }
    
    /**
     * true of the monitor is initialized, meaning that at least one
     * scan cycle has completed and any policy grants found have been chirped
     * 
     * @return true if initialized
     */
    public boolean isInitialized()
    {
        return _initialized;
    }
    
    /**
     * gets the number of times the scan has been run
     * 
     * @return scan count
     */
    public int getScanCount()
    {
        return _scanCount.get();
    }
    
    /**
     * initiates a scan and blocks until it has been completed
     * 
     * @throws Exception
     */
    public synchronized void waitForScan() throws Exception
    {
        // wait for 2 scans for stable files
        CountDownLatch latch = new CountDownLatch(2);
        
       _scanningListener.setScanningLatch(latch);
       _scanner.scan();
       latch.await();
    }  
    
    /**
     * true of reload is enabled, false otherwise
     * 
     * @return true if reload is enabled
     */
    public boolean isReloadEnabled()
    {
        return _reload;
    }

    /**
     * sets the monitor to reload or not, but only if the monitor isn't already running
     * 
     * TODO this doesn't really _have_ to be on a non-running monitor
     * 
     * @param reload
     */
    public void setReload(boolean reload)
    {
        if (isRunning())
        {
            throw new PolicyException("policy monitor is running, unable to set reload at this time");
        }
        
        _reload = reload;
    }

    /**
     * processes a policy file via the default policy loader and chirps
     * changes to the onPolicyChange() abstract method
     * 
     * @param filename
     */
    private void processPolicyFile(String filename)
    {
        try
        {
            File policyFile = new File(filename);

            Set<PolicyBlock> policyBlocks = DefaultPolicyLoader.load(new FileInputStream(policyFile),JettyPolicy.getContext());

            for (PolicyBlock policy : policyBlocks)
            {
                onPolicyChange(policy);
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }

    /**
     * called by the abstract lifecycle to start the monitor
     */
    @Override
    protected void doStart() throws Exception
    {
        super.doStart();
        
        _scanner = new Scanner();

        List<File> scanDirs = new ArrayList<File>();

        scanDirs.add(new File( _policyDirectory ) );
        
        //System.out.println("Scanning: " + _policyDirectory );
        
        _scanner.addListener(new Scanner.DiscreteListener()
        {

            public void fileRemoved(String filename) throws Exception
            {

            }

            /* will trigger when files are changed, not on load time, just when changed */
            public void fileChanged(String filename) throws Exception
            {
               if (_reload && filename.endsWith("policy"))
               {
                  // System.out.println("PolicyMonitor: policy file");
                   processPolicyFile(filename);
               }
            }

            public void fileAdded(String filename) throws Exception
            {
                if (filename.endsWith("policy"))
                {
                   // System.out.println("PolicyMonitor: added policy file");
                    processPolicyFile(filename);
                }
            }
        });
        
        _scanningListener = new LatchScannerListener();
        
        _scanner.addListener(_scanningListener);

        _scanner.setScanDirs(scanDirs);
        _scanner.setReportExistingFilesOnStartup(true);
        _scanner.start();
        _scanner.setScanInterval(_scanInterval);
    }
    
    /**
     * called by the abstract life cycle to turn off the monitor
     */
    @Override
    protected void doStop() throws Exception
    {
        super.doStop();
        
        _scanner.stop();
    }
    
    /**
     * latch listener that can taken in a countdownlatch and notify other 
     * blocking threads that the scan has been completed
     *
     */
    private class LatchScannerListener implements Scanner.ScanCycleListener
    {
        CountDownLatch _latch;
        
        public void scanStarted(int cycle) throws Exception
        {

        }
        
        public void scanEnded(int cycle) throws Exception
        {
            _initialized = true; // just really needed the first time
            _scanCount.incrementAndGet();
            if ( _latch != null )
            {
                _latch.countDown();
            }
        }
        
        public void setScanningLatch( CountDownLatch latch )
        {
            _latch = latch;
        }
    }
    
    /**
     * implemented by the user of the policy monitor to handle custom logic 
     * related to the usage of the policy grant instance/s.
     * 
     * @param grant
     */
    public abstract void onPolicyChange(PolicyBlock grant);
}
