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

import java.util.Objects;

import org.eclipse.jetty.util.TypeUtil;

/**
 * <p>A reference counted resource, for example one that is borrowed from a pool,
 * that may be retained an additional number of times, and released a correspondent
 * number of times, over its lifecycle.</p>
 * <p>The resource is typically implicitly retained when it is first created.
 * It may be retained more times (thus incrementing its reference count) and released
 * (thus decrementing its reference count), until the reference count goes to zero.</p>
 * <h2><a id="idiom">Idiomatic usage</a></h2>
 * <p>The general rules to use {@code Retainable} objects are the following:</p>
 * <ol>
 * <li>If the {@code Retainable} has been obtained by calling a method, and the
 * caller code consumes it, then the caller code must call {@link #release()}.</li>
 * <li>If the {@code Retainable} has been obtained by {@code caller2} by calling a
 * method, and {@code caller2} returns it without consuming it to {@code caller1},
 * then {@code caller2} must not call {@link #release()}, since {@code caller1} will.</li>
 * <li>If the {@code Retainable} has been obtained as a method argument, the
 * receiver code must either:
 * <ol type="A">
 * <li>Consume the {@code Retainable} synchronously within the method, in which case
 * {@link #release()} must not be called.</li>
 * <li>Pass the {@code Retainable} to some other method, in which case {@link #release()}
 * must not be called.</li>
 * <li>Store away the {@code Retainable} for later or asynchronous processing, for
 * example storing it in containers such as {@link java.util.Collection}s, or capturing
 * it in a lambda that is passed to another thread, etc., in which case {@link #retain()}
 * must be called and a mechanism to call {@link #release()} later or asynchronously
 * for this additional {@link #retain()} must be arranged.</li>
 * </ol>
 * </ol>
 */
public interface Retainable extends org.eclipse.jetty.util.Retainable
{
    Retainable NON_RETAINABLE = new Retainable()
    {
    };

    /**
     * A wrapper of {@link Retainable} instances.
     */
    class Wrapper implements Retainable
    {
        private final Retainable wrapped;

        public Wrapper(Retainable wrapped)
        {
            this.wrapped = Objects.requireNonNull(wrapped);
        }

        public Retainable getWrapped()
        {
            return wrapped;
        }

        @Override
        public boolean canRetain()
        {
            return getWrapped().canRetain();
        }

        @Override
        public int getRetained()
        {
            return getWrapped().getRetained();
        }

        @Override
        public boolean isRetained()
        {
            return getWrapped().isRetained();
        }

        @Override
        public void retain()
        {
            getWrapped().retain();
        }

        @Override
        public boolean release()
        {
            return getWrapped().release();
        }

        @Override
        public String toString()
        {
            return "%s@%x[%s]".formatted(TypeUtil.toShortName(getClass()), hashCode(), getWrapped());
        }
    }

    class ReferenceCounter extends org.eclipse.jetty.util.Retainable.ReferenceCounter implements Retainable
    {
        public ReferenceCounter()
        {
            super();
        }

        protected ReferenceCounter(int initialCount)
        {
            super(initialCount);
        }
    }

    /**
     * Convenience method that replaces code like:
     * <pre>{@code
     *   if (buffer != null)
     *   {
     *       buffer.release();
     *       buffer = null;
     *   }
     * }
     * </pre>
     * with:
     * <pre>{@code
     *   buffer = Retainable.release(buffer);
     * }
     * </pre>
     * @param retainable The retainable to release, if not {@code null}.
     * @param <R> The type of the retainable
     * @return always returns {@code null}
     */
    static <R extends Retainable> R release(R retainable)
    {
        if (retainable != null)
            retainable.release();
        return null;
    }
}
