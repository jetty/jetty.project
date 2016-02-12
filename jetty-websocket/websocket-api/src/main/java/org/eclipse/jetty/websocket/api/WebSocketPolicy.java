//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.api;

import java.util.ArrayList;
import java.util.List;

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
    
    /* NOTE TO OTHER DEVELOPERS: 
     * If you change any of these default values,
     * make sure you sync the values with 
     * org.eclipse.jetty.websocket.api.annotations.WebSocket 
     * annotation defaults
     */

    /**
     * The maximum size of a text message during parsing/generating.
     * <p>
     * Text messages over this maximum will result in a close code 1009 {@link StatusCode#MESSAGE_TOO_LARGE}
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
     * The maximum size of a binary message during parsing/generating.
     * <p>
     * Binary messages over this maximum will result in a close code 1009 {@link StatusCode#MESSAGE_TOO_LARGE}
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
     * The size of the input (read from network layer) buffer size.
     * <p>
     * Default: 4096 (4 K)
     */
    private int inputBufferSize = 4 * KB;

    /**
     * Behavior of the websockets
     */
    private final WebSocketBehavior behavior;
    
    public static interface PolicyUpdate
    {
        public void onPolicyUpdate(WebSocketPolicy policy);
    }
    
    private List<PolicyUpdate> listeners = new ArrayList<>();

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
        clone.asyncWriteTimeout = this.asyncWriteTimeout;
        // clone.listeners.addAll(this.listeners);
        return clone;
    }
    
    public void addListener(PolicyUpdate update)
    {
        this.listeners.add(update);
    }
    
    public void removeListener(PolicyUpdate update)
    {
        this.listeners.remove(update);
    }
    
    private void notifyOfUpdate()
    {
        for(PolicyUpdate update: listeners)
        {
            update.onPolicyUpdate(this);
        }
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
     * The size of the input (read from network layer) buffer size.
     * <p>
     * This is the raw read operation buffer size, before the parsing of the websocket frames.
     * 
     * @return the raw network bytes read operation buffer size.
     */
    public int getInputBufferSize()
    {
        return inputBufferSize;
    }

    /**
     * Get the maximum size of a binary message buffer (for streaming writing)
     * 
     * @return the maximum size of a binary message buffer
     */
    public int getMaxBinaryMessageBufferSize()
    {
        return maxBinaryMessageBufferSize;
    }

    /**
     * Get the maximum size of a binary message during parsing/generating.
     * <p>
     * Binary messages over this maximum will result in a close code 1009 {@link StatusCode#MESSAGE_TOO_LARGE}
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
     * Get the maximum size of a text message during parsing/generating.
     * <p>
     * Text messages over this maximum will result in a close code 1009 {@link StatusCode#MESSAGE_TOO_LARGE}
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
        boolean dirty = (this.asyncWriteTimeout != ms);
        assertLessThan("AsyncWriteTimeout",ms,"IdleTimeout",idleTimeout);
        this.asyncWriteTimeout = ms;
        if (dirty)
            notifyOfUpdate();
    }

    /**
     * The time in ms (milliseconds) that a websocket may be idle before closing.
     * 
     * @param ms
     *            the timeout in milliseconds
     */
    public void setIdleTimeout(long ms)
    {
        boolean dirty = (this.idleTimeout != ms);
        assertGreaterThan("IdleTimeout",ms,0);
        this.idleTimeout = ms;
        if (dirty)
            notifyOfUpdate();
    }

    /**
     * The size of the input (read from network layer) buffer size.
     * 
     * @param size
     *            the size in bytes
     */
    public void setInputBufferSize(int size)
    {
        boolean dirty = (this.inputBufferSize != size);
        assertGreaterThan("InputBufferSize",size,1);
        assertLessThan("InputBufferSize",size,"MaxTextMessageBufferSize",maxTextMessageBufferSize);
        assertLessThan("InputBufferSize",size,"MaxBinaryMessageBufferSize",maxBinaryMessageBufferSize);

        this.inputBufferSize = size;
        if(dirty)
            notifyOfUpdate();
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
        boolean dirty = (this.maxBinaryMessageBufferSize != size);
        assertGreaterThan("MaxBinaryMessageBufferSize",size,1);

        this.maxBinaryMessageBufferSize = size;
        if(dirty)
            notifyOfUpdate();
    }

    /**
     * The maximum size of a binary message during parsing/generating.
     * <p>
     * Binary messages over this maximum will result in a close code 1009 {@link StatusCode#MESSAGE_TOO_LARGE}
     * 
     * @param size
     *            the maximum allowed size of a binary message.
     */
    public void setMaxBinaryMessageSize(int size)
    {
        boolean dirty = (this.maxBinaryMessageSize != size);
        assertGreaterThan("MaxBinaryMessageSize",size,1);

        this.maxBinaryMessageSize = size;
        if(dirty)
            notifyOfUpdate();
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
        boolean dirty = (this.maxTextMessageBufferSize != size);
        assertGreaterThan("MaxTextMessageBufferSize",size,1);

        this.maxTextMessageBufferSize = size;
        if(dirty)
            notifyOfUpdate();
    }

    /**
     * The maximum size of a text message during parsing/generating.
     * <p>
     * Text messages over this maximum will result in a close code 1009 {@link StatusCode#MESSAGE_TOO_LARGE}
     * 
     * @param size
     *            the maximum allowed size of a text message.
     */
    public void setMaxTextMessageSize(int size)
    {
        boolean dirty = (this.maxTextMessageSize != size);
        assertGreaterThan("MaxTextMessageSize",size,1);

        this.maxTextMessageSize = size;
        if(dirty)
            notifyOfUpdate();
    }

    @Override
    public String toString()
    {
        StringBuilder builder = new StringBuilder();
        builder.append("WebSocketPolicy@").append(Integer.toHexString(hashCode()));
        builder.append("[").append(behavior);
        builder.append(",textSize=").append(maxTextMessageSize);
        builder.append(",binarySize=").append(maxBinaryMessageSize);
        builder.append(",idleTimeout=").append(idleTimeout);
        builder.append("]");
        return builder.toString();
    }
}
