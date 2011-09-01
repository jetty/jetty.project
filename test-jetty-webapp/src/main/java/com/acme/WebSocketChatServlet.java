package com.acme;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

import javax.servlet.RequestDispatcher;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.WebSocket;
import org.eclipse.jetty.websocket.WebSocketServlet;

public class WebSocketChatServlet extends WebSocketServlet
{
    private static final Logger LOG = Log.getLogger(WebSocketChatServlet.class);

    private final Set<ChatWebSocket> _members = new CopyOnWriteArraySet<ChatWebSocket>();
    
    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) 
        throws javax.servlet.ServletException ,IOException 
    {
        getServletContext().getNamedDispatcher("default").forward(request,response);
    };
    
    public WebSocket doWebSocketConnect(HttpServletRequest request, String protocol)
    {
        return new ChatWebSocket();
    }
    
    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    class ChatWebSocket implements WebSocket.OnTextMessage
    {
        Connection _connection;

        public void onOpen(Connection connection)
        {
            // LOG.info(this+" onConnect");
            _connection=connection;
            _members.add(this);
        }
        
        public void onMessage(byte frame, byte[] data,int offset, int length)
        {
            // LOG.info(this+" onMessage: "+TypeUtil.toHexString(data,offset,length));
        }

        public void onMessage(String data)
        {
            if (data.indexOf("disconnect")>=0)
                _connection.disconnect();
            else
            {
                // LOG.info(this+" onMessage: "+data);
                for (ChatWebSocket member : _members)
                {
                    try
                    {
                        member._connection.sendMessage(data);
                    }
                    catch(IOException e)
                    {
                        LOG.warn(e);
                    }
                }
            }
        }
        
        public void onClose(int code, String message)
        {
            // LOG.info(this+" onDisconnect");
            _members.remove(this);
        }

    }
}
