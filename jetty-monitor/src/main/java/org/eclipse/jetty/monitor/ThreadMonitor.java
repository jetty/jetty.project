//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.monitor;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jetty.monitor.thread.ThreadMonitorException;
import org.eclipse.jetty.monitor.thread.ThreadMonitorInfo;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

@ManagedObject("Busy Thread Monitor")
public class ThreadMonitor extends AbstractLifeCycle implements Runnable
{
    private static final Logger LOG = Log.getLogger(ThreadMonitor.class);

    private int _scanInterval;
    private int _logInterval;
    private int _busyThreshold;
    private int _logThreshold;
    private int _stackDepth;
    private int _trailLength;
    
    private ThreadMXBean _threadBean;
    
    private Thread _runner;
    private Logger _logger;
    private volatile boolean _done = true;
    private Dumpable _dumpable;

    private Map<Long,ThreadMonitorInfo> _monitorInfo;
    
    /* ------------------------------------------------------------ */
    /**
     * Instantiates a new thread monitor.
     *
     * @throws Exception
     */
    public ThreadMonitor() throws Exception
    {
        this(5000);
    }
    
    /* ------------------------------------------------------------ */
    /**
     * Instantiates a new thread monitor.
     *
     * @param intervalMs scan interval
     * @throws Exception
     */
    public ThreadMonitor(int intervalMs) throws Exception
    {
        this(intervalMs, 95);
    }
    
    /* ------------------------------------------------------------ */
    /**
     * Instantiates a new thread monitor.
     *
     * @param intervalMs scan interval
     * @param threshold busy threshold
     * @throws Exception
     */
    public ThreadMonitor(int intervalMs, int threshold) throws Exception
    {
        this(intervalMs, threshold, 3);
    }
    
    /* ------------------------------------------------------------ */
    /**
     * Instantiates a new thread monitor.
     *
     * @param intervalMs scan interval
     * @param threshold busy threshold
     * @param depth stack compare depth
     * @throws Exception
     */
    public ThreadMonitor(int intervalMs, int threshold, int depth) throws Exception
    {
        this(intervalMs, threshold, depth, 3);
    }
    
    /* ------------------------------------------------------------ */
    /**
     * Instantiates a new thread monitor.
     *
     * @param intervalMs scan interval
     * @param threshold busy threshold
     * @param depth stack compare depth
     * @param trail length of stack trail
     * @throws Exception
     */
    public ThreadMonitor(int intervalMs, int threshold, int depth, int trail) throws Exception
    {
        _scanInterval = intervalMs;
        _busyThreshold = threshold;
        _stackDepth = depth;
        _trailLength = trail;
        
        _logger = Log.getLogger(ThreadMonitor.class.getName());
        _monitorInfo = new HashMap<Long, ThreadMonitorInfo>();
       
        init();
    }
    
    /* ------------------------------------------------------------ */
    /**
     * Gets the scan interval.
     *
     * @return the scan interval
     */
    public int getScanInterval()
    {
        return _scanInterval;
    }

    /* ------------------------------------------------------------ */
    /**
     * Sets the scan interval.
     *
     * @param ms the new scan interval
     */
    public void setScanInterval(int ms)
    {
        _scanInterval = ms;
    }

    /* ------------------------------------------------------------ */
    /**
     * Gets the log interval.
     *
     * @return the log interval
     */
    public int getLogInterval()
    {
        return _logInterval;
    }

    /* ------------------------------------------------------------ */
    /**
     * Sets the log interval.
     *
     * @param ms the new log interval
     */
    public void setLogInterval(int ms)
    {
        _logInterval = ms;
    }

    /* ------------------------------------------------------------ */
    /**
     * Gets the busy threshold.
     *
     * @return the busy threshold
     */
    public int getBusyThreshold()
    {
        return _busyThreshold;
    }

    /* ------------------------------------------------------------ */
    /**
     * Sets the busy threshold.
     *
     * @param percent the new busy threshold
     */
    public void setBusyThreshold(int percent)
    {
        _busyThreshold = percent;
    }

    /* ------------------------------------------------------------ */
    /**
     * Gets the log threshold.
     *
     * @return the log threshold
     */
    public int getLogThreshold()
    {
        return _logThreshold;
    }

    /* ------------------------------------------------------------ */
    /**
     * Sets the log threshold.
     *
     * @param percent the new log threshold
     */
    public void setLogThreshold(int percent)
    {
        _logThreshold = percent;
    }

    /* ------------------------------------------------------------ */
    /**
     * Gets the stack depth.
     *
     * @return the stack depth
     */
    public int getStackDepth()
    {
        return _stackDepth;
    }

    /* ------------------------------------------------------------ */
    /**
     * Sets the stack depth.
     *
     * @param stackDepth the new stack depth
     */
    public void setStackDepth(int stackDepth)
    {
        _stackDepth = stackDepth;
    }
    
    /* ------------------------------------------------------------ */
    /**
     * Sets the stack trace trail length.
     *
     * @param trailLength the new trail length
     */
    public void setTrailLength(int trailLength)
    {
        _trailLength = trailLength;
    }

    /* ------------------------------------------------------------ */
    /**
     * Gets the stack trace trail length.
     *
     * @return the trail length
     */
    public int getTrailLength()
    {
        return _trailLength;
    }

    /* ------------------------------------------------------------ */
    /**
     * Enable logging of CPU usage.
     *
     * @param frequencyMs the logging frequency 
     * @param thresholdPercent the logging threshold
     */
    public void logCpuUsage(int frequencyMs, int thresholdPercent)
    {
        setLogInterval(frequencyMs);
        setLogThreshold(thresholdPercent);
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @return A {@link Dumpable} that is dumped whenever spinning threads are detected
     */
    public Dumpable getDumpable()
    {
        return _dumpable;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param dumpable A {@link Dumpable} that is dumped whenever spinning threads are detected
     */
    public void setDumpable(Dumpable dumpable)
    {
        _dumpable = dumpable;
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.util.component.AbstractLifeCycle#doStart()
     */
    public void doStart()
    {
        _done = false;
        
        _runner = new Thread(this);
        _runner.setDaemon(true);
        _runner.start();

        LOG.info("Thread Monitor started successfully");
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.util.component.AbstractLifeCycle#doStop()
     */
    public void doStop()
    {
        if (_runner != null)
        {
            _done = true;
            try
            {
                _runner.join();
            }
            catch (InterruptedException ex) {}
        }
    }

    /* ------------------------------------------------------------ */
    /**
     * Retrieve all avaliable thread ids
     *
     * @return array of thread ids
     */
    protected long[] getAllThreadIds()
    {
        return _threadBean.getAllThreadIds();
    }

    /* ------------------------------------------------------------ */
    /**
     * Retrieve the cpu time for specified thread.
     *
     * @param id thread id
     * @return cpu time of the thread
     */
    protected long getThreadCpuTime(long id)
    {
        return _threadBean.getThreadCpuTime(id);
    }
    
    /* ------------------------------------------------------------ */
    /**
     * Initialize JMX objects.
     */
    protected void init()
    {
        _threadBean = ManagementFactory.getThreadMXBean();
        if (_threadBean.isThreadCpuTimeSupported())
        {
            _threadBean.setThreadCpuTimeEnabled(true);
        }
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @see java.lang.Runnable#run()
     */
    public void run()
    {
        // Initialize repeat flag
        boolean repeat = false;
        boolean scanNow, logNow;

        // Set next scan time and log time
        long nextScanTime = System.currentTimeMillis();
        long nextLogTime = nextScanTime + _logInterval;
        
        while (!_done)
        {
            long currTime = System.currentTimeMillis();
            scanNow = (currTime > nextScanTime);
            logNow = (_logInterval > 0 && currTime > nextLogTime);
            if (repeat || scanNow || logNow)
            {
                repeat = collectThreadInfo();
                logThreadInfo(logNow);

                if (scanNow)
                {
                    nextScanTime = System.currentTimeMillis() + _scanInterval;
                }
                if (logNow)
                {
                    nextLogTime = System.currentTimeMillis() + _logInterval;
                }
            }

            // Sleep only if not going to repeat scanning immediately
            if (!repeat)
            {
                try
                {
                    Thread.sleep(100);
                }
                catch (InterruptedException ex)
                {
                    LOG.ignore(ex);
                }
            }
        }
        
    }
    
    /* ------------------------------------------------------------ */
    /**
     * Collect thread info.
     */
    private boolean collectThreadInfo()
    {
        boolean repeat = false;
        try
        {
            // Retrieve stack traces for all threads at once as it 
            // was proven to be an order of magnitude faster when
            // retrieving a single thread stack trace.
            Map<Thread,StackTraceElement[]> all = Thread.getAllStackTraces();
            
            for (Map.Entry<Thread,StackTraceElement[]> entry : all.entrySet())
            {
                Thread thread = entry.getKey();
                long threadId = thread.getId();
                
                // Skip our own runner thread
                if (threadId == _runner.getId())
                {
                    continue;
                }

                ThreadMonitorInfo currMonitorInfo = _monitorInfo.get(Long.valueOf(threadId));
                if (currMonitorInfo == null)
                {
                    // Create thread info object for a new thread 
                    currMonitorInfo = new ThreadMonitorInfo(thread);
                    currMonitorInfo.setStackTrace(entry.getValue());
                    currMonitorInfo.setCpuTime(getThreadCpuTime(threadId));
                    currMonitorInfo.setSampleTime(System.nanoTime());
                    _monitorInfo.put(Long.valueOf(threadId), currMonitorInfo);
                }
                else
                {
                    // Update the existing thread info object
                    currMonitorInfo.setStackTrace(entry.getValue());
                    currMonitorInfo.setCpuTime(getThreadCpuTime(threadId));
                    currMonitorInfo.setSampleTime(System.nanoTime());
    
                    // Stack trace count holds logging state
                    int count = currMonitorInfo.getTraceCount();
                    if (count >= 0 && currMonitorInfo.isSpinning())
                    {
                        // Thread was spinning and was logged before
                        if (count < _trailLength) 
                        {
                            // Log another stack trace
                            currMonitorInfo.setTraceCount(count+1);
                            repeat = true;
                            continue;
                        }
                        
                        // Reset spin flag and trace count
                        currMonitorInfo.setSpinning(false);
                        currMonitorInfo.setTraceCount(-1);
                    }
                    if (currMonitorInfo.getCpuUtilization() > _busyThreshold)
                    {
                        // Thread is busy
                        StackTraceElement[] lastStackTrace = currMonitorInfo.getStackTrace();
    
                        if (lastStackTrace != null 
                        && matchStackTraces(lastStackTrace, entry.getValue()))
                        {
                            // Thread is spinning
                            currMonitorInfo.setSpinning(true);
                            if (count < 0)
                            {
                                // Enable logging of spin status and stack traces 
                                // only if the incoming trace count is negative
                                // that indicates a new scan for this thread
                                currMonitorInfo.setTraceCount(0);
                                repeat = (_trailLength > 0);
                            }
                        }
                    }
                }
            }
        }
        catch (Exception ex)
        {
            LOG.debug(ex);
        }
        return repeat;
    }

    /* ------------------------------------------------------------ */
    protected void logThreadInfo(boolean logAll)
    {
        if (_monitorInfo.size() > 0)
        {
            // Select thread objects for all live threads
            long[] running = getAllThreadIds();
            List<ThreadMonitorInfo> all = new ArrayList<ThreadMonitorInfo>();
            for (int idx=0; idx<running.length; idx++)
            {
                ThreadMonitorInfo info = _monitorInfo.get(running[idx]); 
                if (info != null)
                {
                    all.add(info);
                }
            }

            // Sort selected thread objects by their CPU utilization
            Collections.sort(all, new Comparator<ThreadMonitorInfo>()
            {
                /* ------------------------------------------------------------ */
                public int compare(ThreadMonitorInfo info1, ThreadMonitorInfo info2)
                {
                    return (int)Math.signum(info2.getCpuUtilization()-info1.getCpuUtilization());
                }
            });
            
            String format = "Thread '%2$s'[%3$s,id:%1$d,cpu:%4$.2f%%]%5$s";
            
            // Log thread information for threads that exceed logging threshold
            // or log spinning threads if their trace count is zero
            boolean spinning=false;
            for (ThreadMonitorInfo info : all)
            {
                if (logAll && info.getCpuUtilization() > _logThreshold 
                || info.isSpinning() && info.getTraceCount() == 0)
                {
                    String message = String.format(format, 
                            info.getThreadId(), info.getThreadName(), 
                            info.getThreadState(), info.getCpuUtilization(),
                            info.isSpinning() ? " SPINNING" : "");
                   _logger.info(message);
                   spinning=true;
                }
            }
            
            // Dump info
            if (spinning && _dumpable!=null)
            {
                System.err.println(_dumpable.dump());
            }

            // Log stack traces for spinning threads with positive trace count
            for (ThreadMonitorInfo info : all)
            {
                if (info.isSpinning() && info.getTraceCount() >= 0)
                {
                    String message = String.format(format,
                            info.getThreadId(), info.getThreadName(), 
                            info.getThreadState(), info.getCpuUtilization(),
                            " STACK TRACE");
                    _logger.warn(new ThreadMonitorException(message, info.getStackTrace()));
                }
            }
        }
    }

    /* ------------------------------------------------------------ */
    /**
     * Match stack traces.
     *
     * @param lastStackTrace last stack trace
     * @param stackTrace current stack trace
     * @return true, if successful
     */
    private boolean matchStackTraces(StackTraceElement[] lastStackTrace, StackTraceElement[] stackTrace)
    {
        boolean match = true;
        int count = Math.min(_stackDepth, Math.min(lastStackTrace.length, stackTrace.length));
        
        for (int idx=0; idx < count; idx++)
        {
            if (!stackTrace[idx].equals(lastStackTrace[idx]))
            {
                match = false;
                break;
            }
        }
        return match;
    }
}
