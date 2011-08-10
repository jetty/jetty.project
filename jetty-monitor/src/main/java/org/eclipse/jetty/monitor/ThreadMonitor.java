// ========================================================================
// Copyright (c) Webtide LLC
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at 
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses. 
// ========================================================================


package org.eclipse.jetty.monitor;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;


/* ------------------------------------------------------------ */
public class ThreadMonitor extends AbstractLifeCycle implements Runnable
{
    private int _scanInterval;
    private int _logInterval;
    private int _busyThreshold;
    private int _logThreshold;
    private int _stackDepth;
    
    private ThreadMXBean _threadBean;
    
    private Thread _runner;
    private Logger _logger;
    private volatile boolean _done = true;

    private Map<Long,ThreadMonitorInfo> _monitorInfo;
    
    /* ------------------------------------------------------------ */
    /**
     * Instantiates a new thread monitor.
     *
     * @throws Exception
     */
    public ThreadMonitor() throws Exception
    {
        this(5000, 95, 3);
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
        _scanInterval = intervalMs;
        _busyThreshold = threshold;
        _stackDepth = depth;
        
        _logger = Log.getLogger(ThreadMonitor.class.getName());
        _monitorInfo = new HashMap<Long, ThreadMonitorInfo>();
       
        init();
    }
    
    /* ------------------------------------------------------------ */
    public int getScanInterval()
    {
        return _scanInterval;
    }

    /* ------------------------------------------------------------ */
    public void setScanInterval(int ms)
    {
        _scanInterval = ms;
    }

    /* ------------------------------------------------------------ */
    public int getLogInterval()
    {
        return _logInterval;
    }

    /* ------------------------------------------------------------ */
    public void setLogInterval(int ms)
    {
        _logInterval = ms;
    }

    /* ------------------------------------------------------------ */
    public int getBusyThreshold()
    {
        return _busyThreshold;
    }

    /* ------------------------------------------------------------ */
    public void setBusyThreshold(int percent)
    {
        _busyThreshold = percent;
    }

    /* ------------------------------------------------------------ */
    public int getLogThreshold()
    {
        return _logThreshold;
    }

    /* ------------------------------------------------------------ */
    public void setLogThreshold(int percent)
    {
        _logThreshold = percent;
    }

    /* ------------------------------------------------------------ */
    public int getStackDepth()
    {
        return _stackDepth;
    }

    /* ------------------------------------------------------------ */
    public void setStackDepth(int stackDepth)
    {
        _stackDepth = stackDepth;
    }
    
    /* ------------------------------------------------------------ */
    public void logCpuUsage(int frequencyMs, int thresholdPercent)
    {
        setLogInterval(frequencyMs);
        setLogThreshold(thresholdPercent);
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

        Log.info("Thread Monitor started successfully");
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
        long currTime = System.currentTimeMillis(); 
        long lastTime = currTime;
        long lastDumpTime = currTime;
        while (!_done)
        {
            currTime = System.currentTimeMillis();
            if (currTime < lastTime + _scanInterval)
            {
                try
                {
                    Thread.sleep(100);
                }
                catch (InterruptedException ex)
                {
                    Log.ignore(ex);
                }
                continue;
            }
            
            collectThreadInfo();
            lastTime = System.currentTimeMillis();

            if (_logInterval > 0 && lastTime > lastDumpTime + _logInterval)
            {
                logCpuUsage();
                lastDumpTime = lastTime;
            }
            logThreadState();
        }
        
    }
    
    /* ------------------------------------------------------------ */
    /**
     * Collect thread info.
     */
    private void collectThreadInfo()
    {
        try
        {
            Map<Thread,StackTraceElement[]> all = Thread.getAllStackTraces();            
            for (Map.Entry<Thread,StackTraceElement[]> entry : all.entrySet())
            {
                Thread thread = entry.getKey();
                long threadId = thread.getId();
                
                if (threadId == _runner.getId())
                {
                    continue;
                }

                ThreadMonitorInfo currMonitorInfo = _monitorInfo.get(Long.valueOf(threadId));
                if (currMonitorInfo == null)
                {
                    currMonitorInfo = new ThreadMonitorInfo(thread);
                    currMonitorInfo.setStackTrace(entry.getValue());
                    currMonitorInfo.setCpuTime(getThreadCpuTime(threadId));
                    currMonitorInfo.setSampleTime(System.nanoTime());
                    _monitorInfo.put(Long.valueOf(threadId), currMonitorInfo);
                }
                else
                {
                    currMonitorInfo.setStackTrace(entry.getValue());
                    currMonitorInfo.setCpuTime(getThreadCpuTime(threadId));
                    currMonitorInfo.setSampleTime(System.nanoTime());
    
                    currMonitorInfo.setSpinning(false);
                    if (currMonitorInfo.getCpuUtilization() > _busyThreshold)
                    {
                        StackTraceElement[] lastStackTrace = currMonitorInfo.getStackTrace();
    
                        if (lastStackTrace != null 
                        && matchStackTraces(lastStackTrace, entry.getValue()))
                        {
                            currMonitorInfo.setSpinning(true);
                        }
                    }
                }
            }
        }
        catch (Exception ex)
        {
            Log.debug(ex);
        }
    }

    /* ------------------------------------------------------------ */
    protected void logCpuUsage()
    {
        if (_monitorInfo.size() > 0)
        {
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
            
            Collections.sort(all, new Comparator<ThreadMonitorInfo>()
            {
                /* ------------------------------------------------------------ */
                public int compare(ThreadMonitorInfo info1, ThreadMonitorInfo info2)
                {
                    return (int)Math.signum(info2.getCpuUtilization()-info1.getCpuUtilization());
                }
            });
            
            String format = "Thread %1$s[id:%2$d,%3$s] - %4$.2f%%";
            for (ThreadMonitorInfo info : all)
            {
                if (info.getCpuUtilization() > _logThreshold)
                {
                    String message = String.format(format, info.getThreadName(), 
                            info.getThreadId(), info.getThreadState(), info.getCpuUtilization());
                   _logger.info(message);
                }
            }
        }
    }

    /* ------------------------------------------------------------ */
    /**
     * Output thread info to log.
     *
     * @param detected thread info list
     */
    protected void logThreadState()
    {
        if (_monitorInfo.size() > 0)
        {
            long[] all = getAllThreadIds();
            for (int idx=0; idx<all.length; idx++)
            {
                ThreadMonitorInfo info = _monitorInfo.get(all[idx]); 
                if (info != null && info.isSpinning())
                {
                    String message = String.format("%1$s[id:%2$d,%3$s] is SPINNING",
                            info.getThreadName(), info.getThreadId(), info.getThreadState());
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
