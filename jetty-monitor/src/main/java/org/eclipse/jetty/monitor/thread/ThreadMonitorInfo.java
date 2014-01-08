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

package org.eclipse.jetty.monitor.thread;


/* ------------------------------------------------------------ */
/**
 */
public class ThreadMonitorInfo
{
    private Thread _thread;
    private StackTraceElement[] _stackTrace;

    private boolean _threadSpinning = false;
    private int _traceCount = -1;

    private long _prevCpuTime;
    private long _prevSampleTime;
    private long _currCpuTime;
    private long _currSampleTime;

    
    /* ------------------------------------------------------------ */
    /**
     * Instantiates a new thread monitor info.
     *
     * @param thread the thread this object is created for
     */
    public ThreadMonitorInfo(Thread thread)
    {
        _thread = thread;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return Id of the thread
     */
    public long getThreadId()
    {
        return _thread.getId();
    }
    
    /* ------------------------------------------------------------ */
    /**
     * Gets the thread name.
     *
     * @return the thread name
     */
    public String getThreadName()
    {
        return _thread.getName();
    }
    
    /* ------------------------------------------------------------ */
    /**
     * Gets the thread state.
     *
     * @return the thread state
     */
    public String getThreadState()
    {
        return _thread.getState().toString();
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

    /* ------------------------------------------------------------ */
    /**
     * Checks if is spinning.
     *
     * @return true, if is spinning
     */
    public boolean isSpinning()
    {
        return _threadSpinning;
    }
    
    /* ------------------------------------------------------------ */
    /**
     * Sets the spinning flag.
     *
     * @param value the new value
     */
    public void setSpinning(boolean value)
    {
        _threadSpinning = value;
    }
    
    /* ------------------------------------------------------------ */
    /**
     * Sets the trace count.
     *
     * @param traceCount the new trace count
     */
    public void setTraceCount(int traceCount)
    {
        _traceCount = traceCount;
    }

    /* ------------------------------------------------------------ */
    /**
     * Gets the trace count.
     *
     * @return the trace count
     */
    public int getTraceCount()
    {
        return _traceCount;
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
}
