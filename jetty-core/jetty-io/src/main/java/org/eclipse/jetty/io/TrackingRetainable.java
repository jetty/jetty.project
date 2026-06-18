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

package org.eclipse.jetty.io;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.eclipse.jetty.util.Retainable;
import org.eclipse.jetty.util.TypeUtil;

/**
 * A {@link Retainable} wrapper that helps track retain/release calls of the wrapped object.
 */
public class TrackingRetainable implements Retainable
{
    private final Instant acquireInstant;
    private final List<Throwable> stacks = new CopyOnWriteArrayList<>();
    private final Retainable delegate;

    public TrackingRetainable()
    {
        this(new ReferenceCounter());
    }

    public TrackingRetainable(Retainable retainable)
    {
        this.delegate = retainable;
        stacks.add(new Throwable("Acquired by " + Thread.currentThread().getName()));
        this.acquireInstant = Instant.now();
    }

    @Override
    public void retain()
    {
        try
        {
            delegate.retain();
            stacks.add(new Throwable("Retained by " + Thread.currentThread().getName()));
        }
        catch (IllegalStateException e)
        {
            stacks.add(new Throwable("Retain-after-last-release by " + Thread.currentThread().getName()));
            throw new IllegalStateException(Thread.currentThread().getName() + " retain-after-last-release " + dump());
        }
    }

    @Override
    public boolean release()
    {
        try
        {
            boolean released = delegate.release();
            stacks.add(new Throwable("Released by " + Thread.currentThread().getName()));
            return released;
        }
        catch (IllegalStateException e)
        {
            stacks.add(new Throwable("Over-released by " + Thread.currentThread().getName()));
            throw new IllegalStateException(Thread.currentThread().getName() + " over-released " + dump());
        }
    }

    public Instant getAcquireInstant()
    {
        return acquireInstant;
    }

    public String dump()
    {
        StringWriter w = new StringWriter();
        PrintWriter pw = new PrintWriter(w);
        pw.println("\n" + stacks.size() + " stack(s)");
        for (Throwable stack : stacks)
        {
            String stackString = toString(stack);
            pw.println(stackString);
        }
        String stacks = w.toString();
        return ("%s@%x on %s wrapping %s%n" +
            " %s")
            .formatted(TypeUtil.toShortName(getClass()), hashCode(), getAcquireInstant(), getRetained(),
                stacks);
    }

    private String toString(Throwable stack)
    {
        StringWriter w = new StringWriter();
        PrintWriter pw = new PrintWriter(w);
        stack.printStackTrace(pw);
        return w.toString();
    }

    @Override
    public boolean canRetain()
    {
        return delegate.canRetain();
    }

    @Override
    public boolean isRetained()
    {
        return delegate.isRetained();
    }

    @Override
    public int getRetained()
    {
        return delegate.getRetained();
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x[d=%s]", TypeUtil.toShortName(getClass()), hashCode(), delegate);
    }
}