package org.eclipse.jetty.websocket.common.events;

import java.io.IOException;
import java.nio.ByteBuffer;

import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.api.extensions.Frame;
import org.eclipse.jetty.websocket.api.extensions.IncomingFrames;
import org.eclipse.jetty.websocket.common.CloseInfo;
import org.eclipse.jetty.websocket.common.WebSocketSession;

public interface EventDriver extends IncomingFrames
{
    public WebSocketPolicy getPolicy();

    public WebSocketSession getSession();

    public void onBinaryFrame(ByteBuffer buffer, boolean fin) throws IOException;

    public void onBinaryMessage(byte[] data);

    public void onClose(CloseInfo close);

    public void onConnect();

    public void onError(Throwable t);

    public void onFrame(Frame frame);

    public void onTextFrame(ByteBuffer buffer, boolean fin) throws IOException;

    public void onTextMessage(String message);

    public void openSession(WebSocketSession session);
}