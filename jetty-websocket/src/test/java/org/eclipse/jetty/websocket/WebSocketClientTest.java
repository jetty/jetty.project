package org.eclipse.jetty.websocket;

import static org.hamcrest.CoreMatchers.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Exchanger;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.util.BlockingArrayQueue;
import org.eclipse.jetty.util.IO;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class WebSocketClientTest
{
    private ServerSocket server;
    private int serverPort;
    
    @Before
    public void startServer() throws IOException {
        server = new ServerSocket();
        server.bind(null);
        serverPort = server.getLocalPort();
    }
    
    @After
    public void stopServer() throws IOException {
        if(server != null) {
            server.close();
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

                public void onError(String message, Throwable ex)
                {
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
    public void testBlockingConnectionRefused() throws Exception
    {
        WebSocketClient client = new WebSocketClient();
        client.start();
        client.setBlockingConnect(true);

        boolean bad=false;
        final AtomicBoolean open = new AtomicBoolean();
        try
        {
            client.open(new URI("ws://127.0.0.1:1"),new WebSocket()
            {
                public void onOpen(Connection connection)
                {
                    open.set(true);
                }

                public void onError(String message, Throwable ex)
                {
                }

                public void onClose(int closeCode, String message)
                {}
            });
            
            Assert.fail();
        }
        catch(IOException e)
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
        client.setConnectTimeout(1000);
        client.start();
        client.setBlockingConnect(false);

        boolean bad=false;
        final AtomicBoolean open = new AtomicBoolean();
        final AtomicReference<String> error = new AtomicReference<String>(null);
        final AtomicInteger close = new AtomicInteger();
        final CountDownLatch latch = new CountDownLatch(1);
        try
        {
            client.open(new URI("ws://127.0.0.1:1"),new WebSocket()
            {
                public void onOpen(Connection connection)
                {
                    open.set(true);
                    latch.countDown();
                }

                public void onError(String message, Throwable ex)
                {
                    error.set(message);
                    latch.countDown();
                }

                public void onClose(int closeCode, String message)
                {
                    close.set(closeCode);
                    latch.countDown();
                }
            });
        }
        catch(IOException e)
        {
            bad=true;
        }
        
        Assert.assertFalse(bad);
        Assert.assertFalse(open.get());
        Assert.assertTrue(latch.await(1,TimeUnit.SECONDS));
        Assert.assertNotNull(error.get());
        
    }
    
    @Test
    public void testBlockingConnectionNotAccepted() throws Exception
    {
        WebSocketClient client = new WebSocketClient();
        client.setConnectTimeout(500);
        client.setBlockingConnect(true);
        client.start();

        boolean bad=false;
        final AtomicReference<String> error = new AtomicReference<String>(null);
        final CountDownLatch latch = new CountDownLatch(1);
        try
        {
            client.open(new URI("ws://127.0.0.1:"+serverPort),new WebSocket()
            {
                public void onOpen(Connection connection)
                {
                    latch.countDown();
                }

                public void onError(String message, Throwable ex)
                {
                    error.set(message);
                    latch.countDown();
                }

                public void onClose(int closeCode, String message)
                {
                    latch.countDown();
                }
            });
        }
        catch(IOException e)
        {
            e.printStackTrace();
            bad=true;
        }

        Assert.assertTrue(latch.await(1,TimeUnit.SECONDS));
        Assert.assertTrue(bad||error.get()!=null);
    }
    
    @Test
    public void testAsyncConnectionNotAccepted() throws Exception
    {
        WebSocketClient client = new WebSocketClient();
        client.setBlockingConnect(true);
        client.setConnectTimeout(300);
        client.start();

        boolean bad=false;
        final AtomicBoolean open = new AtomicBoolean();
        final AtomicReference<String> error = new AtomicReference<String>(null);
        final AtomicInteger close = new AtomicInteger();
        final CountDownLatch latch = new CountDownLatch(1);
        try
        {
            client.open(new URI("ws://127.0.0.1:"+serverPort),new WebSocket()
            {
                public void onOpen(Connection connection)
                {
                    open.set(true);
                    latch.countDown();
                }

                public void onError(String message, Throwable ex)
                {
                    error.set(message);
                    latch.countDown();
                }

                public void onClose(int closeCode, String message)
                {
                    close.set(closeCode);
                    latch.countDown();
                }
            });
        }
        catch(IOException e)
        {
            bad=true;
        }
        
        Assert.assertFalse(bad);
        Assert.assertFalse(open.get());
        Assert.assertTrue(latch.await(1,TimeUnit.SECONDS));
        Assert.assertNotNull(error.get());
    }
    
    @Test
    public void testBlockingConnectionTimeout() throws Exception
    {
        WebSocketClient client = new WebSocketClient();
        client.setConnectTimeout(500);
        client.setBlockingConnect(true);
        client.start();

        boolean bad=false;
        final AtomicReference<String> error = new AtomicReference<String>(null);
        final CountDownLatch latch = new CountDownLatch(1);
        try
        {
            client.open(new URI("ws://127.0.0.1:"+serverPort),new WebSocket()
            {
                public void onOpen(Connection connection)
                {
                    latch.countDown();
                }

                public void onError(String message, Throwable ex)
                {
                    error.set(message);
                    latch.countDown();
                }

                public void onClose(int closeCode, String message)
                {
                    latch.countDown();
                }
            });
        }
        catch(IOException e)
        {
            e.printStackTrace();
            bad=true;
        }
        
        Assert.assertNotNull(server.accept());

        Assert.assertTrue(latch.await(1,TimeUnit.SECONDS));
        Assert.assertTrue(bad||error.get()!=null);
    }
    
    @Test
    public void testAsyncConnectionTimeout() throws Exception
    {
        WebSocketClient client = new WebSocketClient();
        client.setBlockingConnect(true);
        client.setConnectTimeout(300);
        client.start();

        boolean bad=false;
        final AtomicBoolean open = new AtomicBoolean();
        final AtomicReference<String> error = new AtomicReference<String>(null);
        final AtomicInteger close = new AtomicInteger();
        final CountDownLatch latch = new CountDownLatch(1);
        try
        {
            client.open(new URI("ws://127.0.0.1:"+serverPort),new WebSocket()
            {
                public void onOpen(Connection connection)
                {
                    open.set(true);
                    latch.countDown();
                }

                public void onError(String message, Throwable ex)
                {
                    error.set(message);
                    latch.countDown();
                }

                public void onClose(int closeCode, String message)
                {
                    close.set(closeCode);
                    latch.countDown();
                }
            });
        }
        catch(IOException e)
        {
            bad=true;
        }
        Assert.assertNotNull(server.accept());
        
        Assert.assertFalse(bad);
        Assert.assertFalse(open.get());
        Assert.assertTrue(latch.await(1,TimeUnit.SECONDS));
        Assert.assertNotNull(error.get());
    }
    
    
    @Test
    public void testBadHandshake() throws Exception
    {
        WebSocketClient client = new WebSocketClient();
        client.setBlockingConnect(true);
        client.setConnectTimeout(300);
        client.start();

        final AtomicBoolean open = new AtomicBoolean();
        final AtomicReference<String> error = new AtomicReference<String>(null);
        final AtomicInteger close = new AtomicInteger();
        final CountDownLatch latch = new CountDownLatch(1);
        
        client.open(new URI("ws://127.0.0.1:"+serverPort),new WebSocket()
        {
            public void onOpen(Connection connection)
            {
                System.out.printf("onOpen(%s)%n", connection);
                open.set(true);
                latch.countDown();
            }

            public void onError(String message, Throwable ex)
            {
                System.out.printf("onError(%s, %s)%n", message, ex);
                error.set(message);
                latch.countDown();
            }

            public void onClose(int closeCode, String message)
            {
                System.out.printf("onClose(%d, %s)%n", closeCode, message);
                close.set(closeCode);
                latch.countDown();
            }
        });
        
        Socket connection = server.accept();
        consumeClientRequest(connection);

        write(connection, "HTTP/1.1 404 NOT FOUND\r\n\r\n");
        
        Assert.assertFalse(open.get());
        Assert.assertTrue(latch.await(50,TimeUnit.SECONDS));
        Assert.assertThat("error.get()", error.get(), notNullValue());
    }
    
    private void consumeClientRequest(Socket connection) throws IOException
    {
        InputStream in = null;
        InputStreamReader isr = null;
        BufferedReader buf = null;
        try {
            in = connection.getInputStream();
            isr = new InputStreamReader(in);
            buf = new BufferedReader(isr);
            String line;
            while((line = buf.readLine())!=null) {
                System.err.println(line);
            }
        } finally {
            IO.close(buf);
            IO.close(isr);
            IO.close(in);
        }
    }

    @Test
    public void testBadUpgrade() throws Exception
    {
        WebSocketClient client = new WebSocketClient();
        client.setBlockingConnect(true);
        client.setConnectTimeout(10000);
        client.start();

        boolean bad=false;
        final AtomicBoolean open = new AtomicBoolean();
        final AtomicReference<String> error = new AtomicReference<String>(null);
        final AtomicInteger close = new AtomicInteger();
        final CountDownLatch latch = new CountDownLatch(1);
        try
        {
            client.open(new URI("ws://127.0.0.1:"+serverPort),new WebSocket()
            {
                public void onOpen(Connection connection)
                {
                    open.set(true);
                    latch.countDown();
                }

                public void onError(String message, Throwable ex)
                {
                    error.set(message);
                    latch.countDown();
                }

                public void onClose(int closeCode, String message)
                {
                    close.set(closeCode);
                    latch.countDown();
                }
            });
        }
        catch(IOException e)
        {
            bad=true;
        }
        
        Socket connection = server.accept();
        BufferedReader in = new BufferedReader(new InputStreamReader(connection.getInputStream()));
        for (String line=in.readLine();line!=null;line=in.readLine())
        {
            // System.err.println(line);
            if (line.length()==0)
                break;
        }
        
        connection.getOutputStream().write((
                "HTTP/1.1 101 Upgrade\r\n" +
                "Sec-WebSocket-Accept: rubbish\r\n" +
                "\r\n").getBytes());
        
        Assert.assertFalse(bad);
        Assert.assertFalse(open.get());
        Assert.assertTrue(latch.await(1,TimeUnit.SECONDS));
        Assert.assertNotNull(error.get());
    }

    
    @Test
    public void testUpgrade() throws Exception
    {
        WebSocketClient client = new WebSocketClient();
        client.setBlockingConnect(true);
        client.setConnectTimeout(10000);
        client.start();

        boolean bad=false;
        final AtomicBoolean open = new AtomicBoolean();
        final AtomicReference<String> error = new AtomicReference<String>(null);
        final AtomicInteger close = new AtomicInteger();
        final CountDownLatch latch = new CountDownLatch(1);
        try
        {
            client.open(new URI("ws://127.0.0.1:"+serverPort),new WebSocket()
            {
                public void onOpen(Connection connection)
                {
                    open.set(true);
                    latch.countDown();
                }

                public void onError(String message, Throwable ex)
                {
                    error.set(message);
                    latch.countDown();
                }

                public void onClose(int closeCode, String message)
                {
                    close.set(closeCode);
                    latch.countDown();
                }
            });
        }
        catch(IOException e)
        {
            bad=true;
        }
        Assert.assertFalse(bad);
        
        String key="not sent";
        Socket connection = server.accept();
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
                "Sec-WebSocket-Accept: "+ WebSocketConnectionD10.hashKey(key) +"\r\n" +
                "\r\n").getBytes());

        Assert.assertTrue(latch.await(1,TimeUnit.SECONDS));
        Assert.assertNull(error.get());
        Assert.assertTrue(open.get());
    }

    @Test
    public void testIdle() throws Exception
    {
        WebSocketClient client = new WebSocketClient();
        client.setBlockingConnect(true);
        client.setConnectTimeout(10000);
        client.setMaxIdleTime(500);
        client.start();

        boolean bad=false;
        final AtomicBoolean open = new AtomicBoolean();
        final AtomicInteger close = new AtomicInteger();
        final CountDownLatch latch = new CountDownLatch(2);
        try
        {
            client.open(new URI("ws://127.0.0.1:"+serverPort),new WebSocket()
            {
                public void onOpen(Connection connection)
                {
                    open.set(true);
                    latch.countDown();
                }

                public void onError(String message, Throwable ex)
                {
                    latch.countDown();
                }

                public void onClose(int closeCode, String message)
                {
                    close.set(closeCode);
                    latch.countDown();
                }
            });
        }
        catch(IOException e)
        {
            bad=true;
        }
        Assert.assertFalse(bad);
        
        String key="not sent";
        Socket connection = server.accept();
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
                "Sec-WebSocket-Accept: "+ WebSocketConnectionD10.hashKey(key) +"\r\n" +
                "\r\n").getBytes());

        Assert.assertTrue(latch.await(10,TimeUnit.SECONDS));
        Assert.assertTrue(open.get());
        Assert.assertEquals(WebSocketConnectionD10.CLOSE_NORMAL,close.get());
    }
    

    @Test
    public void testNotIdle() throws Exception
    {
        WebSocketClient client = new WebSocketClient();
        client.setBlockingConnect(true);
        client.setConnectTimeout(10000);
        client.setMaxIdleTime(500);
        client.start();

        boolean bad=false;
        final AtomicBoolean open = new AtomicBoolean();
        final Exchanger<Integer> close = new Exchanger<Integer>();
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<WebSocket.Connection> connection = new AtomicReference<WebSocket.Connection>();
        final BlockingQueue<String> queue = new BlockingArrayQueue<String>();
        try
        {
            client.open(new URI("ws://127.0.0.1:"+serverPort),new WebSocket.OnTextMessage()
            {
                public void onOpen(Connection c)
                {
                    open.set(true);
                    connection.set(c);
                    latch.countDown();
                }

                public void onError(String message, Throwable ex)
                {
                    latch.countDown();
                }

                public void onClose(int closeCode, String message)
                {
                    try
                    {
                        close.exchange(closeCode);
                    }
                    catch(InterruptedException ex)
                    {}
                    latch.countDown();
                }
                
                public void onMessage(String data)
                {
                    queue.add(data);
                }
            });
        }
        catch(IOException e)
        {
            bad=true;
        }
        Assert.assertFalse(bad);
        
        String key="not sent";
        Socket socket = server.accept();
        BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        for (String line=in.readLine();line!=null;line=in.readLine())
        {
            if (line.length()==0)
                break;
            if (line.startsWith("Sec-WebSocket-Key:"))
                key=line.substring(18).trim();
        }
        socket.getOutputStream().write((
                "HTTP/1.1 101 Upgrade\r\n" +
                "Sec-WebSocket-Accept: "+ WebSocketConnectionD10.hashKey(key) +"\r\n" +
                "\r\n").getBytes());

        Assert.assertTrue(latch.await(10,TimeUnit.SECONDS));
        Assert.assertTrue(open.get());
        
        // Send some messages client to server
        byte[] recv = new byte[1024];
        int len=-1;
        for (int i=0;i<10;i++)
        {
            Thread.sleep(250);
            connection.get().sendMessage("Hello");
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

        socket.getOutputStream().write(new byte[]{(byte)0x88, (byte) 0x02, (byte)4, (byte)87 },0,4);
        socket.getOutputStream().flush();
        
        Assert.assertEquals(new Integer(1111),close.exchange(null,1,TimeUnit.SECONDS));
    }
    
    private void write(Socket connection, String str) throws IOException
    {
        write(connection, str.getBytes());
    }

    private void write(Socket connection, byte buffer[]) throws IOException
    {
        OutputStream out = null;
        try {
            out = connection.getOutputStream();
            out.write(buffer);
            out.flush();
        } finally {
            IO.close(out);
        }
    }
}
