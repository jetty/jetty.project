//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

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
    public void testLiveTimeout() throws Exception
    {
        List<Integer> remotePorts = new ArrayList<>();
        start(new EmptyServerHandler()
        {
            @Override
            protected void service(String target, Request jettyRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                remotePorts.add(request.getRemotePort());
                if (target.equals("/wait"))
                {
                    long time = Long.parseLong(request.getParameter("time"));
                    try
                    {
                        Thread.sleep(time);
                    }
                    catch (InterruptedException x)
                    {
                        throw new InterruptedIOException();
                    }
                }
            }
        });

        long liveTimeout = 1000;
        client.getTransport().setConnectionPoolFactory(destination ->
        {
            RoundRobinConnectionPool pool = new RoundRobinConnectionPool(destination, 1, destination);
            pool.setLiveTimeout(liveTimeout);
            return pool;
        });

        // Make a request to create the initial connection.
        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .timeout(5, TimeUnit.SECONDS)
                .send();
        Assert.assertEquals(HttpStatus.OK_200, response.getStatus());

        // Wait a bit.
        Thread.sleep(liveTimeout / 2);

        // Live timeout will expire during this request.
        response = client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .path("/wait")
                .param("time", String.valueOf(liveTimeout))
                .timeout(5, TimeUnit.SECONDS)
                .send();
        Assert.assertEquals(HttpStatus.OK_200, response.getStatus());

        // Make another request to be sure another connection will be used.
        response = client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .timeout(5, TimeUnit.SECONDS)
                .send();
        Assert.assertEquals(HttpStatus.OK_200, response.getStatus());

        Assert.assertThat(remotePorts.size(), Matchers.equalTo(3));
        Assert.assertThat(remotePorts.get(0), Matchers.equalTo(remotePorts.get(1)));
        // Third request must be on a different connection.
        Assert.assertThat(remotePorts.get(1), Matchers.not(Matchers.equalTo(remotePorts.get(2))));
    }
}
