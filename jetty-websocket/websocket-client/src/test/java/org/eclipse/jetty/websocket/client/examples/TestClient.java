//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.websocket.client.examples;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.WebSocketAdapter;
import org.eclipse.jetty.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.common.OpCode;
import org.eclipse.jetty.websocket.common.test.LeakTrackingBufferPoolRule;

/**
 * This is not a general purpose websocket client. It's only for testing the websocket server and is hardwired to a specific draft version of the protocol.
 */
public class TestClient
{
    public class TestSocket extends WebSocketAdapter
    {
        @Override
        public void onWebSocketBinary(byte[] payload, int offset, int len)
        {
        }

        @Override
        public void onWebSocketClose(int statusCode, String reason)
        {
            super.onWebSocketClose(statusCode,reason);
        }

        @Override
        public void onWebSocketConnect(Session session)
        {
            if (_verbose)
            {
                System.err.printf("%s#onWebSocketConnect %s %s\n",this.getClass().getSimpleName(),session,session.getClass().getSimpleName());
            }
        }

        public void send(byte op, byte[] data, int maxFragmentLength)
        {
            _starts.add(System.nanoTime());

            int off = 0;
            int len = data.length;
            if ((maxFragmentLength > 0) && (len > maxFragmentLength))
            {
                len = maxFragmentLength;
            }
            __messagesSent++;
            while (off < data.length)
            {
                __framesSent++;

                off += len;
                if ((data.length - off) > len)
                {
                    len = data.length - off;
                }
                if ((maxFragmentLength > 0) && (len > maxFragmentLength))
                {
                    len = maxFragmentLength;
                }
            }
        }

    }

    private static boolean _verbose = false;

    private static final Random __random = new Random();

    private static LeakTrackingBufferPoolRule bufferPool = new LeakTrackingBufferPoolRule("TestClient");

    private final String _host;
    private final int _port;
    private final String _protocol;
    private final int _timeout;

    private static int __framesSent;
    private static int __messagesSent;
    private static AtomicInteger __framesReceived = new AtomicInteger();
    private static AtomicInteger __messagesReceived = new AtomicInteger();

    private static AtomicLong __totalTime = new AtomicLong();
    private static AtomicLong __minDuration = new AtomicLong(Long.MAX_VALUE);
    private static AtomicLong __maxDuration = new AtomicLong(Long.MIN_VALUE);
    private static long __start;

    public static void main(String[] args) throws Exception
    {
        String host = "localhost";
        int port = 8080;
        String protocol = null;
        int count = 10;
        int size = 64;
        int fragment = 4000;
        boolean binary = false;
        int clients = 1;
        int delay = 1000;

        for (int i = 0; i < args.length; i++)
        {
            String a = args[i];
            if ("-p".equals(a) || "--port".equals(a))
            {
                port = Integer.parseInt(args[++i]);
            }
            else if ("-h".equals(a) || "--host".equals(a))
            {
                host = args[++i];
            }
            else if ("-c".equals(a) || "--count".equals(a))
            {
                count = Integer.parseInt(args[++i]);
            }
            else if ("-s".equals(a) || "--size".equals(a))
            {
                size = Integer.parseInt(args[++i]);
            }
            else if ("-f".equals(a) || "--fragment".equals(a))
            {
                fragment = Integer.parseInt(args[++i]);
            }
            else if ("-P".equals(a) || "--protocol".equals(a))
            {
                protocol = args[++i];
            }
            else if ("-v".equals(a) || "--verbose".equals(a))
            {
                _verbose = true;
            }
            else if ("-b".equals(a) || "--binary".equals(a))
            {
                binary = true;
            }
            else if ("-C".equals(a) || "--clients".equals(a))
            {
                clients = Integer.parseInt(args[++i]);
            }
            else if ("-d".equals(a) || "--delay".equals(a))
            {
                delay = Integer.parseInt(args[++i]);
            }
            else if (a.startsWith("-"))
            {
                usage(args);
            }
        }

        TestClient[] client = new TestClient[clients];
        WebSocketClient wsclient = new WebSocketClient(bufferPool);
        try
        {
            wsclient.start();
            __start = System.currentTimeMillis();
            protocol = protocol == null?"echo":protocol;

            for (int i = 0; i < clients; i++)
            {
                client[i] = new TestClient(wsclient,host,port,protocol,60000);
                client[i].open();
            }

            System.out.println("Jetty WebSocket PING " + host + ":" + port + " (" + new InetSocketAddress(host,port) + ") " + clients + " clients " + protocol);

            for (int p = 0; p < count; p++)
            {
                long next = System.currentTimeMillis() + delay;

                byte op = OpCode.TEXT;
                if (binary)
                {
                    op = OpCode.BINARY;
                }

                byte data[] = null;

                switch (op)
                {
                    case OpCode.TEXT:
                    {
                        StringBuilder b = new StringBuilder();
                        while (b.length() < size)
                        {
                            b.append('A' + __random.nextInt(26));
                        }
                        data = b.toString().getBytes(StandardCharsets.UTF_8);
                        break;
                    }
                    case OpCode.BINARY:
                    {
                        data = new byte[size];
                        __random.nextBytes(data);
                        break;
                    }
                }

                for (int i = 0; i < clients; i++)
                {
                    client[i].send(op,data,fragment);
                }

                while (System.currentTimeMillis() < next)
                {
                    Thread.sleep(10);
                }
            }
        }
        finally
        {
            for (int i = 0; i < clients; i++)
            {
                if (client[i] != null)
                {
                    client[i].disconnect();
                }
            }

            long duration = System.currentTimeMillis() - __start;
            System.out.println("--- " + host + " websocket ping statistics using " + clients + " connection" + (clients > 1?"s":"") + " ---");
            System.out.printf("%d/%d frames sent/recv, %d/%d mesg sent/recv, time %dms %dm/s %.2fbps%n",__framesSent,__framesReceived.get(),__messagesSent,
                    __messagesReceived.get(),duration,((1000L * __messagesReceived.get()) / duration),(1000.0D * __messagesReceived.get() * 8 * size)
                            / duration / 1024 / 1024);
            System.out.printf("rtt min/ave/max = %.3f/%.3f/%.3f ms\n",__minDuration.get() / 1000000.0,__messagesReceived.get() == 0?0.0:(__totalTime.get()
                    / __messagesReceived.get() / 1000000.0),__maxDuration.get() / 1000000.0);

            wsclient.stop();
        }
        bufferPool.assertNoLeaks();
    }

    private static void usage(String[] args)
    {
        System.err.println("ERROR: " + Arrays.asList(args));
        System.err.println("USAGE: java -cp CLASSPATH " + TestClient.class + " [ OPTIONS ]");
        System.err.println("  -h|--host HOST  (default localhost)");
        System.err.println("  -p|--port PORT  (default 8080)");
        System.err.println("  -b|--binary");
        System.err.println("  -v|--verbose");
        System.err.println("  -c|--count n    (default 10)");
        System.err.println("  -s|--size n     (default 64)");
        System.err.println("  -f|--fragment n (default 4000) ");
        System.err.println("  -P|--protocol echo|echo-assemble|echo-fragment|echo-broadcast");
        System.err.println("  -C|--clients n  (default 1) ");
        System.err.println("  -d|--delay n    (default 1000ms) ");
        System.exit(1);
    }

    private BlockingQueue<Long> _starts = new LinkedBlockingQueue<Long>();

    int _messageBytes;
    int _frames;
    byte _opcode = -1;
    private WebSocketClient client;
    private TestSocket socket;

    public TestClient(WebSocketClient client, String host, int port, String protocol, int timeoutMS) throws Exception
    {
        this.client = client;
        _host = host;
        _port = port;
        _protocol = protocol;
        _timeout = timeoutMS;
    }

    private void disconnect()
    {
        // TODO Auto-generated method stub
    }

    private void open() throws Exception
    {
        client.getPolicy().setIdleTimeout(_timeout);
        ClientUpgradeRequest request = new ClientUpgradeRequest();
        request.setSubProtocols(_protocol);
        socket = new TestSocket();
        URI wsUri = new URI("ws://" + _host + ":" + _port + "/");
        client.connect(socket,wsUri,request).get(10,TimeUnit.SECONDS);
    }

    private void send(byte op, byte[] data, int fragment)
    {
        socket.send(op,data,fragment);
    }
}
