//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.client;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.junit.Assert;
import org.junit.Test;
import org.junit.Ignore;

public class ExpirationWithLimitedConnectionsTest
{
    @Ignore
    public void testExpirationWithMaxConnectionPerAddressReached() throws Exception
    {
        final Logger logger = Log.getLogger("org.eclipse.jetty.client");
        logger.setDebugEnabled(true);

        HttpClient client = new HttpClient();
        int maxConnectionsPerAddress = 10;
        client.setMaxConnectionsPerAddress(maxConnectionsPerAddress);
        long timeout = 1000;
        client.setTimeout(timeout);
        client.start();

        final List<Socket> sockets = new CopyOnWriteArrayList<Socket>();
        final List<Exception> failures = new CopyOnWriteArrayList<Exception>();
        final AtomicLong processingDelay = new AtomicLong(200);

        final ExecutorService threadPool = Executors.newCachedThreadPool();
        final ServerSocket server = new ServerSocket(0);
        threadPool.submit(new Runnable()
        {
            public void run()
            {
                while (true)
                {
                    try
                    {
                        final Socket socket = server.accept();
                        sockets.add(socket);
                        logger.debug("CONNECTION {}", socket.getRemoteSocketAddress());
                        threadPool.submit(new Runnable()
                        {
                            public void run()
                            {
                                while (true)
                                {
                                    try
                                    {
                                        BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
                                        String firstLine = reader.readLine();
                                        String line = firstLine;
                                        while (line != null)
                                        {
                                            if (line.length() == 0)
                                                break;
                                            line = reader.readLine();
                                        }

                                        if (line == null)
                                            break;

                                        long sleep = processingDelay.get();
                                        logger.debug("{} {} {} ms", firstLine, socket.getRemoteSocketAddress(), sleep);
                                        TimeUnit.MILLISECONDS.sleep(sleep);

                                        String response = "" +
                                                "HTTP/1.1 200 OK\r\n" +
                                                "Content-Length: 0\r\n" +
                                                "\r\n";
                                        OutputStream output = socket.getOutputStream();
                                        output.write(response.getBytes("UTF-8"));
                                        output.flush();
                                    }
                                    catch (Exception x)
                                    {
                                        failures.add(x);
                                        break;
                                    }
                                }
                            }
                        });
                    }
                    catch (Exception x)
                    {
                        failures.add(x);
                        break;
                    }
                }
            }
        });

        List<ContentExchange> exchanges = new ArrayList<ContentExchange>();

        final AtomicBoolean firstExpired = new AtomicBoolean();
        int count = 0;
        int maxAdditionalRequest = 100;
        int additionalRequests = 0;
        while (true)
        {
            TimeUnit.MILLISECONDS.sleep(1); // Just avoid being too fast
            ContentExchange exchange = new ContentExchange(true)
            {
                @Override
                protected void onResponseComplete() throws IOException
                {
                    logger.debug("{} {} OK", getMethod(), getRequestURI());
                }

                @Override
                protected void onExpire()
                {
                    logger.debug("{} {} EXPIRED {}", getMethod(), getRequestURI(), this);
                    firstExpired.compareAndSet(false, true);
                }
            };
            exchanges.add(exchange);
            Address address = new Address("localhost", server.getLocalPort());
            exchange.setAddress(address);
            exchange.setMethod("GET");
            exchange.setRequestURI("/" + count);
            exchange.setVersion("HTTP/1.1");
            exchange.setRequestHeader("Host", address.toString());
            logger.debug("{} {} SENT", exchange.getMethod(), exchange.getRequestURI());
            client.send(exchange);
            ++count;

            if (processingDelay.get() > 0)
            {
                if (client.getDestination(address, false).getConnections() == maxConnectionsPerAddress)
                {
                    if (firstExpired.get())
                    {
                        ++additionalRequests;
                        if (additionalRequests == maxAdditionalRequest)
                            processingDelay.set(0);
                    }
                }
            }
            else
            {
                ++additionalRequests;
                if (additionalRequests == 2 * maxAdditionalRequest)
                    break;
            }
        }

        for (ContentExchange exchange : exchanges)
        {
            int status = exchange.waitForDone();
            Assert.assertTrue(status == HttpExchange.STATUS_COMPLETED || status == HttpExchange.STATUS_EXPIRED);
        }

        client.stop();

        Assert.assertTrue(failures.isEmpty());

        for (Socket socket : sockets)
            socket.close();
        server.close();

        threadPool.shutdown();
        threadPool.awaitTermination(5, TimeUnit.SECONDS);
    }
}
