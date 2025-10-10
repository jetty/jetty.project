//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.util.thread;

/**
 * ThreadUtils provides misc. utilities for threads.
 */
public class ThreadUtils
{
    private static ThreadGroup innocuousThreadGroup;

    public static boolean isInnocuous(Thread thread)
    {
        if (Runtime.version().feature() < 25)
            return false;

        if (innocuousThreadGroup != null)
            return thread.getThreadGroup() == innocuousThreadGroup;

        ThreadGroup threadGroup = thread.getThreadGroup();
        if (threadGroup != null && "InnocuousThreadGroup".equals(threadGroup.getName()))
        {
            innocuousThreadGroup = threadGroup;
            return true;
        }
        return false;
    }
}
