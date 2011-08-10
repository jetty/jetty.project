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

import java.lang.management.ThreadInfo;
import java.util.ArrayList;
import java.util.List;


/* ------------------------------------------------------------ */
/**
 */
public class ThreadMonitorInfo
{
    private long _threadId;
    private String _threadName;
    private String _threadState;
    
    private long _lockOwnerId;
    private String _lockOwnerName;
    private String _lockName;
    
    private List<StackTraceElement[]> _stackTraces;

    private long _prevCpuTime;
    private long _prevSampleTime;
    private long _currCpuTime;
    private long _currSampleTime;

    private boolean _threadSpinning;    

    /* ------------------------------------------------------------ */
    /**
     * Instantiates a new thread monitor info.
     *
     * @param threadInfo the thread info
     */
    public ThreadMonitorInfo(ThreadInfo threadInfo)
    {
        _stackTraces = new ArrayList<StackTraceElement[]>();
        
        setInfo(threadInfo);
    }
    
    /* ------------------------------------------------------------ */
    /**
     * Sets the thread info.
     *
     * @param threadInfo the new thread info
     */
    public void setInfo(ThreadInfo threadInfo)
    {
        _threadId = threadInfo.getThreadId();
        _threadName = threadInfo.getThreadName();
        _threadState = threadInfo.isInNative() ? "IN_NATIVE" : 
            threadInfo.getThreadState().toString();
        
        _lockOwnerId = threadInfo.getLockOwnerId();
        _lockOwnerName = threadInfo.getLockOwnerName();
        _lockName = threadInfo.getLockName();
        
        _stackTraces.clear();
        addStackTrace(threadInfo.getStackTrace());
        
        _threadSpinning = false;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return Id of the thread
     */
    public long getThreadId()
    {
        return _threadId;
    }
    
    /* ------------------------------------------------------------ */
    /**
     * Gets the thread name.
     *
     * @return the thread name
     */
    public String getThreadName()
    {
        return _threadName;
    }
    
    /* ------------------------------------------------------------ */
    /**
     * Gets the thread state.
     *
     * @return the thread state
     */
    public String getThreadState()
    {
        return _threadState;
    }

    /* ------------------------------------------------------------ */
    /** Get the lockOwnerId.
     * @return the lockOwnerId
     */
    public long getLockOwnerId()
    {
        return _lockOwnerId;
    }

    /* ------------------------------------------------------------ */
    /** Get the lockOwnerName.
     * @return the lockOwnerName
     */
    public String getLockOwnerName()
    {
        return _lockOwnerName;
    }

    /* ------------------------------------------------------------ */
    /** Get the lockName.
     * @return the lockName
     */
    public String getLockName()
    {
        return _lockName;
    }
    
    public List<StackTraceElement[]> getStackTraces()
    {
        return _stackTraces;
    }

    public StackTraceElement[] getStackTrace()
    {
        return _stackTraces.size() == 0 ? null : _stackTraces.get(0);
    }

    public void addStackTrace(StackTraceElement[] stackTrace)
    {
        if (stackTrace != null && stackTrace.length > 0)
        {
            _stackTraces.add(stackTrace);
        }
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @return the CPU time of the thread
     */
    public long getCpuTime()
    {
        return _currCpuTime;
    }

    /* ------------------------------------------------------------ */
    /**
     * Set the CPU time.
     *
     * @param ns new CPU time
     */
    public void setCpuTime(long ns)
    {
        _prevCpuTime = _currCpuTime;
        _currCpuTime = ns;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return the time of sample  
     */
    public long getSampleTime()
    {
        return _currSampleTime;
    }

    /* ------------------------------------------------------------ */
    /**
     * Sets the sample time.
     *
     * @param ns the time of sample
     */
    public void setSampleTime(long ns)
    {
        _prevSampleTime = _currSampleTime;
        _currSampleTime = ns;
    }

    /* ------------------------------------------------------------ */
    /**
     * Gets the CPU utilization.
     *
     * @return the CPU utilization percentage
     */
    public float getCpuUtilization()
    {
        long elapsedCpuTime = _currCpuTime - _prevCpuTime;
        long elapsedNanoTime = _currSampleTime - _prevSampleTime;

        return elapsedNanoTime > 0 ? Math.min((elapsedCpuTime * 100.0f) / elapsedNanoTime, 100.0f) : 0; 
    }

    /* ------------------------------------------------------------ */
    public void setSpinning(boolean value)
    {
        _threadSpinning = value;
    }
    
    /* ------------------------------------------------------------ */
    public boolean isSpinning()
    {
        return _threadSpinning;
    }
}
