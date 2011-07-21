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
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.lang.reflect.Method;
import java.util.ArrayList;
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
    private int _busyThreshold;
    private int _stackDepth;
    
    private ThreadMXBean _threadBean;
    private Method findDeadlockedThreadsMethod;
    
    private Thread _runner;
    private boolean _done;
    private Logger _logger;

    private Map<Long,ExtThreadInfo> _extInfo;
    
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
        
        _logger = Log.getLogger(getClass().getName());
        _extInfo = new HashMap<Long, ExtThreadInfo>();
       
        init();
    }
    
    

    
    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.util.component.AbstractLifeCycle#doStart()
     */
    public void doStart()
    {
        _done = false;
        
        _runner = new Thread(this);
        _runner.start();

        Log.info("Thread Monitor started successfully");
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
     * Initialize JMX objects.
     */
    protected void init()
    {
        _threadBean = ManagementFactory.getThreadMXBean();
        if (_threadBean.isThreadCpuTimeSupported())
        {
            _threadBean.setThreadCpuTimeEnabled(true);
        }
        
        String versionStr = System.getProperty("java.version");
        float version = Float.valueOf(versionStr.substring(0,versionStr.lastIndexOf('.')));
        try
        {
            if (version < 1.6)
            { 
                findDeadlockedThreadsMethod = ThreadMXBean.class.getMethod("findMonitorDeadlockedThreads");
            }
            else
            {
                findDeadlockedThreadsMethod = ThreadMXBean.class.getMethod("findDeadlockedThreads");
            }
        }
        catch (Exception ex)
        {
            Log.debug(ex);
        }
    }
    
    /* ------------------------------------------------------------ */
    /**
     * Find deadlocked threads.
     *
     * @return array of the deadlocked thread ids
     * @throws Exception the exception
     */
    protected long[] findDeadlockedThreads() throws Exception
    {
        return (long[]) findDeadlockedThreadsMethod.invoke(_threadBean,(Object[])null);
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
     * Retrieve thread info.
     *
     * @param id thread id
     * @param maxDepth maximum stack depth
     * @return thread info
     */
    protected ThreadInfo getThreadInfo(long id, int maxDepth)
    {
        return _threadBean.getThreadInfo(id,maxDepth);
    }

    /* ------------------------------------------------------------ */
    /**
     * Output thread info to log.
     *
     * @param threads thread info list
     */
    protected void dump(final List<ThreadInfo> threads)
    {
        if (threads != null && threads.size() > 0)
        {
            for (ThreadInfo info : threads)
            {
                StringBuffer msg = new StringBuffer();
                if (info.getLockOwnerId() < 0)
                {
                    msg.append(String.format("Thread %s[%d] is spinning", info.getThreadName(), info.getThreadId()));
                }
                else
                {
                    msg.append(String.format("Thread %s[%d] is %s", info.getThreadName(), info.getThreadId(), info.getThreadState()));
                    msg.append(String.format(" on %s owned by %s[%d]", info.getLockName(), info.getLockOwnerName(), info.getLockOwnerId()));
                }
                
                _logger.warn(new ThreadMonitorException(msg.toString(), info.getStackTrace()));
            }
        }
    }

    /* ------------------------------------------------------------ */
    /**
     * @see java.lang.Runnable#run()
     */
    public void run()
    {
        long currTime; 
        long lastTime = 0;
        while (!_done)
        {
            currTime = System.currentTimeMillis();
            if (currTime < lastTime + _scanInterval)
            {
                try
                {
                    Thread.sleep(50);
                }
                catch (InterruptedException ex)
                {
                    Log.ignore(ex);
                }
                continue;
            }
            
            List<ThreadInfo> threadInfo = new ArrayList<ThreadInfo>(); 
            
            findSpinningThreads(threadInfo);
            findDeadlockedThreads(threadInfo);

            lastTime = System.currentTimeMillis();
            
            if (threadInfo.size() > 0)
            {
                dump(threadInfo);
            }
        }
        
    }
    
    /* ------------------------------------------------------------ */
    /**
     * Find spinning threads.
     *
     * @param threadInfo thread info list to add the results
     * @return thread info list
     */
    private List<ThreadInfo> findSpinningThreads(final List<ThreadInfo> threadInfo)
    {
        if (threadInfo != null)
        {
            try
            {
                long[] allThreadId = getAllThreadIds();            
                for (int idx=0; idx < allThreadId.length; idx++)
                {
                    long currId = allThreadId[idx];
                    
                    if (currId == _runner.getId())
                    {
                        continue;
                    }
    
                    long currCpuTime = getThreadCpuTime(currId);
                    long currNanoTime = System.nanoTime();
                    
                    ExtThreadInfo currExtInfo = _extInfo.get(Long.valueOf(currId));
                    if (currExtInfo != null)
                    {
                        long elapsedCpuTime = currCpuTime - currExtInfo.getLastCpuTime();
                        long elapsedNanoTime = currNanoTime - currExtInfo.getLastSampleTime();
    
                        if (((elapsedCpuTime * 100.0) / elapsedNanoTime) > _busyThreshold)
                        {
                            ThreadInfo currInfo = getThreadInfo(currId, Integer.MAX_VALUE);
                            if (currInfo != null)
                            {
                                StackTraceElement[] lastStackTrace = currExtInfo.getStackTrace();
                                currExtInfo.setStackTrace(currInfo.getStackTrace());

                                if (lastStackTrace != null 
                                && matchStackTraces(lastStackTrace, currInfo.getStackTrace())) {
                                    threadInfo.add(currInfo);
                                }
                            }
                        }
                    }
                    else
                    {
                        currExtInfo = new ExtThreadInfo(currId);
                        _extInfo.put(Long.valueOf(currId), currExtInfo);
                    }
                    
                    currExtInfo.setLastCpuTime(currCpuTime);
                    currExtInfo.setLastSampleTime(currNanoTime);
                }
            }
            catch (Exception ex)
            {
                Log.debug(ex);
            }
        }
        
        return threadInfo;
    }

    /* ------------------------------------------------------------ */
    /**
     * Find deadlocked threads.
     *
     * @param threadInfo thread info list to add the results
     * @return thread info list
     */
    private List<ThreadInfo> findDeadlockedThreads(final List<ThreadInfo> threadInfo)
    {
        if (threadInfo != null)
        {
            try
            {
                long[] threads = findDeadlockedThreads();
                if (threads != null && threads.length > 0)
                {
                    ThreadInfo currInfo;
                    for (int idx=0; idx < threads.length; idx++)
                    {
                        currInfo = getThreadInfo(threads[idx], Integer.MAX_VALUE);
                        if (currInfo != null)
                        {
                            threadInfo.add(currInfo);
                        }
                    }
                }
            }
            catch (Exception ex)
            {
                Log.debug(ex);
            }
        }
            
        return threadInfo;
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

    /* ------------------------------------------------------------ */
    private class ExtThreadInfo
    {
        private long _threadId;

        private long _lastCpuTime;
        private long _lastSampleTime;
        private StackTraceElement[] _stackTrace;

        /* ------------------------------------------------------------ */
        public ExtThreadInfo(long threadId)
        {
            _threadId = threadId;   
        }

        /* ------------------------------------------------------------ */
        /**
         * @return thread id associated with the instance
         */
        public long getThreadId()
        {
            return _threadId;
        }

        /* ------------------------------------------------------------ */
        /**
         * @return the last CPU time of the thread
         */
        public long getLastCpuTime()
        {
            return _lastCpuTime;
        }

        /* ------------------------------------------------------------ */
        /**
         * Set the last CPU time.
         *
         * @param lastCpuTime new last CPU time
         */
        public void setLastCpuTime(long lastCpuTime)
        {
            this._lastCpuTime = lastCpuTime;
        }

        /* ------------------------------------------------------------ */
        /**
         * @return the time of last sample  
         */
        public long getLastSampleTime()
        {
            return _lastSampleTime;
        }

        /* ------------------------------------------------------------ */
        /**
         * Sets the last sample time.
         *
         * @param lastSampleTime the time of last sample
         */
        public void setLastSampleTime(long lastSampleTime)
        {
            _lastSampleTime = lastSampleTime;
        }

        /* ------------------------------------------------------------ */
        /**
         * Gets the stack trace.
         *
         * @return the stack trace
         */
        public StackTraceElement[] getStackTrace()
        {
            return _stackTrace;
        }

        /* ------------------------------------------------------------ */
        /**
         * Sets the stack trace.
         *
         * @param stackTrace the new stack trace
         */
        public void setStackTrace(StackTraceElement[] stackTrace)
        {
            _stackTrace = stackTrace;
        }
    }
}
