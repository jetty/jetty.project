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

package org.eclipse.jetty.util.thread;

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.slf4j.LoggerFactory;

/**
 * An executor than ensurers serial execution of submitted tasks.
 * <p>
 * Callers of this execute will never block in the executor, but they may
 * be required to either execute the task they submit or tasks submitted
 * by other threads whilst they are executing tasks.
 * </p>
 * <p>
 * This class was inspired by the public domain class
 * <a href="https://github.com/jroper/reactive-streams-servlet/blob/master/reactive-streams-servlet/src/main/java/org/reactivestreams/servlet/NonBlockingMutexExecutor.java">NonBlockingMutexExecutor</a>
 * </p>
 */
public class SerializedExecutor implements Executor
{
    private final AtomicReference<Link> _tail = new AtomicReference<>();

    @Override
    public void execute(Runnable task)
    {
        Link link = new Link(task);
        Link lastButOne = _tail.getAndSet(link);
        if (lastButOne == null)
            run(link);
        else
            lastButOne._next.lazySet(link);
    }

    protected void onError(Runnable task, Throwable t)
    {
        if (task instanceof ErrorHandlingTask)
            ((ErrorHandlingTask)task).accept(t);
        LoggerFactory.getLogger(task.getClass()).error("Error", t);
    }

    private void run(Link link)
    {
        while (link != null)
        {
            try
            {
                link._task.run();
            }
            catch (Throwable t)
            {
                onError(link._task, t);
            }
            finally
            {
                // Are we the current the last Link?
                if (_tail.compareAndSet(link, null))
                    link = null;
                else
                {
                    // not the last task, so its next link will eventually be set
                    Link next = link._next.get();
                    while (next == null)
                    {
                        Thread.yield(); // Thread.onSpinWait();
                        next = link._next.get();
                    }
                    link = next;
                }
            }
        }
    }

    private class Link
    {
        private final Runnable _task;
        private final AtomicReference<Link> _next = new AtomicReference<>();

        public Link(Runnable task)
        {
            _task = task;
        }
    }

    /**
     * Error handling task
     * <p>If a submitted task implements this interface, it will be passed
     * any exceptions thrown when running the task.</p>
     */
    public interface ErrorHandlingTask extends Runnable, Consumer<Throwable>
    {}
}
