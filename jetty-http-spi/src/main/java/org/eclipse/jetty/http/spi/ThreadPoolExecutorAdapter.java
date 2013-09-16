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

package org.eclipse.jetty.http.spi;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.log.Log;import org.eclipse.jetty.util.log.Logger;

import org.eclipse.jetty.util.thread.ThreadPool;

/**
 * Jetty {@link ThreadPool} that bridges requests to a {@link ThreadPoolExecutor}.
 */
public class ThreadPoolExecutorAdapter extends AbstractLifeCycle implements ThreadPool
{
    private static final Logger LOG = Log.getLogger(ThreadPoolExecutorAdapter.class);

	
	private ThreadPoolExecutor executor;
	
	public ThreadPoolExecutorAdapter(ThreadPoolExecutor executor)
	{
		this.executor = executor;
	}

	public boolean dispatch(Runnable job)
	{
		try
        {       
			executor.execute(job);
            return true;
        }
        catch(RejectedExecutionException e)
        {
            LOG.warn(e);
            return false;
        }
	}

	public int getIdleThreads()
	{
		return executor.getPoolSize()-executor.getActiveCount();
	}

	public int getThreads()
	{
		return executor.getPoolSize();
	}

	public boolean isLowOnThreads()
	{
		return executor.getActiveCount()>=executor.getMaximumPoolSize();
	}

	public void join() throws InterruptedException
	{
		executor.awaitTermination(Long.MAX_VALUE, TimeUnit.MILLISECONDS);
	}

	
	
	public boolean isFailed()
	{
		return false;
	}

	public boolean isRunning()
	{
		return !executor.isTerminated() && !executor.isTerminating();
	}

	public boolean isStarted()
	{
		return !executor.isTerminated() && !executor.isTerminating();
	}

	public boolean isStarting()
	{
		return false;
	}

	public boolean isStopped()
	{
		return executor.isTerminated();
	}

	public boolean isStopping()
	{
		return executor.isTerminating();
	}

	protected void doStart() throws Exception
	{
		if (executor.isTerminated() || executor.isTerminating() || executor.isShutdown())
            throw new IllegalStateException("Cannot restart");
	}

	protected void doStop() throws Exception
	{
		executor.shutdown();
        if (!executor.awaitTermination(60, TimeUnit.SECONDS))
        	executor.shutdownNow();
	}

}
