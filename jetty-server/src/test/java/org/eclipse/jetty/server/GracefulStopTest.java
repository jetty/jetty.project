//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.server;

import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.server.handler.StatisticsHandler;
import org.eclipse.jetty.util.IO;
import org.hamcrest.Matchers;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class GracefulStopTest
{
    private Server server;

    @Before
    public void setup() throws Exception
    {
        server = new Server(0);
        StatisticsHandler stats = new StatisticsHandler();
        TestHandler test=new TestHandler();
        server.setHandler(stats);
        stats.setHandler(test);
        server.setStopTimeout(10 * 1000);
        
        server.start();
    }

    @Test
    public void testGraceful() throws Exception
    {
        new Thread()
        {
            @Override
            public void run()
            {
                try
                {
                    TimeUnit.SECONDS.sleep(1);
                    server.stop();
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                }
            }
        }.start();

        try(Socket socket = new Socket("localhost",server.getBean(NetworkConnector.class).getLocalPort());)
        {
            socket.getOutputStream().write("GET / HTTP/1.0\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
            String out = IO.toString(socket.getInputStream());
            Assert.assertThat(out,Matchers.containsString("200 OK"));
        }
    }

    private static class TestHandler extends AbstractHandler
    {
        @Override
        public void handle(final String s, final Request request, final HttpServletRequest httpServletRequest, final HttpServletResponse httpServletResponse)
            throws IOException, ServletException
        {
            try
            {
                TimeUnit.SECONDS.sleep(2);
            }
            catch (InterruptedException e)
            {
            }

            httpServletResponse.getWriter().write("OK");
            httpServletResponse.setStatus(200);
            request.setHandled(true);
        }
    }

}
