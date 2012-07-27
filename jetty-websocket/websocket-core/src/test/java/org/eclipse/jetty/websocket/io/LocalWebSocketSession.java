package org.eclipse.jetty.websocket.io;

import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.driver.WebSocketEventDriver;
import org.eclipse.jetty.websocket.protocol.OutgoingFramesCapture;
import org.junit.rules.TestName;

public class LocalWebSocketSession extends WebSocketSession
{
    private String id;
    private OutgoingFramesCapture outgoingCapture;

    public LocalWebSocketSession(TestName testname)
    {
        this(testname,null);
        outgoingCapture = new OutgoingFramesCapture();
        setOutgoing(outgoingCapture);
    }

    public LocalWebSocketSession(TestName testname, WebSocketEventDriver driver)
    {
        super(driver,new LocalWebSocketConnection(testname),WebSocketPolicy.newServerPolicy(),"testing");
        this.id = testname.getMethodName();
    }

    public OutgoingFramesCapture getOutgoingCapture()
    {
        return outgoingCapture;
    }

    @Override
    public String toString()
    {
        return String.format("%s[%s]",LocalWebSocketSession.class.getSimpleName(),id);
    }
}
