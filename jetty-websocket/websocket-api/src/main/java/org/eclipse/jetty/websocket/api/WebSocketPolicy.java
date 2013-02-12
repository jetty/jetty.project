//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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
     * The maximum size of a text message during parsing/generating.
     * <p>
     * Default: 65536 (64 K)
     */
    private int maxTextMessageSize = 64 * KB;

    /**
     * The maximum size of a binary message during parsing/generating.
     * <p>
     * Default: 65536 (64 K)
     */
    private int maxBinaryMessageSize = 64 * KB;

    /**
     * The time in ms (milliseconds) that a websocket may be idle before closing.
     * <p>
     * Default: 300000 (ms)
     */
    private long idleTimeout = 300000;

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

    public WebSocketPolicy(WebSocketBehavior behavior)
    {
        this.behavior = behavior;
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
        clone.maxBinaryMessageSize = this.maxBinaryMessageSize;
        clone.inputBufferSize = this.inputBufferSize;
        return clone;
    }

    public WebSocketBehavior getBehavior()
    {
        return behavior;
    }

    public long getIdleTimeout()
    {
        return idleTimeout;
    }

    public int getInputBufferSize()
    {
        return inputBufferSize;
    }

    public int getMaxBinaryMessageSize()
    {
        return maxBinaryMessageSize;
    }

    public int getMaxTextMessageSize()
    {
        return maxTextMessageSize;
    }

    public void setIdleTimeout(long idleTimeout)
    {
        this.idleTimeout = idleTimeout;
    }

    public void setInputBufferSize(int inputBufferSize)
    {
        this.inputBufferSize = inputBufferSize;
    }

    public void setMaxBinaryMessageSize(int maxBinaryMessageSize)
    {
        this.maxBinaryMessageSize = maxBinaryMessageSize;
    }

    public void setMaxTextMessageSize(int maxTextMessageSize)
    {
        this.maxTextMessageSize = maxTextMessageSize;
    }
}
