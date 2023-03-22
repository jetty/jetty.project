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
import java.util.concurrent.atomic.AtomicInteger;

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
public interface Retainable
{
    /**
     * <p>Returns whether this resource is referenced counted by calls to {@link #retain()}
     * and {@link #release()}.</p>
     * <p>Implementations may decide that special resources are not not referenced counted (for example,
     * {@code static} constants) so calling {@link #retain()} is a no-operation, and
     * calling {@link #release()} on those special resources is a no-operation that always returns true.</p>
     *
     * @return true if calls to {@link #retain()} are reference counted.
     */
    default boolean canRetain()
    {
        return false;
    }

    /**
     * <p>Retains this resource, potentially incrementing a reference count if there are resources that will be released.</p>
     */
    default void retain()
    {
    }

    /**
     * <p>Releases this resource, potentially decrementing a reference count (if any).</p>
     *
     * @return {@code true} when the reference count goes to zero or if there was no reference count,
     *         {@code false} otherwise.
     */
    default boolean release()
    {
        return true;
    }

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
         * @return the current reference count
         */
        public int get()
        {
            return references.get();
        }

        /**
         * <p>Updates the reference count from {@code 0} to {@code 1}.</p>
         * <p>This method should only be used when this resource is acquired
         * from a pool.</p>
         */
        public void acquire()
        {
            if (references.getAndUpdate(c -> c == 0 ? 1 : c) != 0)
                throw new IllegalStateException("acquired while in use " + this);
        }

        @Override
        public boolean canRetain()
        {
            return true;
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
            return String.format("%s@%x[r=%d]", getClass().getSimpleName(), hashCode(), get());
        }
    }
}
