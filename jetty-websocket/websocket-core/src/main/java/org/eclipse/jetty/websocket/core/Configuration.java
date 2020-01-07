//
//  ========================================================================
//  Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
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

    interface Customizer
    {
        void customize(Configuration configurable);
    }

    class ConfigurationHolder implements Configuration
    {
        protected Duration idleTimeout;
        protected Duration writeTimeout;
        protected Boolean autoFragment;
        protected Long maxFrameSize;
        protected Integer outputBufferSize;
        protected Integer inputBufferSize;
        protected Long maxBinaryMessageSize;
        protected Long maxTextMessageSize;

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
    }

    class ConfigurationCustomizer extends ConfigurationHolder implements Customizer
    {
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
        }

        public static ConfigurationCustomizer from(ConfigurationCustomizer parent, ConfigurationCustomizer child)
        {
            ConfigurationCustomizer customizer = new ConfigurationCustomizer();
            parent.customize(customizer);
            child.customize(customizer);
            return customizer;
        }
    }
}
