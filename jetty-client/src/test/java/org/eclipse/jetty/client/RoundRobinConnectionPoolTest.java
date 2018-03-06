//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.hamcrest.Matchers;
import org.junit.Assert;
import org.junit.Test;

public class RoundRobinConnectionPoolTest extends AbstractHttpClientServerTest
{
    public RoundRobinConnectionPoolTest(SslContextFactory sslContextFactory)
    {
        super(sslContextFactory);
    }

    @Test
    public void testRoundRobin() throws Exception
    {
        AtomicBoolean record = new AtomicBoolean();
        List<Integer> remotePorts = new ArrayList<>();
        start(new EmptyServerHandler()
        {
            @Override
            protected void service(String target, Request jettyRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                if (record.get())
                    remotePorts.add(request.getRemotePort());
            }
        });

        int maxConnections = 3;
        client.getTransport().setConnectionPoolFactory(destination -> new RoundRobinConnectionPool(destination, maxConnections, destination));

        // Prime the connections, so that they are all opened
        // before we actually test the round robin behavior.
        String host = "localhost";
        int port = connector.getLocalPort();
        for (int i = 0; i < maxConnections; ++i)
        {
            ContentResponse response = client.newRequest(host, port)
                    .scheme(scheme)
                    .timeout(5, TimeUnit.SECONDS)
                    .send();
            Assert.assertEquals(HttpStatus.OK_200, response.getStatus());
        }

        record.set(true);
        int requests = 2 * maxConnections - 1;
        for (int i = 0; i < requests; ++i)
        {
            ContentResponse response = client.newRequest(host, port)
                    .scheme(scheme)
                    .timeout(5, TimeUnit.SECONDS)
                    .send();
            Assert.assertEquals(HttpStatus.OK_200, response.getStatus());
        }

        Assert.assertThat(remotePorts.size(), Matchers.equalTo(requests));
        for (int i = 0; i < requests; ++i)
        {
            int base = i % maxConnections;
            int expected = remotePorts.get(base);
            int candidate = remotePorts.get(i);
            Assert.assertThat(client.dump() + System.lineSeparator() + remotePorts.toString(), expected, Matchers.equalTo(candidate));
            if (i > 0)
                Assert.assertThat(remotePorts.get(i - 1), Matchers.not(Matchers.equalTo(candidate)));
        }
    }
}
