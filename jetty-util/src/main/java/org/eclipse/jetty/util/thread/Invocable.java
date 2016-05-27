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
 * An object (typically either a {@link Runnable} or {@link Callable}
 * that can declare how it will behaive when invoked: blocking, non-blocking
 * or either.
 *
 */
public interface Invocable
{
    enum InvocationType { BLOCKING, NON_BLOCKING, EITHER };
    
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
    
    public static void invokeOnlyNonBlocking(Runnable task)
    {
        switch(getInvocationType(task))
        {
            case BLOCKING:
                throw new IllegalArgumentException("Cannot invoke nonblocking: "+task);
                
            case NON_BLOCKING:
                task.run(); 
                break;
                
            case EITHER:
                // a Choice exists, so we must indicate NonBlocking
                invokeNonBlocking(task);
                break;
        }
    }

    public static void invokePreferNonBlocking(Runnable task)
    {
        switch(getInvocationType(task))
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
        switch(getInvocationType(task))
        {
            case BLOCKING:
            case NON_BLOCKING:
                task.run(); 
                break;
                
            case EITHER:
                if (getInvocationType(task)==InvocationType.EITHER && preferredInvocationType==InvocationType.NON_BLOCKING)
                    invokeNonBlocking(task);
                else
                    task.run();
                break;
        }
    }
    
    public static Runnable asPreferred(Runnable task, InvocationType preferredInvocationType)
    {
        switch(getInvocationType(task))
        {
            case BLOCKING:
            case NON_BLOCKING:
                break;

            case EITHER:
                if (getInvocationType(task)==InvocationType.EITHER && preferredInvocationType==InvocationType.NON_BLOCKING)
                    return new Runnable()
                {
                    @Override
                    public void run()
                    {
                        invokeNonBlocking(task);
                    }
                };
                break;
        }

        return task;
    }
    

    public static void invokePreferBlocking(Runnable task)
    {
        task.run();
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
