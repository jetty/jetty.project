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

package org.eclipse.jetty.websocket.core;

import java.time.Duration;

public interface Configuration
{
    /**
     * Get the Idle Timeout
     *
     * @return the idle timeout
     */
    Duration getIdleTimeout();

    /**
     * Get the Write Timeout
     *
     * @return the write timeout
     */
    Duration getWriteTimeout();

    /**
     * Set the Idle Timeout.
     *
     * @param timeout the timeout duration (timeout &lt;= 0 implies an infinite timeout)
     */
    void setIdleTimeout(Duration timeout);

    /**
     * Set the Write Timeout.
     *
     * @param timeout the timeout duration (timeout &lt;= 0 implies an infinite timeout)
     */
    void setWriteTimeout(Duration timeout);

    boolean isAutoFragment();

    void setAutoFragment(boolean autoFragment);

    long getMaxFrameSize();

    void setMaxFrameSize(long maxFrameSize);

    int getOutputBufferSize();

    void setOutputBufferSize(int outputBufferSize);

    int getInputBufferSize();

    void setInputBufferSize(int inputBufferSize);

    long getMaxBinaryMessageSize();

    void setMaxBinaryMessageSize(long maxSize);

    long getMaxTextMessageSize();

    void setMaxTextMessageSize(long maxSize);

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

    interface Customizer
    {
        void customize(Configuration configurable);
    }

    class ConfigurationCustomizer implements Configuration, Customizer
    {
        private Duration idleTimeout;
        private Duration writeTimeout;
        private Boolean autoFragment;
        private Long maxFrameSize;
        private Integer outputBufferSize;
        private Integer inputBufferSize;
        private Long maxBinaryMessageSize;
        private Long maxTextMessageSize;
        private Integer maxOutgoingFrames;

        @Override
        public Duration getIdleTimeout()
        {
            return idleTimeout == null ? WebSocketConstants.DEFAULT_IDLE_TIMEOUT : idleTimeout;
        }

        @Override
        public Duration getWriteTimeout()
        {
            return writeTimeout == null ? WebSocketConstants.DEFAULT_WRITE_TIMEOUT : writeTimeout;
        }

        @Override
        public void setIdleTimeout(Duration timeout)
        {
            this.idleTimeout = timeout;
        }

        @Override
        public void setWriteTimeout(Duration timeout)
        {
            this.writeTimeout = timeout;
        }

        @Override
        public boolean isAutoFragment()
        {
            return autoFragment == null ? WebSocketConstants.DEFAULT_AUTO_FRAGMENT : autoFragment;
        }

        @Override
        public void setAutoFragment(boolean autoFragment)
        {
            this.autoFragment = autoFragment;
        }

        @Override
        public long getMaxFrameSize()
        {
            return maxFrameSize == null ? WebSocketConstants.DEFAULT_MAX_FRAME_SIZE : maxFrameSize;
        }

        @Override
        public void setMaxFrameSize(long maxFrameSize)
        {
            this.maxFrameSize = maxFrameSize;
        }

        @Override
        public int getOutputBufferSize()
        {
            return outputBufferSize == null ? WebSocketConstants.DEFAULT_OUTPUT_BUFFER_SIZE : outputBufferSize;
        }

        @Override
        public void setOutputBufferSize(int outputBufferSize)
        {
            this.outputBufferSize = outputBufferSize;
        }

        @Override
        public int getInputBufferSize()
        {
            return inputBufferSize == null ? WebSocketConstants.DEFAULT_INPUT_BUFFER_SIZE : inputBufferSize;
        }

        @Override
        public void setInputBufferSize(int inputBufferSize)
        {
            this.inputBufferSize = inputBufferSize;
        }

        @Override
        public long getMaxBinaryMessageSize()
        {
            return maxBinaryMessageSize == null ? WebSocketConstants.DEFAULT_MAX_BINARY_MESSAGE_SIZE : maxBinaryMessageSize;
        }

        @Override
        public void setMaxBinaryMessageSize(long maxBinaryMessageSize)
        {
            this.maxBinaryMessageSize = maxBinaryMessageSize;
        }

        @Override
        public long getMaxTextMessageSize()
        {
            return maxTextMessageSize == null ? WebSocketConstants.DEFAULT_MAX_TEXT_MESSAGE_SIZE : maxTextMessageSize;
        }

        @Override
        public void setMaxTextMessageSize(long maxTextMessageSize)
        {
            this.maxTextMessageSize = maxTextMessageSize;
        }

        @Override
        public int getMaxOutgoingFrames()
        {
            return maxOutgoingFrames == null ? WebSocketConstants.DEFAULT_MAX_OUTGOING_FRAMES : maxOutgoingFrames;
        }

        @Override
        public void setMaxOutgoingFrames(int maxOutgoingFrames)
        {
            this.maxOutgoingFrames = maxOutgoingFrames;
        }

        @Override
        public void customize(Configuration configurable)
        {
            if (idleTimeout != null)
                configurable.setIdleTimeout(idleTimeout);
            if (writeTimeout != null)
                configurable.setWriteTimeout(writeTimeout);
            if (autoFragment != null)
                configurable.setAutoFragment(autoFragment);
            if (maxFrameSize != null)
                configurable.setMaxFrameSize(maxFrameSize);
            if (inputBufferSize != null)
                configurable.setInputBufferSize(inputBufferSize);
            if (outputBufferSize != null)
                configurable.setOutputBufferSize(outputBufferSize);
            if (maxBinaryMessageSize != null)
                configurable.setMaxBinaryMessageSize(maxBinaryMessageSize);
            if (maxTextMessageSize != null)
                configurable.setMaxTextMessageSize(maxTextMessageSize);
            if (maxOutgoingFrames != null)
                configurable.setMaxOutgoingFrames(maxOutgoingFrames);
        }

        @Override
        public String toString()
        {
            return String.format("%s@%x{idleTimeout=%s, writeTimeout=%s, autoFragment=%s, maxFrameSize=%s, " +
                    "inputBufferSize=%s, outputBufferSize=%s, maxBinaryMessageSize=%s, maxTextMessageSize=%s, maxOutgoingFrames=%s}",
                getClass().getSimpleName(), hashCode(),
                idleTimeout, writeTimeout, autoFragment, maxFrameSize, inputBufferSize, outputBufferSize,
                maxBinaryMessageSize, maxTextMessageSize, maxOutgoingFrames);
        }
    }
}
