package examples;

import java.io.IOException;

import javax.servlet.http.HttpSession;
import javax.websocket.EndpointConfig;
import javax.websocket.OnMessage;
import javax.websocket.OnOpen;
import javax.websocket.Session;
import javax.websocket.server.ServerEndpoint;

@ServerEndpoint(value = "/example", configurator = GetHttpSessionConfigurator.class)
public class GetHttpSessionSocket
{
    private Session wsSession;
    private HttpSession httpSession;
    
    @OnOpen
    public void open(Session session, EndpointConfig config) {
        this.wsSession = session;
        this.httpSession = (HttpSession)config.getUserProperties().get(HttpSession.class.getName());
    }
    
    @OnMessage
    public void echo(String msg) throws IOException {
        wsSession.getBasicRemote().sendText(msg);
    }
}
