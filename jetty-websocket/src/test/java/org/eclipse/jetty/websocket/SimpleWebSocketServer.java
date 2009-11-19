package org.eclipse.jetty.websocket;

import java.io.IOException;

import javax.servlet.http.HttpServletRequest;

import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.server.handler.ResourceHandler;
import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.websocket.WebSocketTest.TestWebSocket;

public class SimpleWebSocketServer extends Server
{
    TestWebSocket _websocket;
    SelectChannelConnector _connector;
    WebSocketHandler _handler;
    
    public SimpleWebSocketServer()
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
        ResourceHandler rh=new ResourceHandler();
        _handler.setHandler(rh);
        rh.setDirectoriesListed(true);
        rh.setResourceBase("./src/test/resources");
        
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
            
            new Thread()
            {
                public void run()
                {
                    for (int i=0;_outbound.isOpen()&& i<10;i++)
                    {
                        try
                        {
                            Thread.sleep(1000);
                            System.err.println("send "+i);
                            _outbound.sendMessage(SENTINEL_FRAME,"Roger That "+i);
                        }
                        catch (Exception e)
                        {
                            Log.warn(e);
                        }
                    }
                }
            }.start();
        }
        
        public void onMessage(byte frame, byte[] data,int offset, int length)
        {
            System.err.println("onMessage: "+TypeUtil.toHexString(data,offset,length));
        }

        public void onMessage(byte frame, String data)
        {
            System.err.println("onMessage: "+data);
        }

        public void onDisconnect()
        {
            System.err.println("onDisconnect");
        }
    }
    
    
    public static void main(String[] args) 
    {
        try
        {
            SimpleWebSocketServer server = new SimpleWebSocketServer();
            server.start();
            server.join();
        }
        catch(Exception e)
        {
            Log.warn(e);
        }
    }
    
    
    
}
