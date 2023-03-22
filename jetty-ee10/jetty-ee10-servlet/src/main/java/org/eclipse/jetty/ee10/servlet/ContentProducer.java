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

package org.eclipse.jetty.ee10.servlet;

import org.eclipse.jetty.io.Content;

/**
 * ContentProducer is the bridge between {@link HttpInput} and {@link Content.Source}.
 */
public interface ContentProducer
{
    /**
     * A recycled {@link ContentProducer} will only produce special content with a non-null error until
     * {@link #reopen()} is called.
     */
    void recycle();

    /**
     * Reset all internal state, making this is instance logically equivalent to a freshly allocated one.
     */
    void reopen();

    /**
     * Fail all content currently available in this {@link ContentProducer} instance
     * as well as in the underlying {@link Content.Source}.
     *
     * This call is always non-blocking.
     * Doesn't change state.
     * @return true if EOF was reached.
     */
    boolean consumeAvailable();

    /**
     * Check if the current data rate consumption is above the minimal rate.
     * Abort the channel, fail the content currently available and throw a
     * BadMessageException(REQUEST_TIMEOUT_408) if the check fails.
     */
    void checkMinDataRate();

    /**
     * Get the byte count produced by the underlying {@link Content.Source}.
     *
     * This call is always non-blocking.
     * Doesn't change state.
     * @return the byte count produced by the underlying {@link Content.Source}.
     */
    long getBytesArrived();

    /**
     * Get the byte count that can immediately be read from this
     * {@link ContentProducer} instance or the underlying {@link Content.Source}.
     *
     * This call is always non-blocking.
     * Doesn't change state.
     * @return the available byte count.
     */
    int available();

    /**
     * Check if this {@link ContentProducer} instance contains some
     * content chunk without querying the underlying {@link Content.Source}.
     *
     * This call is always non-blocking.
     * Doesn't change state.
     * Doesn't query the HttpChannel.
     * @return true if this {@link ContentProducer} instance contains content, false otherwise.
     */
    boolean hasChunk();

    /**
     * Check if the underlying {@link Content.Source} reached an error content.
     * This call is always non-blocking.
     * Doesn't change state.
     * Doesn't query the HttpChannel.
     * @return true if the underlying {@link Content.Source} reached an error content, false otherwise.
     */
    boolean isError();

    /**
     * Get the next content chunk that can be read from or that describes the terminal condition
     * that was reached (error, eof).
     * This call may or may not block until some content is available, depending on the implementation.
     * After this call, state can be either of UNREADY or IDLE.
     *
     * @return the next content chunk that can be read from or null if the implementation does not block
     * and has no available content.
     */
    Content.Chunk nextChunk();

    /**
     * Free up the content by calling {@link Content.Chunk#release()} on it
     * and updating this instance's internal state.
     */
    void reclaim(Content.Chunk chunk);

    /**
     * Check if this {@link ContentProducer} instance has some content that can be read without blocking.
     * If there is some, the next call to {@link #nextChunk()} will not block.
     * If there isn't any and the implementation does not block, this method will trigger a
     * {@link jakarta.servlet.ReadListener} callback once some content is available.
     * This call is always non-blocking.
     * @return true if some content is immediately available, false otherwise.
     */
    boolean isReady();

    /**
     * Wake up the thread that is waiting for the next content.
     * After this call, state can be READY.
     * @return true if the thread has to be rescheduled, false otherwise.
     */
    boolean onContentProducible();
}

