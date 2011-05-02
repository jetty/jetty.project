package org.eclipse.jetty.websocket;

import java.util.Map;

public interface Extension extends WebSocketParser.FrameHandler, WebSocketGenerator
{
    public String getName();
    public String getParameterizedName();
    public int getDataOpcodes();
    public int getControlOpcodes();
    public int getReservedBits();
    
    public boolean init(Map<String,String> parameters);
    public void bind(WebSocket.FrameConnection connection, WebSocketParser.FrameHandler inbound, WebSocketGenerator outbound,byte[] dataOpCodes, byte[] controlOpcodes, byte[] bitMasks);
    
}
