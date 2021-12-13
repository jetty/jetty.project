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

package org.eclipse.jetty.server;

import org.eclipse.jetty.util.thread.AutoLock;

/**
 * ContentProducer is the bridge between {@link HttpInput} and {@link HttpChannel}.
 * It wraps a {@link HttpChannel} and uses the {@link HttpChannel#needContent()},
 * {@link HttpChannel#produceContent()} and {@link HttpChannel#failAllContent(Throwable)}
 * methods, tracks the current state of the channel's input by updating the
 * {@link HttpChannelState} and provides the necessary mechanism to unblock
 * the reader thread when using a blocking implementation or to know if the reader thread
 * has to be rescheduled when using an async implementation.
 */
public interface ContentProducer
{
    /**
     * Lock this instance. The lock must be held before any method of this instance's
     * method be called, and must be manually released afterward.
     * @return the lock that is guarding this instance.
     */
    AutoLock lock();

    /**
     * Reset all internal state and clear any held resources.
     */
    void recycle();

    /**
     * Fail all content currently available in this {@link ContentProducer} instance
     * as well as in the underlying {@link HttpChannel}.
     *
     * This call is always non-blocking.
     * Doesn't change state.
     * @return true if EOF was reached.
     */
    boolean consumeAll();

    /**
     * Check if the current data rate consumption is above the minimal rate.
     * Abort the channel, fail the content currently available and throw a
     * BadMessageException(REQUEST_TIMEOUT_408) if the check fails.
     */
    void checkMinDataRate();

    /**
     * Get the byte count produced by the underlying {@link HttpChannel}.
     *
     * This call is always non-blocking.
     * Doesn't change state.
     * @return the byte count produced by the underlying {@link HttpChannel}.
     */
    long getRawContentArrived();

    /**
     * Get the byte count that can immediately be read from this
     * {@link ContentProducer} instance or the underlying {@link HttpChannel}.
     *
     * This call is always non-blocking.
     * Doesn't change state.
     * @return the available byte count.
     */
    int available();

    /**
     * Check if this {@link ContentProducer} instance contains some
     * content without querying the underlying {@link HttpChannel}.
     *
     * This call is always non-blocking.
     * Doesn't change state.
     * Doesn't query the HttpChannel.
     * @return true if this {@link ContentProducer} instance contains content, false otherwise.
     */
    boolean hasContent();

    /**
     * Check if the underlying {@link HttpChannel} reached an error content.
     * This call is always non-blocking.
     * Doesn't change state.
     * Doesn't query the HttpChannel.
     * @return true if the underlying {@link HttpChannel} reached an error content, false otherwise.
     */
    boolean isError();

    /**
     * Get the next content that can be read from or that describes the special condition
     * that was reached (error, eof).
     * This call may or may not block until some content is available, depending on the implementation.
     * The returned content is decoded by the interceptor set with {@link #setInterceptor(HttpInput.Interceptor)}
     * or left as-is if no intercept is set.
     * After this call, state can be either of UNREADY or IDLE.
     * @return the next content that can be read from or null if the implementation does not block
     * and has no available content.
     */
    HttpInput.Content nextContent();

    /**
     * Free up the content by calling {@link HttpInput.Content#succeeded()} on it
     * and updating this instance' internal state.
     */
    void reclaim(HttpInput.Content content);

    /**
     * Check if this {@link ContentProducer} instance has some content that can be read without blocking.
     * If there is some, the next call to {@link #nextContent()} will not block.
     * If there isn't any and the implementation does not block, this method will trigger a
     * {@link jakarta.servlet.ReadListener} callback once some content is available.
     * This call is always non-blocking.
     * @return true if some content is immediately available, false otherwise.
     */
    boolean isReady();

    /**
     * Get the {@link org.eclipse.jetty.server.HttpInput.Interceptor}.
     * @return The {@link org.eclipse.jetty.server.HttpInput.Interceptor}, or null if none set.
     */
    HttpInput.Interceptor getInterceptor();

    /**
     * Set the interceptor.
     * @param interceptor The interceptor to use.
     */
    void setInterceptor(HttpInput.Interceptor interceptor);

    /**
     * Wake up the thread that is waiting for the next content.
     * After this call, state can be READY.
     * @return true if the thread has to be rescheduled, false otherwise.
     */
    boolean onContentProducible();
}

