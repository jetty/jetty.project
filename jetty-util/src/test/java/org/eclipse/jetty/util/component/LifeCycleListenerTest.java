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

package org.eclipse.jetty.util.component;

import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.logging.StacklessLogging;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class LifeCycleListenerTest
{
    static Exception cause = new Exception("expected test exception");

    @Test
    public void testStart() throws Exception
    {
        TestLifeCycle lifecycle = new TestLifeCycle();
        TestListener listener = new TestListener();
        lifecycle.addEventListener(listener);

        lifecycle.setCause(cause);

        try (StacklessLogging stackless = new StacklessLogging(AbstractLifeCycle.class))
        {
            lifecycle.start();
            assertTrue(false);
        }
        catch (Exception e)
        {
            assertEquals(cause, e);
            assertEquals(cause, listener.getCause());
        }
        lifecycle.setCause(null);

        lifecycle.start();

        // check that the starting event has been thrown
        assertTrue(listener.starting, "The staring event didn't occur");

        // check that the started event has been thrown
        assertTrue(listener.started, "The started event didn't occur");

        // check that the starting event occurs before the started event
        assertTrue(listener.startingTime <= listener.startedTime, "The starting event must occur before the started event");

        // check that the lifecycle's state is started
        assertTrue(lifecycle.isStarted(), "The lifecycle state is not started");
    }

    @Test
    public void testStop() throws Exception
    {
        TestLifeCycle lifecycle = new TestLifeCycle();
        TestListener listener = new TestListener();
        lifecycle.addEventListener(listener);

        // need to set the state to something other than stopped or stopping or
        // else
        // stop() will return without doing anything

        lifecycle.start();
        lifecycle.setCause(cause);

        try (StacklessLogging stackless = new StacklessLogging(AbstractLifeCycle.class))
        {
            lifecycle.stop();
            assertTrue(false);
        }
        catch (Exception e)
        {
            assertEquals(cause, e);
            assertEquals(cause, listener.getCause());
        }

        lifecycle.setCause(null);

        lifecycle.stop();

        // check that the stopping event has been thrown
        assertTrue(listener.stopping, "The stopping event didn't occur");

        // check that the stopped event has been thrown
        assertTrue(listener.stopped, "The stopped event didn't occur");

        // check that the stopping event occurs before the stopped event
        assertTrue(listener.stoppingTime <= listener.stoppedTime, "The stopping event must occur before the stopped event");
        // System.out.println("STOPING TIME : " + listener.stoppingTime + " : " + listener.stoppedTime);

        // check that the lifecycle's state is stopped
        assertTrue(lifecycle.isStopped(), "The lifecycle state is not stopped");
    }

    @Test
    public void testRemoveLifecycleListener()
        throws Exception
    {
        TestLifeCycle lifecycle = new TestLifeCycle();
        TestListener listener = new TestListener();
        lifecycle.addEventListener(listener);

        lifecycle.start();
        assertTrue(listener.starting, "The starting event didn't occur");
        lifecycle.removeEventListener(listener);
        lifecycle.stop();
        assertFalse(listener.stopping, "The stopping event occurred");
    }

    private static class TestLifeCycle extends AbstractLifeCycle
    {
        Exception cause;

        private TestLifeCycle()
        {
        }

        @Override
        protected void doStart() throws Exception
        {
            if (cause != null)
                throw cause;
            super.doStart();
        }

        @Override
        protected void doStop() throws Exception
        {
            if (cause != null)
                throw cause;
            super.doStop();
        }

        public void setCause(Exception e)
        {
            cause = e;
        }
    }

    private class TestListener extends AbstractLifeCycle.AbstractLifeCycleListener
    {
        @SuppressWarnings("unused")
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
            startedTime = TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
        }

        public void lifeCycleStarting(LifeCycle event)
        {
            starting = true;
            startingTime = TimeUnit.NANOSECONDS.toMillis(System.nanoTime());

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
            stoppedTime = TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
        }

        public void lifeCycleStopping(LifeCycle event)
        {
            stopping = true;
            stoppingTime = TimeUnit.NANOSECONDS.toMillis(System.nanoTime());

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
