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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.server.handler.HandlerWrapper;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;

@Ignore("Ignored while refactoring the connection events and statistics")
public class SelectChannelStatisticsTest
{
    private static final Logger LOG = Log.getLogger(SelectChannelStatisticsTest.class);

    private static Server _server;
    private static ConnectorStatistics _statistics;
    private static AbstractNetworkConnector _connector;
    private static CyclicBarrier _connect;
    private static CountDownLatch _closed;

    private Socket[] _socket;
    private PrintWriter[] _out;
    private BufferedReader[] _in;

    @BeforeClass
    public static void initClass() throws Exception
    {
        _connect = new CyclicBarrier(2);

        _server = new Server();
        _connector = new ServerConnector(_server);
        _statistics = new ConnectorStatistics();
        _connector.addBean(_statistics);
        _server.addConnector(_connector);

        HandlerWrapper wrapper = new HandlerWrapper()
        {
            @Override
            public void handle(String path, Request request, HttpServletRequest httpRequest, HttpServletResponse httpResponse) throws IOException, ServletException
            {
                try
                {
                    _connect.await();
                }
                catch (Exception ex)
                {
                    LOG.debug(ex);
                }
                finally
                {
                    super.handle(path, request, httpRequest, httpResponse);
                }
            }
        };
        _server.setHandler(wrapper);

        Handler handler = new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response)
                throws IOException, ServletException
            {
                try{Thread.sleep(1);} catch(Exception e){}
                baseRequest.setHandled(true);
                PrintWriter out = response.getWriter();
                out.write("Server response\n");
                out.close();

                response.setStatus(HttpServletResponse.SC_OK);
            }
        };
        wrapper.setHandler(handler);

        _server.start();
    }

    @AfterClass
    public static void destroy() throws Exception
    {
        _server.stop();
        _server.join();
    }

    @Before
    public void init() throws Exception
    {
        _statistics.reset();
    }

    @After
    public void tini() throws Exception
    {
    }

    @Test
    public void testSingleRequest() throws Exception
    {
        doInit(1);

        sendRequest(1, 1);

        doClose(1);

        assertEquals(1, _statistics.getConnections());
        assertEquals(0, _statistics.getConnectionsOpen());
        assertEquals(1, _statistics.getConnectionsOpenMax());
        assertTrue(_statistics.getConnectionsOpen() <= _statistics.getConnectionsOpenMax());

        assertTrue(_statistics.getConnectionDurationMean() > 0);
        assertTrue(_statistics.getConnectionDurationMax() > 0);
        assertTrue(_statistics.getConnectionDurationMean() <= _statistics.getConnectionDurationMax());

        assertEquals(1, _statistics.getMessagesIn());
        assertEquals(1.0, _statistics.getMessagesInPerConnectionMean(), 0.01);
        assertEquals(1, _statistics.getMessagesInPerConnectionMax());
        assertTrue(_statistics.getMessagesInPerConnectionMean() <= _statistics.getMessagesInPerConnectionMax());
    }

    @Test
    public void testMultipleRequests() throws Exception
    {
        doInit(1);

        sendRequest(1, 1);

        sendRequest(1, 1);

        doClose(1);

        assertEquals(1, _statistics.getConnections());
        assertEquals(0, _statistics.getConnectionsOpen());
        assertEquals(1, _statistics.getConnectionsOpenMax());
        assertTrue(_statistics.getConnectionsOpen() <= _statistics.getConnectionsOpenMax());

        assertTrue(_statistics.getConnectionDurationMean() > 0);
        assertTrue(_statistics.getConnectionDurationMax() > 0);
        assertTrue(_statistics.getConnectionDurationMean() <= _statistics.getConnectionDurationMax());

        assertEquals(2, _statistics.getMessagesIn());
        assertEquals(2.0, _statistics.getMessagesInPerConnectionMean(), 0.01);
        assertEquals(2, _statistics.getMessagesInPerConnectionMax());
        assertTrue(_statistics.getMessagesInPerConnectionMean() <= _statistics.getMessagesInPerConnectionMax());
    }

    @Test
    public void testMultipleConnections() throws Exception
    {
        doInit(3);

        sendRequest(1, 1); // request 1 connection 1

        sendRequest(2, 2); // request 1 connection 2

        sendRequest(3, 3); // request 1 connection 3

        sendRequest(2, 3); // request 2 connection 2

        sendRequest(3, 3); // request 2 connection 3

        sendRequest(3, 3); // request 3 connection 3

        doClose(3);

        assertEquals(3, _statistics.getConnections());
        assertEquals(0, _statistics.getConnectionsOpen());
        assertEquals(3, _statistics.getConnectionsOpenMax());
        assertTrue(_statistics.getConnectionsOpen() <= _statistics.getConnectionsOpenMax());

        assertTrue(_statistics.getConnectionDurationMean() > 0);
        assertTrue(_statistics.getConnectionDurationMax() > 0);
        assertTrue(_statistics.getConnectionDurationMean() <= _statistics.getConnectionDurationMax());

        assertEquals(6, _statistics.getMessagesIn());
        assertEquals(2.0, _statistics.getMessagesInPerConnectionMean(), 0.01);
        assertEquals(3, _statistics.getMessagesInPerConnectionMax());
        assertTrue(_statistics.getMessagesInPerConnectionMean() <= _statistics.getMessagesInPerConnectionMax());
    }

    protected void doInit(int count)
    {
        _socket = new Socket[count];
        _out = new PrintWriter[count];
        _in = new BufferedReader[count];

        _closed = new CountDownLatch(count);
    }

    private void doClose(int count) throws Exception
    {
        for (int idx=0; idx < count; idx++)
        {
            if (_socket[idx] != null)
            {
                _socket[idx].close();
            }
        }

        _closed.await();
    }

    private void sendRequest(int id, int count) throws Exception
    {
        int idx = id - 1;

        if (idx < 0)
            throw new IllegalArgumentException("Connection ID <= 0");

        _socket[idx]  = _socket[idx] == null ? new Socket("localhost", _connector.getLocalPort()) : _socket[idx];
        _out[idx] = _out[idx] == null ? new PrintWriter(_socket[idx].getOutputStream(), true) : _out[idx];
        _in[idx] = _in[idx] == null ? new BufferedReader(new InputStreamReader(_socket[idx].getInputStream())) : _in[idx];

        _connect.reset();

        _out[idx].write("GET / HTTP/1.1\r\nHost: localhost\r\n\r\n");
        _out[idx].flush();

        _connect.await();

        assertEquals(count, _statistics.getConnectionsOpen());

        String line=_in[idx].readLine();
        while(line!=null)
        {
            if ("Server response".equals(line))
                break;
            line=_in[idx].readLine();
        }
    }
}
