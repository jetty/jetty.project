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

package org.eclipse.jetty.util;

import java.util.concurrent.atomic.AtomicInteger;

/// A reference counted resource, for example one that is borrowed from a pool,
/// that may be retained an additional number of times, and released a correspondent
/// number of times, over its lifecycle.
///
/// The resource is typically implicitly retained when it is first created.
/// It may be retained more times (thus incrementing its reference count) and released
/// (thus decrementing its reference count), until the reference count goes to zero.
///
/// ## <a id="idiom">Idiomatic usage</a>
///
/// The general rules to use `Retainable` objects are the following:
///
/// 1. If the `Retainable` has been obtained by calling a method, and the
///    caller code consumes it, then the caller code must call [#release()].
/// 2. If the `Retainable` has been obtained by `caller2` by calling a
///    method, and `caller2` returns it without consuming it to `caller1`,
///    then `caller2` must not call [#release()], since `caller1` will.
/// 3. If the `Retainable` has been obtained as a method argument, the
///    receiver code must either:
///    * Consume the `Retainable` synchronously within the method, in
///      which case neither [#retain()] nor [#release()] must be called.
///    * Pass the `Retainable` to some other method, in which case
///      neither [#retain()] nor [#release()] must be called.
///    * Store away the `Retainable` for later or asynchronous processing, for
///      example storing it in containers such as [java.util.Collection]s, or
///      capturing it in a lambda that is passed to another thread, etc., in
///      which case [#retain()] must be called and a mechanism to call
///      [#release()] later or asynchronously for this additional [#retain()]
///      must be arranged.
public interface Retainable extends AutoCloseable
{
    Retainable NON_RETAINABLE = new Retainable()
    {
    };

    /// Convenience method that replaces code like:
    ///
    /// ```
    /// if (buffer != null)
    /// {
    ///     buffer.release();
    ///     buffer = null;
    /// }
    /// ```
    ///
    /// with:
    ///
    /// ```
    /// buffer = Retainable.dispose(buffer);
    /// ```
    ///
    /// @param retainable The [Retainable] to release, if not `null`.
    /// @param <R> The type of the [Retainable]
    /// @return always returns `null`
    static <R extends Retainable> R dispose(R retainable)
    {
        if (retainable != null)
            retainable.release();
        return null;
    }

    /// Returns whether [#retain()] has been called at least one more time than [#release()].
    ///
    /// @return whether this buffer is retained
    default boolean isRetained()
    {
        return false;
    }

    /// Retains this resource, potentially incrementing a reference
    /// count if there are resources that will be released.
    default void retain()
    {
    }

    /// Releases this resource, potentially decrementing a reference count (if any).
    ///
    /// @return `true` when the reference count goes to zero or if there was no reference count,
    /// `false` otherwise.
    default boolean release()
    {
        return true;
    }

    /// Conveniently calls [#release()] in try-with-resources blocks.
    @Override
    default void close()
    {
        release();
    }

    /// A reference count implementation for a [Retainable] resource.
    ///
    /// The reference count is initialized to 1 when the resource is created,
    /// and therefore it is implicitly retained and needs a call to [#release()].
    ///
    /// Additional calls to [#retain()] must be matched by correspondent
    /// calls to [#release()].
    ///
    /// When the reference count goes to zero, the resource may be pooled.
    /// When the resource is acquired from the pool, [#acquire()] should be
    /// called to set the reference count to `1`.
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

        /// @return the current reference count
        public int getCount()
        {
            return references.get();
        }

        /// Updates the reference count from `0` to `1`.
        ///
        /// This method should only be used when this resource is acquired
        /// from a pool.
        public void acquire()
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

        @Override
        public boolean isRetained()
        {
            return getCount() > 1;
        }

        @Override
        public String toString()
        {
            return String.format("%s@%x[rc=%d]", TypeUtil.toShortName(getClass()), hashCode(), getCount());
        }
    }
}
