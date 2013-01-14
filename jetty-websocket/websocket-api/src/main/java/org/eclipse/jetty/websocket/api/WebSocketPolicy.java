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
     * The maximum allowed payload size (validated in both directions)
     * <p>
     * Default: 65536 (64K)
     */
    private int maxPayloadSize = 64 * KB;

    /**
     * The maximum size of a text message during parsing/generating.
     * <p>
     * Default: 16384 (16 K)
     */
    private int maxTextMessageSize = 64 * KB;

    /**
     * The maximum size of a binary message during parsing/generating.
     * <p>
     * Default: -1 (no validation)
     */
    private int maxBinaryMessageSize = 64 * KB;

    /**
     * The time in ms (milliseconds) that a websocket may be idle before closing.
     * <p>
     * Default: 300000 (ms)
     */
    private int idleTimeout = 300000;

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
                throw new MessageTooLargeException("Requested binary message size [" + requestedSize + "] exceeds maximum size [" + maxBinaryMessageSize + "]");
            }
        }
    }

    public void assertValidPayloadLength(int payloadLength)
    {
        // validate to buffer sizes
        if (payloadLength > maxPayloadSize)
        {
            throw new MessageTooLargeException("Requested payload length [" + payloadLength + "] exceeds maximum size [" + maxPayloadSize + "]");
        }
    }

    public void assertValidTextMessageSize(int requestedSize)
    {
        if (maxTextMessageSize > 0)
        {
            // validate it
            if (requestedSize > maxTextMessageSize)
            {
                throw new MessageTooLargeException("Requested text message size [" + requestedSize + "] exceeds maximum size [" + maxTextMessageSize + "]");
            }
        }
    }

    public WebSocketPolicy clonePolicy()
    {
        WebSocketPolicy clone = new WebSocketPolicy(this.behavior);
        clone.idleTimeout = this.idleTimeout;
        clone.maxPayloadSize = this.maxPayloadSize;
        clone.maxBinaryMessageSize = this.maxBinaryMessageSize;
        clone.maxTextMessageSize = this.maxTextMessageSize;
        return clone;
    }

    public WebSocketBehavior getBehavior()
    {
        return behavior;
    }

    public int getIdleTimeout()
    {
        return idleTimeout;
    }

    public int getMaxBinaryMessageSize()
    {
        return maxBinaryMessageSize;
    }

    public int getMaxPayloadSize()
    {
        return maxPayloadSize;
    }

    public int getMaxTextMessageSize()
    {
        return maxTextMessageSize;
    }

    public void setIdleTimeout(int idleTimeout)
    {
        this.idleTimeout = idleTimeout;
    }

    public void setMaxBinaryMessageSize(int maxBinaryMessageSize)
    {
        this.maxBinaryMessageSize = maxBinaryMessageSize;
    }

    public void setMaxPayloadSize(int maxPayloadSize)
    {
        if (maxPayloadSize < 0)
        {
            throw new IllegalStateException("Cannot have payload size be a negative number");
        }
        this.maxPayloadSize = maxPayloadSize;
    }

    public void setMaxTextMessageSize(int maxTextMessageSize)
    {
        this.maxTextMessageSize = maxTextMessageSize;
    }
}
