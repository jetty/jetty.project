//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
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

import org.eclipse.jetty.websocket.masks.Masker;

/**
 * Settings for WebSocket operations.
 */
public class WebSocketPolicy
{
    public static WebSocketPolicy newClientPolicy()
    {
        return new WebSocketPolicy(WebSocketBehavior.CLIENT);
    }

    public static WebSocketPolicy newServerPolicy()
    {
        return new WebSocketPolicy(WebSocketBehavior.SERVER);
    }

    /**
     * Automatically fragment large frames.
     * <p>
     * If frames are encountered at size larger than {@link #maxPayloadSize} then they are automatically fragmented into pieces fitting within the
     * maxPayloadSize.
     * <p>
     * Default: false
     */
    private boolean autoFragment = false;

    /**
     * The maximum allowed payload size (validated in both directions)
     * <p>
     * Default: 65536 (64K)
     */
    private int maxPayloadSize = 65536;

    /**
     * The maximum size of a text message during parsing/generating.
     * <p>
     * Default: 16384 (16 K)
     */
    private int maxTextMessageSize = 16384;

    /**
     * The maximum size of a binary message during parsing/generating.
     * <p>
     * Default: -1 (no validation)
     */
    private int maxBinaryMessageSize = -1;

    /**
     * Maximum Message Buffer size, which is also the max frame byte size.
     * <p>
     * Default: 65536 (64 K)
     */
    private int bufferSize = 65536;

    /**
     * The time in ms (milliseconds) that a websocket may be idle before closing.
     * <p>
     * Default: 300000 (ms)
     */
    private int idleTimeout = 300000;

    /**
     * The implementation for masking
     */
    private Masker masker = null;

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
        clone.autoFragment = this.autoFragment;
        clone.masker = this.masker;
        clone.idleTimeout = this.idleTimeout;
        clone.bufferSize = this.bufferSize;
        clone.maxPayloadSize = this.maxPayloadSize;
        clone.maxBinaryMessageSize = this.maxBinaryMessageSize;
        clone.maxTextMessageSize = this.maxTextMessageSize;
        return clone;
    }

    public WebSocketBehavior getBehavior()
    {
        return behavior;
    }

    public int getBufferSize()
    {
        return bufferSize;
    }

    public int getIdleTimeout()
    {
        return idleTimeout;
    }

    public Masker getMasker()
    {
        return masker;
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

    public boolean isAutoFragment()
    {
        return autoFragment;
    }

    public void setAutoFragment(boolean autoFragment)
    {
        this.autoFragment = autoFragment;
    }

    public void setBufferSize(int bufferSize)
    {
        this.bufferSize = bufferSize;
    }

    public void setIdleTimeout(int idleTimeout)
    {
        this.idleTimeout = idleTimeout;
    }

    public void setMasker(Masker masker)
    {
        this.masker = masker;
    }

    public void setMaxBinaryMessageSize(int maxBinaryMessageSize)
    {
        this.maxBinaryMessageSize = maxBinaryMessageSize;
    }

    public void setMaxPayloadSize(int maxPayloadSize)
    {
        if (maxPayloadSize < bufferSize)
        {
            throw new IllegalStateException("Cannot have payload size be smaller than buffer size");
        }
        this.maxPayloadSize = maxPayloadSize;
    }

    public void setMaxTextMessageSize(int maxTextMessageSize)
    {
        this.maxTextMessageSize = maxTextMessageSize;
    }

}
