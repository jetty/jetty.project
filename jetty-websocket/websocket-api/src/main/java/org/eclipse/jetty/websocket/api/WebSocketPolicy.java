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
    private long maxMessageSize = 64 * KB;

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

    public void assertValidMessageSize(int requestedSize)
    {
        if (maxMessageSize > 0)
        {
            // validate it
            if (requestedSize > maxMessageSize)
            {
                throw new MessageTooLargeException("Requested message size [" + requestedSize + "] exceeds maximum size [" + maxMessageSize + "]");
            }
        }
    }

    public WebSocketPolicy clonePolicy()
    {
        WebSocketPolicy clone = new WebSocketPolicy(this.behavior);
        clone.idleTimeout = this.idleTimeout;
        clone.maxMessageSize = this.maxMessageSize;
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

    public long getMaxMessageSize()
    {
        return maxMessageSize;
    }

    public void setIdleTimeout(long idleTimeout)
    {
        this.idleTimeout = idleTimeout;
    }

    public void setInputBufferSize(int inputBufferSize)
    {
        this.inputBufferSize = inputBufferSize;
    }

    public void setMaxMessageSize(long maxMessageSize)
    {
        this.maxMessageSize = maxMessageSize;
    }
}
