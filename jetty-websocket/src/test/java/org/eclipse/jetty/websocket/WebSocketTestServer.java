package org.eclipse.jetty.websocket;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import javax.servlet.http.HttpServletRequest;

import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.server.handler.ResourceHandler;
import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.websocket.WebSocketTest.TestWebSocket;

public class WebSocketTestServer extends Server
{
    TestWebSocket _websocket;
    SelectChannelConnector _connector;
    WebSocketHandler _handler;
    ConcurrentLinkedQueue<TestWebSocket> _webSockets= new ConcurrentLinkedQueue<TestWebSocket>();
    
    public WebSocketTestServer()
    {
        _connector = new SelectChannelConnector();
        _connector.setPort(8080);
        
        addConnector(_connector);
        _handler= new WebSocketHandler()
        {
            @Override
            protected WebSocket doWebSocketConnect(HttpServletRequest request, String protocol)
            {
                _websocket = new TestWebSocket();
                return _websocket;
            }
        };
        
        setHandler(_handler);
    }

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    class TestWebSocket implements WebSocket
    {
        Outbound _outbound;

        public void onConnect(Outbound outbound)
        {
            System.err.println("onConnect");
            _outbound=outbound;
            _webSockets.add(this);
            
        }
        
        public void onMessage(byte frame, byte[] data,int offset, int length)
        {
        }

        public void onMessage(final byte frame, final String data)
        {
            System.err.println("onMessage "+data);
            for (TestWebSocket ws : _webSockets)
            {
                if (ws!=this)
                {
                    try
                    {
                        if (ws._outbound.isOpen())
                            ws._outbound.sendMessage(frame,data);
                    }
                    catch (IOException e)
                    {
                        e.printStackTrace();
                    }
                }
            }
        }

        public void onDisconnect()
        {
            _webSockets.remove(this);
        }
    }
    
    
    public static void main(String[] args) 
    {
        try
        {
            WebSocketTestServer server = new WebSocketTestServer();
            server.start();
            server.join();
        }
        catch(Exception e)
        {
            Log.warn(e);
        }
    }
    
    
    
}
