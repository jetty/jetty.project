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

package org.eclipse.jetty.websocket.api;

import java.time.Duration;

/**
 * <p>Implementations allow to configure WebSocket parameters.</p>
 */
public interface Configurable
{
    /**
     * The duration that a websocket may be idle before being closed by the implementation
     *
     * @return the timeout duration
     */
    Duration getIdleTimeout();

    /**
     * The duration that a websocket may be idle before being closed by the implementation
     *
     * @param duration the timeout duration (may not be null or negative)
     */
    void setIdleTimeout(Duration duration);

    /**
     * The input (read from network layer) buffer size.
     * <p>
     * This is the raw read operation buffer size, before the parsing of the websocket frames.
     * </p>
     *
     * @return the raw network buffer input size.
     */
    int getInputBufferSize();

    /**
     * The input (read from network layer) buffer size.
     *
     * @param size the size in bytes
     */
    void setInputBufferSize(int size);

    /**
     * The output (write to network layer) buffer size.
     * <p>
     * This is the raw write operation buffer size and has no relationship to the websocket frame.
     * </p>
     *
     * @return the raw network buffer output size.
     */
    int getOutputBufferSize();

    /**
     * The output (write to network layer) buffer size.
     *
     * @param size the size in bytes
     */
    void setOutputBufferSize(int size);

    /**
     * Get the maximum size of a binary message during parsing.
     * <p>
     * This is a memory conservation option, memory over this limit will not be
     * allocated by Jetty for handling binary messages.  This applies to individual frames,
     * whole message handling, and partial message handling.
     * </p>
     * <p>
     * Binary messages over this maximum will result in a close code 1009 {@link StatusCode#MESSAGE_TOO_LARGE}
     * </p>
     *
     * @return the maximum size of a binary message
     */
    long getMaxBinaryMessageSize();

    /**
     * The maximum size of a binary message during parsing/generating.
     * <p>
     * Binary messages over this maximum will result in a close code 1009 {@link StatusCode#MESSAGE_TOO_LARGE}
     * </p>
     *
     * @param size the maximum allowed size of a binary message.
     */
    void setMaxBinaryMessageSize(long size);

    /**
     * Get the maximum size of a text message during parsing.
     * <p>
     * This is a memory conservation option, memory over this limit will not be
     * allocated by Jetty for handling text messages.  This applies to individual frames,
     * whole message handling, and partial message handling.
     * </p>
     * <p>
     * Text messages over this maximum will result in a close code 1009 {@link StatusCode#MESSAGE_TOO_LARGE}
     * </p>
     *
     * @return the maximum size of a text message.
     */
    long getMaxTextMessageSize();

    /**
     * The maximum size of a text message during parsing/generating.
     * <p>
     * Text messages over this maximum will result in a close code 1009 {@link StatusCode#MESSAGE_TOO_LARGE}
     *
     * @param size the maximum allowed size of a text message.
     */
    void setMaxTextMessageSize(long size);

    /**
     * The maximum payload size of any WebSocket Frame which can be received.
     *
     * @return the maximum size of a WebSocket Frame.
     */
    long getMaxFrameSize();

    /**
     * The maximum payload size of any WebSocket Frame which can be received.
     * <p>
     * WebSocket Frames over this maximum will result in a close code 1009 {@link StatusCode#MESSAGE_TOO_LARGE}
     * </p>
     *
     * @param maxFrameSize the maximum allowed size of a WebSocket Frame.
     */
    void setMaxFrameSize(long maxFrameSize);

    /**
     * If true, frames are automatically fragmented to respect the maximum frame size.
     *
     * @return whether to automatically fragment incoming WebSocket Frames.
     */
    boolean isAutoFragment();

    /**
     * If set to true, frames are automatically fragmented to respect the maximum frame size.
     *
     * @param autoFragment whether to automatically fragment incoming WebSocket Frames.
     */
    void setAutoFragment(boolean autoFragment);

    /**
     * Get the maximum number of data frames allowed to be waiting to be sent at any one time.
     * The default value is -1, this indicates there is no limit on how many frames can be
     * queued to be sent by the implementation. If the limit is exceeded, subsequent frames
     * sent are failed with a {@link java.nio.channels.WritePendingException} but
     * the connection is not failed and will remain open.
     *
     * @return the max number of frames.
     */
    int getMaxOutgoingFrames();

    /**
     * Set the maximum number of data frames allowed to be waiting to be sent at any one time.
     * The default value is -1, this indicates there is no limit on how many frames can be
     * queued to be sent by the implementation. If the limit is exceeded, subsequent frames
     * sent are failed with a {@link java.nio.channels.WritePendingException} but
     * the connection is not failed and will remain open.
     *
     * @param maxOutgoingFrames the max number of frames.
     */
    void setMaxOutgoingFrames(int maxOutgoingFrames);
}
