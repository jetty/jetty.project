// ========================================================================
// Copyright (c) 2008-2009 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.util.component;

import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestSuite;
import junit.textui.TestRunner;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.StdErrLog;

public class LifeCycleListenerTest extends TestCase
{
    static Exception cause = new Exception("expected test exception");

    public LifeCycleListenerTest(String name)
    {
        super(name);
    }

    public static void main(String[] args)
    {
        TestRunner.run(suite());
    }

    public static Test suite()
    {
        TestSuite suite = new TestSuite(LifeCycleListenerTest.class);
        return suite;
    }

    public void testStart() throws Exception
    {
        TestLifeCycle lifecycle = new TestLifeCycle();
        TestListener listener = new TestListener();
        lifecycle.addLifeCycleListener(listener);
        ((StdErrLog)Log.getLog()).setHideStacks(true);
        lifecycle.setCause(cause);
        
        try
        {
            lifecycle.start();
            assertTrue(false);
        }
        catch(Exception e)
        {
            assertEquals(cause,e);
            assertEquals(cause,listener.getCause());
        }
        lifecycle.setCause(null);
        ((StdErrLog)Log.getLog()).setHideStacks(false);
        
        
        lifecycle.start();

        // check that the starting event has been thrown
        assertTrue("The staring event didn't occur",listener.starting);

        // check that the started event has been thrown
        assertTrue("The started event didn't occur",listener.started);

        // check that the starting event occurs before the started event
        assertTrue("The starting event must occur before the started event",listener.startingTime <= listener.startedTime);

        // check that the lifecycle's state is started
        assertTrue("The lifecycle state is not started",lifecycle.isStarted());
    }

    public void testStop() throws Exception
    {
        TestLifeCycle lifecycle = new TestLifeCycle();
        TestListener listener = new TestListener();
        lifecycle.addLifeCycleListener(listener);

        
        // need to set the state to something other than stopped or stopping or
        // else
        // stop() will return without doing anything

        lifecycle.start();

        ((StdErrLog)Log.getLog()).setHideStacks(true);
        lifecycle.setCause(cause);
        
        try
        {
            lifecycle.stop();
            assertTrue(false);
        }
        catch(Exception e)
        {
            assertEquals(cause,e);
            assertEquals(cause,listener.getCause());
        }

        
        lifecycle.setCause(null);
        ((StdErrLog)Log.getLog()).setHideStacks(false);
        
        lifecycle.stop();

        // check that the stopping event has been thrown
        assertTrue("The stopping event didn't occur",listener.stopping);

        // check that the stopped event has been thrown
        assertTrue("The stopped event didn't occur",listener.stopped);

        // check that the stopping event occurs before the stopped event
        assertTrue("The stopping event must occur before the stopped event",listener.stoppingTime <= listener.stoppedTime);
        // System.out.println("STOPING TIME : " + listener.stoppingTime + " : " + listener.stoppedTime);

        // check that the lifecycle's state is stopped
        assertTrue("The lifecycle state is not stooped",lifecycle.isStopped());
    }

    private class TestLifeCycle extends AbstractLifeCycle
    {
        Exception cause;
        
        private TestLifeCycle()
        {
        }
        
        @Override
        protected void doStart() throws Exception
        {
            if (cause!=null)
                throw cause;
            super.doStart();
        }
        
        @Override
        protected void doStop() throws Exception
        {
            if (cause!=null)
                throw cause;
            super.doStop();
        }
        
        public void setCause(Exception e)
        {
            cause=e;
        }
    }

    private class TestListener implements LifeCycle.Listener
    {

        private boolean failure = false;
        private boolean started = false;
        private boolean starting = false;
        private boolean stopped = false;
        private boolean stopping = false;

        private long startedTime;
        private long startingTime;
        private long stoppedTime;
        private long stoppingTime;

        private Throwable cause = null;

        public void lifeCycleFailure(LifeCycle event, Throwable cause)
        {
            failure = true;
            this.cause = cause;
        }

        public Throwable getCause()
        {
            return cause;
        }

        public void lifeCycleStarted(LifeCycle event)
        {
            started = true;
            startedTime = System.currentTimeMillis();
        }

        public void lifeCycleStarting(LifeCycle event)
        {
            starting = true;
            startingTime = System.currentTimeMillis();

            // need to sleep to make sure the starting and started times are not
            // the same
            try
            {
                Thread.sleep(1);
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }
        }

        public void lifeCycleStopped(LifeCycle event)
        {
            stopped = true;
            stoppedTime = System.currentTimeMillis();
        }

        public void lifeCycleStopping(LifeCycle event)
        {
            stopping = true;
            stoppingTime = System.currentTimeMillis();

            // need to sleep to make sure the stopping and stopped times are not
            // the same
            try
            {
                Thread.sleep(1);
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }
        }
    }

}
