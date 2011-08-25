package org.eclipse.jetty.websocket;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jetty.util.BlockingArrayQueue;
import org.eclipse.jetty.util.IO;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class WebSocketClientTest
{
    private ServerSocket _server;
    private int _serverPort;
    
    @Before
    public void startServer() throws IOException {
        _server = new ServerSocket();
        _server.bind(null);
        _serverPort = _server.getLocalPort();
    }
    
    @After
    public void stopServer() throws IOException {
        if(_server != null) {
            _server.close();
        }
    }
    
    @Test
    public void testBadURL() throws Exception
    {
        WebSocketClient client = new WebSocketClient();
        client.start();

        boolean bad=false;
        final AtomicBoolean open = new AtomicBoolean();
        try
        {
            client.open(new URI("http://localhost:8080"),new WebSocket()
            {
                public void onOpen(Connection connection)
                {
                    open.set(true);
                }
                
                public void onClose(int closeCode, String message)
                {}
            });
            
            Assert.fail();
        }
        catch(IllegalArgumentException e)
        {
            bad=true;
        }
        Assert.assertTrue(bad);
        Assert.assertFalse(open.get());
    }

    
    @Test
    public void testAsyncConnectionRefused() throws Exception
    {
        WebSocketClient client = new WebSocketClient();
        client.start();

        final AtomicBoolean open = new AtomicBoolean();
        final AtomicInteger close = new AtomicInteger();

        Future<WebSocket.Connection> future=client.open(new URI("ws://127.0.0.1:1"),new WebSocket()
        {
            public void onOpen(Connection connection)
            {
                open.set(true);
            }

            public void onClose(int closeCode, String message)
            {
                close.set(closeCode);
            }
        });

        Throwable error=null;
        try
        {
            future.get(1,TimeUnit.SECONDS);
            Assert.fail();
        }
        catch(ExecutionException e)
        {
            error=e.getCause();
        }
        
        Assert.assertFalse(open.get());
        Assert.assertEquals(WebSocketConnectionD12.CLOSE_NOCLOSE,close.get());
        Assert.assertTrue(error instanceof ConnectException);
        
    }
    

    
    @Test
    public void testConnectionNotAccepted() throws Exception
    {
        WebSocketClient client = new WebSocketClient();
        client.start();

        final AtomicBoolean open = new AtomicBoolean();
        final AtomicInteger close = new AtomicInteger();
        Future<WebSocket.Connection> future=client.open(new URI("ws://127.0.0.1:"+_serverPort),new WebSocket()
        {
            public void onOpen(Connection connection)
            {
                open.set(true);
            }

            public void onClose(int closeCode, String message)
            {
                close.set(closeCode);
            }
        });


        Throwable error=null;
        try
        {
            future.get(250,TimeUnit.MILLISECONDS);
            Assert.fail();
        }
        catch(TimeoutException e)
        {
            error=e;
        }
        
        Assert.assertFalse(open.get());
        Assert.assertEquals(WebSocketConnectionD12.CLOSE_NOCLOSE,close.get());
        Assert.assertTrue(error instanceof TimeoutException);
        
    }

    @Test
    public void testConnectionTimeout() throws Exception
    {
        WebSocketClient client = new WebSocketClient();
        client.start();

        final AtomicBoolean open = new AtomicBoolean();
        final AtomicInteger close = new AtomicInteger();
        Future<WebSocket.Connection> future=client.open(new URI("ws://127.0.0.1:"+_serverPort),new WebSocket()
        {
            public void onOpen(Connection connection)
            {
                open.set(true);
            }

            public void onClose(int closeCode, String message)
            {
                close.set(closeCode);
            }
        });

        Assert.assertNotNull(_server.accept());

        Throwable error=null;
        try
        {
            future.get(250,TimeUnit.MILLISECONDS);
            Assert.fail();
        }
        catch(TimeoutException e)
        {
            error=e;
        }
        
        Assert.assertFalse(open.get());
        Assert.assertEquals(WebSocketConnectionD12.CLOSE_NOCLOSE,close.get());
        Assert.assertTrue(error instanceof TimeoutException);
        
    }

    
    @Test
    public void testBadHandshake() throws Exception
    {
        WebSocketClient client = new WebSocketClient();
        client.start();

        final AtomicBoolean open = new AtomicBoolean();
        final AtomicInteger close = new AtomicInteger();
        Future<WebSocket.Connection> future=client.open(new URI("ws://127.0.0.1:"+_serverPort+"/"),new WebSocket()
        {
            public void onOpen(Connection connection)
            {
                open.set(true);
            }

            public void onClose(int closeCode, String message)
            {
                close.set(closeCode);
            }
        });

        Socket connection = _server.accept();
        respondToClient(connection, "HTTP/1.1 404 NOT FOUND\r\n\r\n");

        Throwable error=null;
        try
        {
            future.get(250,TimeUnit.MILLISECONDS);
            Assert.fail();
        }
        catch(ExecutionException e)
        {
            error=e.getCause();
        }
        
        Assert.assertFalse(open.get());
        Assert.assertEquals(WebSocketConnectionD12.CLOSE_PROTOCOL,close.get());
        Assert.assertTrue(error instanceof IOException);
        Assert.assertTrue(error.getMessage().indexOf("404 NOT FOUND")>0);
      
    }

    @Test
    public void testBadUpgrade() throws Exception
    {
        WebSocketClient client = new WebSocketClient();
        client.start();

        final AtomicBoolean open = new AtomicBoolean();
        final AtomicInteger close = new AtomicInteger();
        Future<WebSocket.Connection> future=client.open(new URI("ws://127.0.0.1:"+_serverPort+"/"),new WebSocket()
        {
            public void onOpen(Connection connection)
            {
                open.set(true);
            }

            public void onClose(int closeCode, String message)
            {
                close.set(closeCode);
            }
        });

        Socket connection = _server.accept();
        respondToClient(connection,
                "HTTP/1.1 101 Upgrade\r\n" +
                "Sec-WebSocket-Accept: rubbish\r\n" +
                "\r\n" );

        Throwable error=null;
        try
        {
            future.get(250,TimeUnit.MILLISECONDS);
            Assert.fail();
        }
        catch(ExecutionException e)
        {
            error=e.getCause();
        }
        Assert.assertFalse(open.get());
        Assert.assertEquals(WebSocketConnectionD12.CLOSE_PROTOCOL,close.get());
        Assert.assertTrue(error instanceof IOException);
        Assert.assertTrue(error.getMessage().indexOf("Bad Sec-WebSocket-Accept")>=0);
    }

    @Test
    public void testUpgradeThenTCPClose() throws Exception
    {
        WebSocketClient client = new WebSocketClient();
        client.start();

        final AtomicBoolean open = new AtomicBoolean();
        final AtomicInteger close = new AtomicInteger();
        final CountDownLatch _latch = new CountDownLatch(1);
        Future<WebSocket.Connection> future=client.open(new URI("ws://127.0.0.1:"+_serverPort+"/"),new WebSocket()
        {
            public void onOpen(Connection connection)
            {
                open.set(true);
            }

            public void onClose(int closeCode, String message)
            {
                close.set(closeCode);
                _latch.countDown();
            }
        });
        
        Socket socket = _server.accept();
        accept(socket);

        WebSocket.Connection connection = future.get(250,TimeUnit.MILLISECONDS);
        Assert.assertNotNull(connection);
        Assert.assertTrue(open.get());
        Assert.assertEquals(0,close.get());
        
        socket.close();
        _latch.await(10,TimeUnit.SECONDS);

        Assert.assertEquals(WebSocketConnectionD12.CLOSE_NOCLOSE,close.get());
        
    }

    @Test
    public void testIdle() throws Exception
    {
        WebSocketClient client = new WebSocketClient();
        client.setMaxIdleTime(500);
        client.start();

        final AtomicBoolean open = new AtomicBoolean();
        final AtomicInteger close = new AtomicInteger();
        final CountDownLatch _latch = new CountDownLatch(1);
        Future<WebSocket.Connection> future=client.open(new URI("ws://127.0.0.1:"+_serverPort+"/"),new WebSocket()
        {
            public void onOpen(Connection connection)
            {
                open.set(true);
            }

            public void onClose(int closeCode, String message)
            {
                close.set(closeCode);
                _latch.countDown();
            }
        });
        
        Socket socket = _server.accept();
        accept(socket);

        WebSocket.Connection connection = future.get(250,TimeUnit.MILLISECONDS);
        Assert.assertNotNull(connection);
        Assert.assertTrue(open.get());
        Assert.assertEquals(0,close.get());
        
        long start=System.currentTimeMillis();
        _latch.await(10,TimeUnit.SECONDS);
        Assert.assertTrue(System.currentTimeMillis()-start<5000);
        Assert.assertEquals(WebSocketConnectionD12.CLOSE_NORMAL,close.get());
    }
    

    @Test
    public void testNotIdle() throws Exception
    {
        WebSocketClient client = new WebSocketClient();
        client.setMaxIdleTime(500);
        client.start();

        final AtomicBoolean open = new AtomicBoolean();
        final AtomicInteger close = new AtomicInteger();
        final CountDownLatch _latch = new CountDownLatch(1);
        final BlockingQueue<String> queue = new BlockingArrayQueue<String>();
        Future<WebSocket.Connection> future=client.open(new URI("ws://127.0.0.1:"+_serverPort+"/"),new WebSocket.OnTextMessage()
        {
            public void onOpen(Connection connection)
            {
                open.set(true);
            }

            public void onClose(int closeCode, String message)
            {
                close.set(closeCode);
                _latch.countDown();
            }
            
            public void onMessage(String data)
            {
                queue.add(data);
            }
        });
        
        Socket socket = _server.accept();
        accept(socket);

        WebSocket.Connection connection = future.get(250,TimeUnit.MILLISECONDS);
        Assert.assertNotNull(connection);
        Assert.assertTrue(open.get());
        Assert.assertEquals(0,close.get());
        
        
        
        // Send some messages client to server
        byte[] recv = new byte[1024];
        int len=-1;
        for (int i=0;i<10;i++)
        {
            Thread.sleep(250);
            connection.sendMessage("Hello");
            len=socket.getInputStream().read(recv,0,recv.length);
            Assert.assertTrue(len>0);
        }

        // Send some messages server to client
        byte[] send = new byte[] { (byte)0x81, (byte) 0x02, (byte)'H', (byte)'i'};
        
        for (int i=0;i<10;i++)
        {
            Thread.sleep(250);
            socket.getOutputStream().write(send,0,send.length);
            socket.getOutputStream().flush();
            Assert.assertEquals("Hi",queue.poll(1,TimeUnit.SECONDS));
        }

        // Close with code
        long start=System.currentTimeMillis();
        socket.getOutputStream().write(new byte[]{(byte)0x88, (byte) 0x02, (byte)4, (byte)87 },0,4);
        socket.getOutputStream().flush();

        _latch.await(10,TimeUnit.SECONDS);
        Assert.assertTrue(System.currentTimeMillis()-start<5000);
        Assert.assertEquals(1111,close.get());
        
    }
    
    

    private void respondToClient(Socket connection, String serverResponse) throws IOException
    {
        InputStream in = null;
        InputStreamReader isr = null;
        BufferedReader buf = null;
        OutputStream out = null;
        try {
            in = connection.getInputStream();
            isr = new InputStreamReader(in);
            buf = new BufferedReader(isr);
            String line;
            while((line = buf.readLine())!=null) 
            {
                // System.err.println(line);
                if(line.length() == 0) 
                {
                    // Got the "\r\n" line.
                    break;
                }
            }

            // System.out.println("[Server-Out] " + serverResponse);
            out = connection.getOutputStream();
            out.write(serverResponse.getBytes());
            out.flush();
        } 
        finally 
        {
            IO.close(buf);
            IO.close(isr);
            IO.close(in);
            IO.close(out);
        }
    }

    private void accept(Socket connection) throws IOException
    {
        String key="not sent";
        BufferedReader in = new BufferedReader(new InputStreamReader(connection.getInputStream()));
        for (String line=in.readLine();line!=null;line=in.readLine())
        {
            if (line.length()==0)
                break;
            if (line.startsWith("Sec-WebSocket-Key:"))
                key=line.substring(18).trim();
        }
        connection.getOutputStream().write((
                "HTTP/1.1 101 Upgrade\r\n" +
                "Sec-WebSocket-Accept: "+ WebSocketConnectionD12.hashKey(key) +"\r\n" +
                "\r\n").getBytes());
    }
}
