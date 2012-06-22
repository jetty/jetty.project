package org.eclipse.jetty.websocket.api;

import java.nio.ByteBuffer;
import java.util.concurrent.Future;

import org.eclipse.jetty.websocket.frames.BaseFrame;
import org.eclipse.jetty.websocket.frames.BinaryFrame;
import org.eclipse.jetty.websocket.frames.CloseFrame;
import org.eclipse.jetty.websocket.frames.ControlFrame;
import org.eclipse.jetty.websocket.frames.DataFrame;
import org.eclipse.jetty.websocket.frames.PingFrame;
import org.eclipse.jetty.websocket.frames.TextFrame;


/**
 * Constants for WebSocket protocol as-defined in <a href="https://tools.ietf.org/html/rfc6455">RFC-6455</a>.
 */
public interface WebSocket
{
    /**
     * Per <a href="https://tools.ietf.org/html/rfc6455#section-1.3">RFC 6455, section 1.3</a> - Opening Handshake - this version is "13"
     */
    public final static int VERSION = 13;
    
    /*
     *  Connection
     *    - void terminateConnection();
     *    - void terminateConnection(int statusCode);
     *    - void terminateConnection(int statusCode, String reason);
     *    - FutureCallback<Void> sendFrame(BaseFrame frame)
     *    - FutureCallback<Void> sendText(String text)
     *    - FutureCallback<Void> sendBinary(ByteBuffer payload)
     *    - FutureCallback<Void> sendBinary(byte buf[], int offset, int len)
     *    
     *  @OnWebSocketHandshake
     *  public void {methodname}(Connection conn)
     *  
     *  @OnWebSocketConnect
     *  public void {methodname}(Connection conn)
     *  
     *  @OnWebSocketDisconnect
     *  public void {methodname}(Connection conn)
     *    
     *  @OnWebSocketFrame(type=CloseFrame.class)
     *  public void {methodname}(CloseFrame frame);
     *  
     *  @OnWebSocketText
     *  public void {methodname}(String text);
     *  
     *  @OnWebSocketBinary
     *  public void {methodname}(byte buf[], int offset, int length);
     *  public void {methodname}(ByteBuffer buffer);
     *  
     *  @OnWebSocketClose
     *  public void {methodnamne}(int statusCode, String reason);
     */

    void onBinaryFrame(BinaryFrame frame);

    void onClose(int statusCode, String reason);
    void onCloseFrame(CloseFrame frame);

    void onControlFrame(ControlFrame frame);
    void onDataFrame(DataFrame frame);
    void onHandshake(/* what params? */);

    void onOpen();
    void onTextFrame(TextFrame frame);

    void sendBinary(byte buf[]);
    void sendBinary(ByteBuffer buffer);

    Future<Runnable> sendFrame(BaseFrame frame);
    void sendPong(PingFrame frame);
    void sendText(String message);
}
