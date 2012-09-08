//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
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
import java.util.concurrent.TimeUnit;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class RedirectionTest extends AbstractHttpClientServerTest
{
    @Before
    public void init() throws Exception
    {
        start(new RedirectHandler());
    }

    @Test
    public void test_303() throws Exception
    {
        Response response = client.newRequest("localhost", connector.getLocalPort())
                .path("/303/localhost/done")
                .send().get(5, TimeUnit.SECONDS);
        Assert.assertNotNull(response);
        Assert.assertEquals(200, response.status());
        Assert.assertFalse(response.headers().containsKey(HttpHeader.LOCATION.asString()));
    }

    @Test
    public void test_303_302() throws Exception
    {
        Response response = client.newRequest("localhost", connector.getLocalPort())
                .path("/303/localhost/302/localhost/done")
                .send().get(5, TimeUnit.SECONDS);
        Assert.assertNotNull(response);
        Assert.assertEquals(200, response.status());
        Assert.assertFalse(response.headers().containsKey(HttpHeader.LOCATION.asString()));
    }

    @Test
    public void test_303_302_OnDifferentDestinations() throws Exception
    {
        Response response = client.newRequest("localhost", connector.getLocalPort())
                .path("/303/127.0.0.1/302/localhost/done")
                .send().get(5, TimeUnit.SECONDS);
        Assert.assertNotNull(response);
        Assert.assertEquals(200, response.status());
        Assert.assertFalse(response.headers().containsKey(HttpHeader.LOCATION.asString()));
    }

    private class RedirectHandler extends AbstractHandler
    {
        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            try
            {
                String[] paths = target.split("/", 4);
                int status = Integer.parseInt(paths[1]);
                response.setStatus(status);
                String host = paths[2];
                response.setHeader("Location", request.getScheme() + "://" + host + ":" + request.getServerPort() + "/" + paths[3]);
            }
            catch (NumberFormatException x)
            {
                response.setStatus(200);
            }
            finally
            {
                baseRequest.setHandled(true);
            }
        }
    }
}
