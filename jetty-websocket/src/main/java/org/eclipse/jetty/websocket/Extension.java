package org.eclipse.jetty.websocket;

import java.util.Map;

public interface Extension extends WebSocketParser.FrameHandler, WebSocketGenerator
{
    public String getName();
    public String getParameterizedName();
    
    public boolean init(Map<String,String> parameters);
    public void bind(WebSocket.FrameConnection connection, WebSocketParser.FrameHandler inbound, WebSocketGenerator outbound);
    
}
