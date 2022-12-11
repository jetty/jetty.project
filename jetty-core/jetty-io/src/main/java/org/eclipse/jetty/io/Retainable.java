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

package org.eclipse.jetty.io;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * <p>A reference counted resource, for example one that is borrowed from a pool,
 * that may be retained an additional number of times, and released a correspondent
 * number of times, over its lifecycle.</p>
 * <p>The resource is typically implicitly retained when it is first created.
 * It may be retained more times (thus incrementing its reference count) and released
 * (thus decrementing its reference count), until the reference count goes to zero.</p>
 */
public interface Retainable
{
    /**
     * <p>Retains this resource, incrementing the reference count.</p>
     */
    void retain();

    /**
     * <p>Releases this resource, decrementing the reference count.</p>
     * <p>This method returns {@code true} when the reference count goes to zero,
     * {@code false} otherwise.</p>
     *
     * @return whether the invocation of this method decremented the reference count to zero
     */
    boolean release();

    class Noop implements Retainable
    {
        @Override
        public void retain()
        {
        }

        @Override
        public boolean release()
        {
            return true;
        }
    }

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
            return "%s@%x[%s]".formatted(getClass().getSimpleName(), hashCode(), getWrapped());
        }
    }

    /**
     * <p>A reference count implementation for a {@link Retainable} resource.</p>
     * <p>The reference count is initialized to 1 when the resource is created,
     * and therefore it is implicitly retained and needs a call to {@link #release()}.</p>
     * <p>Additional calls to {@link #retain()} must be matched by correspondent
     * calls to {@link #release()}.</p>
     * <p>When the reference count goes to zero, the resource may be pooled.
     * When the resource is acquired from the pool, {@link #acquire()} should be
     * called to set the reference count to {@code 1}.</p>
     */
    class ReferenceCounter implements Retainable
    {
        private final AtomicInteger references;

        public ReferenceCounter()
        {
            this(1);
        }

        protected ReferenceCounter(int initialCount)
        {
            references = new AtomicInteger(initialCount);
        }

        /**
         * <p>Updates the reference count from {@code 0} to {@code 1}.</p>
         * <p>This method should only be used when this resource is acquired
         * from a pool.</p>
         */
        protected void acquire()
        {
            if (references.getAndUpdate(c -> c == 0 ? 1 : c) != 0)
                throw new IllegalStateException("acquired while in use " + this);
        }

        @Override
        public void retain()
        {
            if (!tryRetain())
                throw new IllegalStateException("released " + this);
        }

        public boolean tryRetain()
        {
            return references.getAndUpdate(c -> c == 0 ? 0 : c + 1) != 0;
        }

        @Override
        public boolean release()
        {
            int ref = references.updateAndGet(c ->
            {
                if (c == 0)
                    throw new IllegalStateException("already released " + this);
                return c - 1;
            });
            return ref == 0;
        }

        /**
         * <p>Returns whether {@link #retain()} has been called at least one more time than {@link #release()}.</p>
         *
         * @return whether this buffer is retained
         */
        public boolean isRetained()
        {
            return references.get() > 1;
        }

        @Override
        public String toString()
        {
            return String.format("%s@%x[r=%d]", getClass().getSimpleName(), hashCode(), references.get());
        }
    }
}
