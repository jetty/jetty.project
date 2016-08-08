//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

import java.util.concurrent.Callable;

/**
 * <p>A task (typically either a {@link Runnable} or {@link Callable}
 * that declares how it will behave when invoked:</p>
 * <ul>
 * <li>blocking, the invocation will certainly block (e.g. performs blocking I/O)</li>
 * <li>non-blocking, the invocation will certainly <strong>not</strong> block</li>
 * <li>either, the invocation <em>may</em> block</li>
 * </ul>
 */
public interface Invocable
{
    enum InvocationType
    {
        BLOCKING, NON_BLOCKING, EITHER
    }

    static ThreadLocal<Boolean> __nonBlocking = new ThreadLocal<Boolean>()
    {
        @Override
        protected Boolean initialValue()
        {
            return Boolean.FALSE;
        }
    };

    public static boolean isNonBlockingInvocation()
    {
        return __nonBlocking.get();
    }

    public static void invokeNonBlocking(Runnable task)
    {
        // a Choice exists, so we must indicate NonBlocking
        Boolean was_non_blocking = __nonBlocking.get();
        try
        {
            __nonBlocking.set(Boolean.TRUE);
            task.run();
        }
        finally
        {
            __nonBlocking.set(was_non_blocking);
        }
    }

    public static void invokePreferNonBlocking(Runnable task)
    {
        switch (getInvocationType(task))
        {
            case BLOCKING:
            case NON_BLOCKING:
                task.run();
                break;

            case EITHER:
                // a Choice exists, so we must indicate NonBlocking
                invokeNonBlocking(task);
                break;
        }
    }

    public static void invokePreferred(Runnable task, InvocationType preferredInvocationType)
    {
        switch (getInvocationType(task))
        {
            case BLOCKING:
            case NON_BLOCKING:
                task.run();
                break;

            case EITHER:
                if (getInvocationType(task) == InvocationType.EITHER && preferredInvocationType == InvocationType.NON_BLOCKING)
                    invokeNonBlocking(task);
                else
                    task.run();
                break;
        }
    }

    public static Runnable asPreferred(Runnable task, InvocationType preferredInvocationType)
    {
        switch (getInvocationType(task))
        {
            case BLOCKING:
            case NON_BLOCKING:
                break;

            case EITHER:
                if (getInvocationType(task) == InvocationType.EITHER && preferredInvocationType == InvocationType.NON_BLOCKING)
                    return () -> invokeNonBlocking(task);
                break;
        }

        return task;
    }

    public static InvocationType getInvocationType(Object o)
    {
        if (o instanceof Invocable)
            return ((Invocable)o).getInvocationType();
        return InvocationType.BLOCKING;
    }

    default InvocationType getInvocationType()
    {
        return InvocationType.BLOCKING;
    }
}
