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

package org.eclipse.jetty.util.thread;

/**
 * Marker that wraps a Runnable, indicating that it is running in a thread that must not be blocked.
 * <p />
 * Client code can use the thread-local {@link #isNonBlockingThread()} to detect whether they are
 * in the context of a non-blocking thread, and perform different actions if that's the case.
 */
public class NonBlockingThread implements Runnable
{
    private final static ThreadLocal<Boolean> __nonBlockingThread = new ThreadLocal<>();

    /**
     * @return whether the current thread is a thread that must not block.
     */
    public static boolean isNonBlockingThread()
    {
        return Boolean.TRUE.equals(__nonBlockingThread.get());
    }

    private final Runnable delegate;

    public NonBlockingThread(Runnable delegate)
    {
        this.delegate = delegate;
    }

    @Override
    public void run()
    {
        try
        {
            __nonBlockingThread.set(Boolean.TRUE);
            delegate.run();
        }
        finally
        {
            __nonBlockingThread.remove();
        }
    }
}
