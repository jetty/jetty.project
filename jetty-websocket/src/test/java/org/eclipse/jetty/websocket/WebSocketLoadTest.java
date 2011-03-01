package org.eclipse.jetty.websocket;

import static org.junit.Assert.assertTrue;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.lang.management.ManagementFactory;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.servlet.http.HttpServletRequest;

import junit.framework.Assert;

import org.eclipse.jetty.io.Buffer;
import org.eclipse.jetty.io.bio.SocketEndPoint;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * @version $Revision$ $Date$
 */
public class WebSocketLoadTest
{
    private static Server _server;
    private static Connector _connector;

    @BeforeClass
    public static void startServer() throws Exception
    {
        _server = new Server();

        _connector = new SelectChannelConnector();
        _server.addConnector(_connector);

        QueuedThreadPool threadPool = new QueuedThreadPool(200);
        threadPool.setMaxStopTimeMs(1000);
        _server.setThreadPool(threadPool);

        WebSocketHandler wsHandler = new WebSocketHandler()
        {
            @Override
            protected WebSocket doWebSocketConnect(HttpServletRequest request, String protocol)
            {
                return new EchoWebSocket();
            }
        };
        wsHandler.setHandler(new DefaultHandler());
        _server.setHandler(wsHandler);

        _server.start();
    }

    @AfterClass
    public static void stopServer() throws Exception
    {
        _server.stop();
        _server.join();
    }

    @Test
    public void testLoad() throws Exception
    {
        int count = 50;
        int iterations = 100;

        ExecutorService threadPool = Executors.newCachedThreadPool();
        try
        {
            CountDownLatch latch = new CountDownLatch(count * iterations);
            WebSocketClient[] clients = new WebSocketClient[count];
            for (int i = 0; i < clients.length; ++i)
            {
                clients[i] = new WebSocketClient("localhost", _connector.getLocalPort(), 1000, latch, iterations);
                clients[i].open();
            }

            long start = System.nanoTime();
            for (WebSocketClient client : clients)
                threadPool.execute(client);

            int parallelism = ManagementFactory.getOperatingSystemMXBean().getAvailableProcessors();
            long maxTimePerIteration = 5;
            assertTrue(latch.await(iterations * (count / parallelism + 1) * maxTimePerIteration, TimeUnit.MILLISECONDS));
            long end = System.nanoTime();
            // System.err.println("Elapsed: " + TimeUnit.NANOSECONDS.toMillis(end - start) + " ms");

            for (WebSocketClient client : clients)
                client.close();
        }
        finally
        {
            threadPool.shutdown();
            assertTrue(threadPool.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    private static class EchoWebSocket implements WebSocket
    {
        private volatile Outbound outbound;

        public void onConnect(Outbound outbound)
        {
            this.outbound = outbound;
        }

        public void onMessage(byte frame, String data)
        {
            try
            {
                // System.err.println(">> "+data);
                outbound.sendMessage(data);
            }
            catch (IOException x)
            {
                outbound.disconnect();
            }
        }

        public void onFragment(boolean more, byte opcode, byte[] data, int offset, int length)
        {
        }

        public void onMessage(byte frame, byte[] data, int offset, int length)
        {
        }

        public void onDisconnect()
        {
        }
    }

    private class WebSocketClient implements Runnable
    {
        private final Socket socket;
        private final BufferedWriter output;
        private final BufferedReader input;
        private final int iterations;
        private final CountDownLatch latch;
        private final SocketEndPoint _endp;
        private final WebSocketGeneratorD06 _generator;
        private final WebSocketParserD06 _parser;
        private final WebSocketParser.FrameHandler _handler = new WebSocketParser.FrameHandler()
        {
            public void onFrame(boolean more, byte flags, byte opcode, Buffer buffer)
            {
                _response=buffer;
            }
        };
        private volatile Buffer _response;
        
        public WebSocketClient(String host, int port, int readTimeout, CountDownLatch latch, int iterations) throws IOException
        {
            this.latch = latch;
            socket = new Socket(host, port);
            socket.setSoTimeout(readTimeout);
            output = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), "ISO-8859-1"));
            input = new BufferedReader(new InputStreamReader(socket.getInputStream(), "ISO-8859-1"));
            this.iterations = iterations;
            
            _endp=new SocketEndPoint(socket);
            _generator = new WebSocketGeneratorD06(new WebSocketBuffers(32*1024),_endp,new WebSocketGeneratorD06.FixedMaskGen());
            _parser = new WebSocketParserD06(new WebSocketBuffers(32*1024),_endp,_handler,false);
            
        }

        private void open() throws IOException
        {
            output.write("GET /chat HTTP/1.1\r\n"+
                    "Host: server.example.com\r\n"+
                    "Upgrade: websocket\r\n"+
                    "Connection: Upgrade\r\n"+
                    "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n"+
                    "Sec-WebSocket-Origin: http://example.com\r\n"+
                    "Sec-WebSocket-Protocol: onConnect\r\n" +
                    "Sec-WebSocket-Version: 6\r\n"+
                    "\r\n");
            output.flush();

            String responseLine = input.readLine();
            assertTrue(responseLine.startsWith("HTTP/1.1 101 Switching Protocols"));
            // Read until we find an empty line, which signals the end of the http response
            String line;
            while ((line = input.readLine()) != null)
                if (line.length() == 0)
                    break;
        }

        public void run()
        {
            try
            {
                String message = "0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF";
                for (int i = 0; i < iterations; ++i)
                {
                    _generator.addFrame(WebSocket.OP_TEXT,message,10000);
                    _generator.flush(10000);
                    
                    //System.err.println("-> "+message);
                    
                    _response=null;
                    while(_response==null)
                        _parser.parseNext();
                    //System.err.println("<- "+_response);
                    Assert.assertEquals(message,_response.toString());
                    latch.countDown();
                }
            }
            catch (IOException x)
            {
                throw new RuntimeException(x);
            }
        }


        public void close() throws IOException
        {
            socket.close();
        }
    }
}
