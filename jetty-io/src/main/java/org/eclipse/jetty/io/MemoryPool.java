//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

/**
 * A Pool of objects representing memory that can be acquired based on size and direction. The held instances may be the memory
 * component itself (e.g.: {@link java.nio.ByteBuffer}) or an abstraction providing access to the memory component.
 * @param <T> The memory buffer type.
 */
public interface MemoryPool<T>
{
    /**
     * Acquire a memory buffer from the pool.
     * @param size The size of the buffer. The returned buffer will have at least this capacity.
     * @param direct true if a direct memory buffer is needed, false otherwise.
     * @return a memory buffer.
     */
    T acquire(int size, boolean direct);

    /**
     * Release a previously acquired memory buffer to the pool.
     * @param buffer the memory buffer to release.
     */
    void release(T buffer);
}
