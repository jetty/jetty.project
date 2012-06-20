package org.eclipse.jetty.websocket.api;

import org.eclipse.jetty.websocket.masks.Masker;

/**
 * Settings for WebSocket operations.
 */
public class WebSocketPolicy
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

    /**
     * Behavior of the websockets
     */
    private WebSocketBehavior behavior = WebSocketBehavior.SERVER; // TODO: Review default

    public WebSocketBehavior getBehavior()
    {
        return behavior;
    }

    public Masker getMasker()
    {
        return masker;
    }

    public int getMaxBinaryMessageSize()
    {
        return maxBinaryMessageSize;
    }

    public int getMaxTextMessageSize()
    {
        return maxTextMessageSize;
    }

    public void setBehavior(WebSocketBehavior behavior)
    {
        this.behavior = behavior;
    }

    public void setMasker(Masker masker)
    {
        this.masker = masker;
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
