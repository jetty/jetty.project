//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package examples;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
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
import org.eclipse.jetty.websocket.core.OpCode;

/**
 * This is not a general purpose websocket client.
 * <p>
 * It's only for testing the websocket server and is hardwired to a specific draft version of the protocol.
 * </p>
 */
public class ClientDemo
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
            super.onWebSocketClose(statusCode, reason);
        }

        @Override
        public void onWebSocketConnect(Session session)
        {
            if (verbose)
            {
                System.err.printf("%s#onWebSocketConnect %s %s\n", this.getClass().getSimpleName(), session, session.getClass().getSimpleName());
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
            messagesSent++;
            while (off < data.length)
            {
                framesSent++;

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

    private static boolean verbose = false;

    private static final Random RANDOM = new Random();

    private final String host;
    private final int port;
    private final String protocol;
    private final int timeout;

    private static int framesSent;
    private static int messagesSent;
    private static AtomicInteger framesReceived = new AtomicInteger();
    private static AtomicInteger messagesReceived = new AtomicInteger();

    private static AtomicLong totalTime = new AtomicLong();
    private static AtomicLong minDuration = new AtomicLong(Long.MAX_VALUE);
    private static AtomicLong maxDuration = new AtomicLong(Long.MIN_VALUE);
    private static long start;

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
                verbose = true;
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

        ClientDemo[] client = new ClientDemo[clients];
        WebSocketClient wsclient = new WebSocketClient();
        try
        {
            wsclient.start();
            start = System.currentTimeMillis();
            protocol = protocol == null ? "echo" : protocol;

            for (int i = 0; i < clients; i++)
            {
                client[i] = new ClientDemo(wsclient, host, port, protocol, 60000);
                client[i].open();
            }

            System.out
                .println("Jetty WebSocket PING " + host + ":" + port + " (" + new InetSocketAddress(host, port) + ") " + clients + " clients " + protocol);

            for (int p = 0; p < count; p++)
            {
                long next = System.currentTimeMillis() + delay;

                byte op = OpCode.TEXT;
                if (binary)
                {
                    op = OpCode.BINARY;
                }

                byte[] data = null;

                switch (op)
                {
                    case OpCode.TEXT:
                    {
                        StringBuilder b = new StringBuilder();
                        while (b.length() < size)
                        {
                            b.append('A' + RANDOM.nextInt(26));
                        }
                        data = b.toString().getBytes(StandardCharsets.UTF_8);
                        break;
                    }
                    case OpCode.BINARY:
                    {
                        data = new byte[size];
                        RANDOM.nextBytes(data);
                        break;
                    }
                }

                for (int i = 0; i < clients; i++)
                {
                    client[i].send(op, data, fragment);
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

            long duration = System.currentTimeMillis() - start;
            System.out.println("--- " + host + " websocket ping statistics using " + clients + " connection" + (clients > 1 ? "s" : "") + " ---");
            System.out.printf("%d/%d frames sent/recv, %d/%d mesg sent/recv, time %dms %dm/s %.2fbps%n", framesSent, framesReceived.get(), messagesSent,
                messagesReceived.get(), duration, ((1000L * messagesReceived.get()) / duration),
                (1000.0D * messagesReceived.get() * 8 * size) / duration / 1024 / 1024);
            System.out.printf("rtt min/ave/max = %.3f/%.3f/%.3f ms\n", minDuration.get() / 1000000.0,
                messagesReceived.get() == 0 ? 0.0 : (totalTime.get() / messagesReceived.get() / 1000000.0), maxDuration.get() / 1000000.0);

            wsclient.stop();
        }
    }

    private static void usage(String[] args)
    {
        System.err.println("ERROR: " + Arrays.asList(args));
        System.err.println("USAGE: java -cp CLASSPATH " + ClientDemo.class + " [ OPTIONS ]");
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

    private BlockingQueue<Long> _starts = new LinkedBlockingQueue<>();

    private WebSocketClient client;
    private TestSocket socket;

    public ClientDemo(WebSocketClient client, String host, int port, String protocol, int timeoutMS) throws Exception
    {
        this.client = client;
        this.host = host;
        this.port = port;
        this.protocol = protocol;
        timeout = timeoutMS;
    }

    private void disconnect()
    {
        // TODO Auto-generated method stub
    }

    private void open() throws Exception
    {
        client.setIdleTimeout(Duration.ofMillis(timeout));
        ClientUpgradeRequest request = new ClientUpgradeRequest();
        request.setSubProtocols(protocol);
        socket = new TestSocket();
        URI wsUri = new URI("ws://" + host + ":" + port + "/");
        client.connect(socket, wsUri, request).get(10, TimeUnit.SECONDS);
    }

    private void send(byte op, byte[] data, int fragment)
    {
        socket.send(op, data, fragment);
    }
}
