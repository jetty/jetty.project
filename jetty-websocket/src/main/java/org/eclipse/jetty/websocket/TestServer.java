package org.eclipse.jetty.websocket;

import java.io.IOException;
import java.util.concurrent.ConcurrentLinkedQueue;

import javax.servlet.http.HttpServletRequest;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.websocket.WebSocket.Connection;

public class TestServer extends Server
{
    boolean _verbose;

    WebSocket _websocket;
    SelectChannelConnector _connector;
    WebSocketHandler _handler;
    ConcurrentLinkedQueue<TestWebSocket> _webSockets = new ConcurrentLinkedQueue<TestWebSocket>();

    public TestServer(int port)
    {
        _connector = new SelectChannelConnector();
        _connector.setPort(port);

        addConnector(_connector);
        _handler = new WebSocketHandler()
        {
            public WebSocket doWebSocketConnect(HttpServletRequest request, String protocol)
            {
                if ("org.ietf.websocket.test-echo".equals(protocol) || "echo".equals(protocol) || "lws-mirror-protocol".equals(protocol))
                {
                    _websocket = new TestEchoWebSocket();        
                }
                else if ("org.ietf.websocket.test-echo-broadcast".equals(protocol))
                {
                    _websocket = new TestEchoBroadcastWebSocket(); 

                }
                else if ("org.ietf.websocket.test-echo-assemble".equals(protocol))
                {

                }
                else if ("org.ietf.websocket.test-echo-fragment".equals(protocol))
                {

                }
                else if ("org.ietf.websocket.test-consume".equals(protocol))
                {

                }
                else if ("org.ietf.websocket.test-produce".equals(protocol))
                {

                }
                else if (protocol==null)
                {
                    _websocket = new TestWebSocket(); 
                }
                return _websocket;
            }
        };

        setHandler(_handler);
    }

    
    public boolean isVerbose()
    {
        return _verbose;
    }

    public void setVerbose(boolean verbose)
    {
        _verbose = verbose;
    }
    
    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    class TestWebSocket implements WebSocket, WebSocket.OnFrame, WebSocket.OnBinaryMessage, WebSocket.OnTextMessage, WebSocket.OnControl
    {
        protected Connection _connection;
        
        public Connection getOutbound()
        {
            return _connection;
        }
        
        public void onConnect(Connection connection)
        {
            _connection = connection;
            _webSockets.add(this);
        }

        public void onDisconnect(int code,String message)
        {
            _webSockets.remove(this);
        }
        
        public boolean onFrame(byte flags, byte opcode, byte[] data, int offset, int length)
        {            
            if (_verbose)
                System.err.printf("%s#onFrame %s|%s %s\n",this.getClass().getSimpleName(),TypeUtil.toHexString(flags),TypeUtil.toHexString(opcode),TypeUtil.toHexString(data,offset,length));
            return false;
        }

        public boolean onControl(byte controlCode, byte[] data, int offset, int length)
        {
            if (_verbose)
                System.err.printf("%s#onControl  %s %s\n",this.getClass().getSimpleName(),TypeUtil.toHexString(controlCode),TypeUtil.toHexString(data,offset,length));            
            return false;
        }

        public void onMessage(String data)
        {
            if (_verbose)
                System.err.printf("%s#onMessage     %s\n",this.getClass().getSimpleName(),data);
        }

        public void onMessage(byte[] data, int offset, int length)
        {
            if (_verbose)
                System.err.printf("%s#onMessage     %s\n",this.getClass().getSimpleName(),TypeUtil.toHexString(data,offset,length));
        }
    }
    
    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    class TestEchoWebSocket extends TestWebSocket 
    {
        @Override
        public void onConnect(Connection connection)
        {
            super.onConnect(connection);
            connection.setMaxTextMessageSize(-1);
            connection.setMaxBinaryMessageSize(-1);
        }
        
        @Override
        public boolean onFrame(byte flags, byte opcode, byte[] data, int offset, int length)
        {
            super.onFrame(flags,opcode,data,offset,length);
            try
            {
                switch(opcode)
                {
                    case WebSocketConnectionD06.OP_CLOSE:
                    case WebSocketConnectionD06.OP_PING:
                    case WebSocketConnectionD06.OP_PONG:
                        break;
                    default:
                        getOutbound().sendFrame(flags,opcode,data,offset,length); 
                }
            }
            catch (IOException e)
            {
                e.printStackTrace();
            }
            
            return false;
        }
    }
    
    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    class TestEchoBroadcastWebSocket extends TestWebSocket
    {
        @Override
        public void onMessage(byte[] data, int offset, int length)
        {
            super.onMessage(data,offset,length);
            for (TestWebSocket ws : _webSockets)
            {
                try
                {
                    ws.getOutbound().sendMessage(data,offset,length); 
                }
                catch (IOException e)
                {
                    e.printStackTrace();
                }
            }
        }

        @Override
        public void onMessage(final String data)
        {
            super.onMessage(data);
            for (TestWebSocket ws : _webSockets)
            {
                try
                {
                    ws.getOutbound().sendMessage(data); 
                }
                catch (IOException e)
                {
                    e.printStackTrace();
                }
            }
        }
    }
    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    class TestEchoAssembleWebSocket extends TestWebSocket
    {
        
        @Override
        public void onConnect(Connection connection)
        {
            super.onConnect(connection);
            connection.setMaxTextMessageSize(64*1024);
            connection.setMaxBinaryMessageSize(64*1024);
        }

        @Override
        public void onMessage(byte[] data, int offset, int length)
        {
            super.onMessage(data,offset,length);
            try
            {
                getOutbound().sendMessage(data,offset,length); 
            }
            catch (IOException e)
            {
                e.printStackTrace();
            }
        }

        @Override
        public void onMessage(final String data)
        {
            super.onMessage(data);
            try
            {
                getOutbound().sendMessage(data); 
            }
            catch (IOException e)
            {
                e.printStackTrace();
            }
        }
    }

    private static void usage()
    {
        System.err.println("java -cp CLASSPATH "+TestServer.class+" [ OPTIONS ]");
        System.err.println("  -p|--port PORT ");
        System.err.println("  -v|--verbose ");
        System.exit(1);
    }
    
    public static void main(String[] args)
    {
        try
        {
            int port=8080;
            boolean verbose=false;
            
            for (int i=0;i<args.length;i++)
            {
                String a=args[i];
                if ("-p".equals(a)||"--port".equals(a))
                    port=Integer.parseInt(args[++i]);
                else if ("-v".equals(a)||"--verbose".equals(a))
                    verbose=true;
                else if (a.startsWith("-"))
                    usage();
            }
            
            
            TestServer server = new TestServer(port);
            server.setVerbose(verbose);
            server.start();
            server.join();
        }
        catch (Exception e)
        {
            Log.warn(e);
        }
    }


}
