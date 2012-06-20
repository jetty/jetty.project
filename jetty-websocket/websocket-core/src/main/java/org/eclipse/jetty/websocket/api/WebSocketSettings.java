package org.eclipse.jetty.websocket.api;

import org.eclipse.jetty.websocket.masks.Masker;

/**
 * Settings for WebSocket operations.
 */
public class WebSocketSettings
{
    /**
     * The maximum size of a text message during parsing/generating
     */
    private int maxTextMessageSize = -1;
    /**
     * The maximum size of a binary message during parsing/generating
     */
    private int maxBinaryMessageSize = -1;

    /**
     * The implementation for masking
     */
    private Masker masker = null;
    
    public int getMaxBinaryMessageSize()
    {
        return maxBinaryMessageSize;
    }

    public int getMaxTextMessageSize()
    {
        return maxTextMessageSize;
    }

    public void setMaxBinaryMessageSize(int maxBinaryMessageSize)
    {
        this.maxBinaryMessageSize = maxBinaryMessageSize;
    }

    public void setMaxTextMessageSize(int maxTextMessageSize)
    {
        this.maxTextMessageSize = maxTextMessageSize;
    }

    public Masker getMaskGen()
    {
        return masker;
    }

    public void setMaskGen(Masker masker)
    {
        this.masker = masker;
    }
    
}
