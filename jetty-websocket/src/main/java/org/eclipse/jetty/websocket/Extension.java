package org.eclipse.jetty.websocket;

public interface Extension extends WebSocketParser.FrameHandler, WebSocketGenerator
{
    public String getExtensionName();
    public int getDataOpcodes();
    public int getControlOpcodes();
    public int getReservedBits();
    
    public void init(WebSocketParser.FrameHandler inbound, WebSocketGenerator outbound,byte[] dataOpCodes, byte[] controlOpcodes, byte[] bitMasks);
    
}
