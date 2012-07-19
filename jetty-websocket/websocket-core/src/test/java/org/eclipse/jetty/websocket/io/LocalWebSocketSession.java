package org.eclipse.jetty.websocket.io;

import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.driver.WebSocketEventDriver;
import org.junit.rules.TestName;

public class LocalWebSocketSession extends WebSocketSession
{
    private String id;

    public LocalWebSocketSession(TestName testname)
    {
        this(testname,null);
    }

    public LocalWebSocketSession(TestName testname, WebSocketEventDriver driver)
    {
        super(driver,new LocalWebSocketConnection(testname),WebSocketPolicy.newServerPolicy(),"testing");
        this.id = testname.getMethodName();
    }

    @Override
    public String toString()
    {
        return String.format("%s[%s]",LocalWebSocketSession.class.getSimpleName(),id);
    }
}
