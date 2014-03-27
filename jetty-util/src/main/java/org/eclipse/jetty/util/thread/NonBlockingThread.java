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

public class NonBlockingThread 
{
    private final static ThreadLocal<Boolean> __nonBlockingThread = new ThreadLocal<>(); 
    public static boolean isNonBlockingThread()
    {
        return Boolean.TRUE.equals(__nonBlockingThread.get());
    }

    public static void runAsNonBlocking(Runnable runnable)
    {
        try
        {
            __nonBlockingThread.set(Boolean.TRUE);
            runnable.run();
        }
        finally
        {
            __nonBlockingThread.remove();
        }
    }
}
