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

package org.eclipse.jetty.http.client;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.util.BytesContentProvider;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.ConnectionStatistics;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.util.IO;
import org.hamcrest.Matchers;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;

public class ConnectionStatisticsTest extends AbstractTest
{
    public ConnectionStatisticsTest(Transport transport)
    {
        super(transport);
    }

    @Test
    public void testConnectionStatistics() throws Exception
    {
        Assume.assumeThat(transport, Matchers.isOneOf( Transport.HTTP, Transport.H2C, Transport.H2));

        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                IO.copy(request.getInputStream(), response.getOutputStream());
            }
        });

        ConnectionStatistics serverStats = new ConnectionStatistics();
        connector.addBean(serverStats);
        serverStats.start();

        ConnectionStatistics clientStats = new ConnectionStatistics();
        client.addBean(clientStats);
        clientStats.start();

        byte[] content = new byte[3072];
        long contentLength = content.length;
        ContentResponse response = client.newRequest(newURI())
                .content(new BytesContentProvider(content))
                .timeout(5, TimeUnit.SECONDS)
                .send();

        Assert.assertThat(response.getStatus(), Matchers.equalTo(HttpStatus.OK_200));

        // The bytes have already arrived, but give time to
        // the server to finish to run the response logic.
        Thread.sleep(1000);

        // Close all connections.
        stop();

        // Give some time to process the stop event.
        Thread.sleep(1000);

        Assert.assertThat(serverStats.getConnectionsMax(), Matchers.greaterThan(0L));
        Assert.assertThat(serverStats.getReceivedBytes(), Matchers.greaterThan(contentLength));
        Assert.assertThat(serverStats.getSentBytes(), Matchers.greaterThan(contentLength));
        Assert.assertThat(serverStats.getReceivedMessages(), Matchers.greaterThan(0L));
        Assert.assertThat(serverStats.getSentMessages(), Matchers.greaterThan(0L));

        Assert.assertThat(clientStats.getConnectionsMax(), Matchers.greaterThan(0L));
        Assert.assertThat(clientStats.getReceivedBytes(), Matchers.greaterThan(contentLength));
        Assert.assertThat(clientStats.getSentBytes(), Matchers.greaterThan(contentLength));
        Assert.assertThat(clientStats.getReceivedMessages(), Matchers.greaterThan(0L));
        Assert.assertThat(clientStats.getSentMessages(), Matchers.greaterThan(0L));
    }
}
