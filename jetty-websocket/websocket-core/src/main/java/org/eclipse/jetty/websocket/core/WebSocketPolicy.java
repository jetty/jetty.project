//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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

import org.eclipse.jetty.websocket.core.extensions.WebSocketExtensionRegistry;

/**
 * Settings for WebSocket operations.
 */
public class WebSocketPolicy
{
    private static final int KB = 1024;

    public static WebSocketPolicy newClientPolicy()
    {
        return new WebSocketPolicy(WebSocketBehavior.CLIENT);
    }

    public static WebSocketPolicy newServerPolicy()
    {
        return new WebSocketPolicy(WebSocketBehavior.SERVER);
    }
    
    /**
     * The maximum size of a text message during parsing.
     * <p>
     * Text messages over this maximum will result in a close code 1009 (MESSAGE_TOO_LARGE)
     * <p>
     * Default: 65536 (64 K)
     */
    private int maxTextMessageSize = 64 * KB;

    /**
     * The maximum size of a text message buffer.
     * <p>
     * Used ONLY for stream based message writing.
     * <p>
     * Default: 32768 (32 K)
     */
    private int maxTextMessageBufferSize = 32 * KB;

    /**
     * The maximum size of a binary message during parsing.
     * <p>
     * Binary messages over this maximum will result in a close code 1009 (MESSAGE_TOO_LARGE)
     * <p>
     * Default: 65536 (64 K)
     */
    private int maxBinaryMessageSize = 64 * KB;

    /**
     * The maximum size of a binary message buffer
     * <p>
     * Used ONLY for for stream based message writing
     * <p>
     * Default: 32768 (32 K)
     */
    private int maxBinaryMessageBufferSize = 32 * KB;

    /**
     * The timeout in ms (milliseconds) for async write operations.
     * <p>
     * Negative values indicate a disabled timeout.
     */
    private long asyncWriteTimeout = 60_000;

    /**
     * The time in ms (milliseconds) that a websocket may be idle before closing.
     * <p>
     * Default: 300000 (ms)
     */
    private long idleTimeout = 300_000;

    /**
     * The input (read from network layer) buffer size.
     * <p>
     * Default: 4096 (4 K)
     */
    private int inputBufferSize = 4 * KB;
    
    /**
     * The maximum size of an individual Frame payload.
     * <p>
     *     This is for raw memory management on parse.
     * </p>
     */
    // TODO: when ws-over-http2 exists, this needs to be set appropriately
    private int maxAllowedFrameSize = (int) Math.min(Integer.MAX_VALUE, Runtime.getRuntime().maxMemory());
    
    /**
     * The output (writes to network layer) buffer size.
     * <p>
     *     Default: 4096 (4 K)
     * </p>
     */
    private int outputBufferSize = 4 * KB;

    /**
     * Behavior of the websockets
     */
    private final WebSocketBehavior behavior;
    
    public WebSocketPolicy(WebSocketBehavior behavior)
    {
        this.behavior = behavior;
    }

    private void assertLessThan(String name, long size, String otherName, long otherSize)
    {
        if (size > otherSize)
        {
            throw new IllegalArgumentException(String.format("%s [%d] must be less than %s [%d]",name,size,otherName,otherSize));
        }
    }

    private void assertGreaterThan(String name, long size, long minSize)
    {
        if (size < minSize)
        {
            throw new IllegalArgumentException(String.format("%s [%d] must be a greater than or equal to " + minSize,name,size));
        }
    }

    public void assertValidBinaryMessageSize(int requestedSize)
    {
        if (maxBinaryMessageSize > 0)
        {
            // validate it
            if (requestedSize > maxBinaryMessageSize)
            {
                throw new MessageTooLargeException("Binary message size [" + requestedSize + "] exceeds maximum size [" + maxBinaryMessageSize + "]");
            }
        }
    }

    public void assertValidTextMessageSize(int requestedSize)
    {
        if (maxTextMessageSize > 0)
        {
            // validate it
            if (requestedSize > maxTextMessageSize)
            {
                throw new MessageTooLargeException("Text message size [" + requestedSize + "] exceeds maximum size [" + maxTextMessageSize + "]");
            }
        }
    }

    public WebSocketPolicy clonePolicy()
    {
        WebSocketPolicy clone = new WebSocketPolicy(this.behavior);
        clone.idleTimeout = this.idleTimeout;
        clone.maxTextMessageSize = this.maxTextMessageSize;
        clone.maxTextMessageBufferSize = this.maxTextMessageBufferSize;
        clone.maxBinaryMessageSize = this.maxBinaryMessageSize;
        clone.maxBinaryMessageBufferSize = this.maxBinaryMessageBufferSize;
        clone.inputBufferSize = this.inputBufferSize;
        clone.outputBufferSize = this.outputBufferSize;
        clone.maxAllowedFrameSize = this.maxAllowedFrameSize;
        clone.asyncWriteTimeout = this.asyncWriteTimeout;
        return clone;
    }
    
    /**
     * The timeout in ms (milliseconds) for async write operations.
     * <p>
     * Negative values indicate a disabled timeout.
     * 
     * @return the timeout for async write operations. negative values indicate disabled timeout.
     */
    public long getAsyncWriteTimeout()
    {
        return asyncWriteTimeout;
    }

    public WebSocketBehavior getBehavior()
    {
        return behavior;
    }

    /**
     * The time in ms (milliseconds) that a websocket connection mad by idle before being closed automatically.
     * 
     * @return the timeout in milliseconds for idle timeout.
     */
    public long getIdleTimeout()
    {
        return idleTimeout;
    }

    /**
     * The input (read from network layer) buffer size.
     * <p>
     * This is the raw read operation buffer size, before the parsing of the websocket frames.
     * </p>
     *
     * @return the raw network buffer input size.
     */
    public int getInputBufferSize()
    {
        return inputBufferSize;
    }
    
    /**
     * The output (write to network layer) buffer size.
     * <p>
     *   This is the raw write operation buffer size and has no relationship to the websocket frame.
     * </p>
     *
     * @return the raw network buffer output size.
     */
    public int getOutputBufferSize()
    {
        return outputBufferSize;
    }
    
    /**
     * The maximum allowed frame size during parsing.
     * <p>
     *     This is used to manage frame payload memory allocation concerns.
     *     If an excessively large frame payload size is received, then this
     *     will short circuit the parsing step and trigger a close code 1009 (MESSAGE_TOO_LARGE)
     *     for that endpoint before the allocation of the memory for that payload is even made.
     * </p>
     *
     * @return the maximum allowed frame size that this implementation can handle
     */
    public int getMaxAllowedFrameSize()
    {
        return maxAllowedFrameSize;
    }
    
    /**
     * Get the maximum size of a binary message buffer.
     * @return the maximum size of a binary message buffer
     */
    public int getMaxBinaryMessageBufferSize()
    {
        return maxBinaryMessageBufferSize;
    }

    /**
     * Get the maximum size of a binary message during parsing.
     * <p>
     * Binary messages over this maximum will result in a close code 1009 (MESSAGE_TOO_LARGE)
     * 
     * @return the maximum size of a binary message
     */
    public int getMaxBinaryMessageSize()
    {
        return maxBinaryMessageSize;
    }

    /**
     * Get the maximum size of a text message buffer (for streaming writing)
     * 
     * @return the maximum size of a text message buffer
     */
    public int getMaxTextMessageBufferSize()
    {
        return maxTextMessageBufferSize;
    }

    /**
     * Get the maximum size of a text message during parsing.
     * <p>
     * Text messages over this maximum will result in a close code 1009 (MESSAGE_TOO_LARGE)
     * 
     * @return the maximum size of a text message.
     */
    public int getMaxTextMessageSize()
    {
        return maxTextMessageSize;
    }

    /**
     * The timeout in ms (milliseconds) for async write operations.
     * <p>
     * Negative values indicate a disabled timeout.
     * 
     * @param ms
     *            the timeout in milliseconds
     */
    public void setAsyncWriteTimeout(long ms)
    {
        assertLessThan("AsyncWriteTimeout",ms,"IdleTimeout",idleTimeout);
        this.asyncWriteTimeout = ms;
    }

    /**
     * The time in ms (milliseconds) that a websocket may be idle before closing.
     * 
     * @param ms
     *            the timeout in milliseconds
     */
    public void setIdleTimeout(long ms)
    {
        if(ms < -1) return; // no change (likely came from annotation)

        assertGreaterThan("IdleTimeout",ms,0);
        this.idleTimeout = ms;
    }

    /**
     * The input (read from network layer) buffer size.
     * 
     * @param size
     *            the size in bytes
     */
    public void setInputBufferSize(int size)
    {
        if(size < 0) return; // no change (likely came from annotation)
        assertGreaterThan("InputBufferSize",size,1);

        this.inputBufferSize = size;
    }
    
    /**
     * The output (write to network layer) buffer size.
     *
     * @param size
     *            the size in bytes
     */
    public void setOutputBufferSize(int size)
    {
        if(size < 0) return; // no change (likely came from annotation)
        assertGreaterThan("OutputBufferSize",size,1);
        
        this.outputBufferSize = size;
    }
    
    /**
     * The maximum supported size of an individual frame during parsing.
     * <p>
     *     This is used to manage frame payload memory allocation concerns.
     *     If an excessively large frame payload size is received, then this
     *     will short circuit the parsing step and trigger a close code 1009 (MESSAGE_TOO_LARGE)
     *     for that endpoint before the allocation of the memory for that payload is even made.
     * </p>
     *
     * @param size
     *            the size in bytes
     */
    public void setMaxAllowedFrameSize(int size)
    {
        if(size < 0) return; // no change (likely came from annotation)
        assertGreaterThan("MaxAllowedFrameSize",size,1);
        
        this.maxAllowedFrameSize = size;
    }
    
    /**
     * The maximum size of a binary message buffer.
     * <p>
     * Used ONLY for stream based message writing.
     * 
     * @param size
     *            the maximum size of the binary message buffer
     */
    public void setMaxBinaryMessageBufferSize(int size)
    {
        assertGreaterThan("MaxBinaryMessageBufferSize",size,1);

        this.maxBinaryMessageBufferSize = size;
    }
    
    /**
     * The maximum size of a binary message during parsing.
     * <p>
     * Binary messages over this maximum will result in a close code 1009 (MESSAGE_TOO_LARGE)
     * </p>
     *
     * @param size
     *            the maximum allowed size of a binary message.
     */
    public void setMaxBinaryMessageSize(long size)
    {
        if (size > Integer.MAX_VALUE)
        {
            throw new IllegalArgumentException("This implementation does not support binary message sizes over " + Integer.MAX_VALUE);
        }
        this.setMaxBinaryMessageSize((int) size);
    }
    
    /**
     * The maximum size of a binary message during parsing.
     * <p>
     * Binary messages over this maximum will result in a close code 1009 (MESSAGE_TOO_LARGE)
     * 
     * @param size
     *            the maximum allowed size of a binary message.
     */
    public void setMaxBinaryMessageSize(int size)
    {
        if(size < 0) return; // no change (likely came from annotation)

        this.maxBinaryMessageSize = Math.max(-1, size);
    }

    /**
     * The maximum size of a text message buffer.
     * <p>
     * Used ONLY for stream based message writing.
     * 
     * @param size
     *            the maximum size of the text message buffer
     */
    public void setMaxTextMessageBufferSize(int size)
    {
        assertGreaterThan("MaxTextMessageBufferSize",size,1);

        this.maxTextMessageBufferSize = size;
    }
    
    /**
     * The maximum size of a text message during parsing.
     * <p>
     * Text messages over this maximum will result in a close code 1009 (MESSAGE_TOO_LARGE)
     *
     * @param size
     *            the maximum allowed size of a text message.
     */
    public void setMaxTextMessageSize(long size)
    {
        if (size > Integer.MAX_VALUE)
        {
            throw new IllegalArgumentException("This implementation does not support text message sizes over " + Integer.MAX_VALUE);
        }
        this.setMaxTextMessageSize((int) size);
    }
    
    /**
     * The maximum size of a text message during parsing.
     * <p>
     * Text messages over this maximum will result in a close code 1009 (MESSAGE_TOO_LARGE)
     * 
     * @param size
     *            the maximum allowed size of a text message.
     */
    public void setMaxTextMessageSize(int size)
    {
        if(size < 0) return; // no change (likely came from annotation)

        this.maxTextMessageSize = Math.max(-1, size);
    }
    
    @SuppressWarnings("StringBufferReplaceableByString")
    @Override
    public String toString()
    {
        StringBuilder builder = new StringBuilder();
        builder.append("WSPolicy@").append(Integer.toHexString(hashCode()));
        builder.append("[").append(behavior);
        builder.append(",textSize=").append(maxTextMessageSize);
        builder.append(",binarySize=").append(maxBinaryMessageSize);
        builder.append(",idleTimeout=").append(idleTimeout);
        builder.append("]");
        return builder.toString();
    }
}
