package org.eclipse.jetty.websocket;

import java.io.IOException;
import java.util.concurrent.ConcurrentLinkedQueue;

import javax.servlet.http.HttpServletRequest;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ResourceHandler;
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
    WebSocketHandler _wsHandler;
    ResourceHandler _rHandler;
    ConcurrentLinkedQueue<TestWebSocket> _broadcast = new ConcurrentLinkedQueue<TestWebSocket>();

    public TestServer(int port)
    {
        _connector = new SelectChannelConnector();
        _connector.setPort(port);

        addConnector(_connector);
        _wsHandler = new WebSocketHandler()
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
                    _websocket = new TestEchoAssembleWebSocket();
                }
                else if ("org.ietf.websocket.test-echo-fragment".equals(protocol))
                {
                    _websocket = new TestEchoFragmentWebSocket();
                }
                else if (protocol==null)
                {
                    _websocket = new TestWebSocket(); 
                }
                return _websocket;
            }
        };

        setHandler(_wsHandler);
        
        _rHandler=new ResourceHandler();
        _rHandler.setDirectoriesListed(true);
        _rHandler.setResourceBase(".");
        _wsHandler.setHandler(_rHandler);
   
    }

    /* ------------------------------------------------------------ */
    public boolean isVerbose()
    {
        return _verbose;
    }

    /* ------------------------------------------------------------ */
    public void setVerbose(boolean verbose)
    {
        _verbose = verbose;
    }

    /* ------------------------------------------------------------ */
    public void setResourceBase(String dir)
    {
        _rHandler.setResourceBase(dir);
    }

    /* ------------------------------------------------------------ */
    public String getResourceBase()
    {
        return _rHandler.getResourceBase();
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
            if (_verbose)
                System.err.printf("%s#onConnect %s\n",this.getClass().getSimpleName(),connection);
        }

        public void onDisconnect(int code,String message)
        {
            if (_verbose)
                System.err.printf("%s#onDisonnect %d %s\n",this.getClass().getSimpleName(),code,message);
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
        public void onConnect(Connection connection)
        {
            super.onConnect(connection);
            _broadcast.add(this);
        }

        @Override
        public void onDisconnect(int code,String message)
        {
            super.onDisconnect(code,message);
            _broadcast.remove(this);
        }
        
        @Override
        public void onMessage(byte[] data, int offset, int length)
        {
            super.onMessage(data,offset,length);
            for (TestWebSocket ws : _broadcast)
            {
                try
                {
                    ws.getOutbound().sendMessage(data,offset,length); 
                }
                catch (IOException e)
                {
                    _broadcast.remove(ws);
                    e.printStackTrace();
                }
            }
        }

        @Override
        public void onMessage(final String data)
        {
            super.onMessage(data);
            for (TestWebSocket ws : _broadcast)
            {
                try
                {
                    ws.getOutbound().sendMessage(data); 
                }
                catch (IOException e)
                {
                    _broadcast.remove(ws);
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
    
    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    class TestEchoFragmentWebSocket extends TestWebSocket
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
                getOutbound().sendFrame((byte)0x0,WebSocketConnectionD06.OP_BINARY,data,offset,length/2); 
                getOutbound().sendFrame((byte)0x8,WebSocketConnectionD06.OP_BINARY,data,offset+length/2,length-length/2); 
            }
            catch (IOException e)
            {
                e.printStackTrace();
            }
        }

        @Override
        public void onMessage(final String message)
        {
            super.onMessage(message);
            try
            {
                byte[] data = message.getBytes(StringUtil.__UTF8);
                int offset=0;
                int length=data.length;
                getOutbound().sendFrame((byte)0x0,WebSocketConnectionD06.OP_TEXT,data,offset,length/2); 
                getOutbound().sendFrame((byte)0x8,WebSocketConnectionD06.OP_TEXT,data,offset+length/2,length-length/2); 
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
        System.err.println("  -p|--port PORT    (default 8080)");
        System.err.println("  -v|--verbose ");
        System.err.println("  -d|--docroot file (default '.')");
        System.exit(1);
    }
    
    public static void main(String... args)
    {
        try
        {
            int port=8080;
            boolean verbose=false;
            String docroot=".";
            
            for (int i=0;i<args.length;i++)
            {
                String a=args[i];
                if ("-p".equals(a)||"--port".equals(a))
                    port=Integer.parseInt(args[++i]);
                else if ("-v".equals(a)||"--verbose".equals(a))
                    verbose=true;
                else if ("-d".equals(a)||"--docroot".equals(a))
                    docroot=args[++i];
                else if (a.startsWith("-"))
                    usage();
            }
            
            
            TestServer server = new TestServer(port);
            server.setVerbose(verbose);
            server.setResourceBase(docroot);
            server.start();
            server.join();
        }
        catch (Exception e)
        {
            Log.warn(e);
        }
    }


}
