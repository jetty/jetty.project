// ========================================================================
// Copyright (c) 2004-2009 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses.
// ========================================================================

package org.eclipse.jetty.server;

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

import junit.framework.TestCase;

import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.server.handler.HandlerWrapper;
import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.eclipse.jetty.util.log.Log;

public class AbstractConnectorTest extends TestCase
{
    private Server _server;
    private Handler _handler;
    private AbstractConnector _connector;
    
    private String _request = "GET / HTTP/1.1\r\n" +
                              "Host: localhost\r\n" +
                              "\r\n";
    private Socket[] _socket;
    private PrintWriter[] _out;
    private BufferedReader[] _in;
    
    private CyclicBarrier _connect;
    private CountDownLatch _closed;

    @Override
    protected void setUp() throws Exception
    {
        _connect = new CyclicBarrier(2);

        _server = new Server();

        _connector = 
            new SelectChannelConnector() {
                public void connectionClosed(Connection connection)
                {
                    super.connectionClosed(connection);
                    _closed.countDown();
                }
            
            };
        _server.addConnector(_connector);
        _connector.setStatsOn(true);

        HandlerWrapper wrapper = 
            new HandlerWrapper() {
                public void handle(String path, Request request, HttpServletRequest httpRequest, HttpServletResponse httpResponse) throws IOException, ServletException
                {
                    try
                    {
                        _connect.await();
                     }
                    catch (Exception ex)
                    {
                        Log.debug(ex);
                    }
                    finally
                    {             
                        super.handle(path, request, httpRequest, httpResponse);
                    }
                }
            };
        _server.setHandler(wrapper);   

        _handler = 
            new AbstractHandler() {
                public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response)
                    throws IOException, ServletException
                {
                    baseRequest.setHandled(true);
                    
                    PrintWriter out = response.getWriter();
                    out.write("Server response\n");
                    out.close();
                    
                    response.setStatus(HttpServletResponse.SC_OK);
                }
            };
        wrapper.setHandler(_handler);
        
        _server.start();
    }

    @Override
    protected void tearDown() throws Exception
    {
        _server.stop();
        _server.join();
    }

    public void testSingleRequest()
        throws Exception
    {
        doInit(1);

        sendRequest(1, 1);
       
        doClose(1);
        
        assertEquals(1, _connector.getConnections());
        assertEquals(0, _connector.getConnectionsOpen());
        assertEquals(1, _connector.getConnectionsOpenMax());
        assertTrue(_connector.getConnectionsOpen() <= _connector.getConnectionsOpenMax());
        
        assertTrue(_connector.getConnectionsDurationMean() > 0);
        assertTrue(_connector.getConnectionsDurationMax() > 0);
        assertTrue(_connector.getConnectionsDurationMean() <= _connector.getConnectionsDurationMax());
        
        assertEquals(1, _connector.getRequests());
        assertEquals(1.0, _connector.getConnectionsRequestsMean());
        assertEquals(1, _connector.getConnectionsRequestsMax());
        assertTrue(_connector.getConnectionsRequestsMean() <= _connector.getConnectionsRequestsMax());
    }
    
    public void testMultipleRequests()
        throws Exception
    {
        doInit(1);
               
        sendRequest(1, 1);

        sendRequest(1, 1);

        doClose(1);
        
        assertEquals(1, _connector.getConnections());
        assertEquals(0, _connector.getConnectionsOpen());
        assertEquals(1, _connector.getConnectionsOpenMax());
        assertTrue(_connector.getConnectionsOpen() <= _connector.getConnectionsOpenMax());
        
        assertTrue(_connector.getConnectionsDurationMean() > 0);
        assertTrue(_connector.getConnectionsDurationMax() > 0);
        assertTrue(_connector.getConnectionsDurationMean() <= _connector.getConnectionsDurationMax());
        
        assertEquals(2, _connector.getRequests());
        assertEquals(2.0, _connector.getConnectionsRequestsMean());
        assertEquals(2, _connector.getConnectionsRequestsMax());
        assertTrue(_connector.getConnectionsRequestsMean() <= _connector.getConnectionsRequestsMax());
    }
    
    public void testMultipleConnections()
        throws Exception
    {
        doInit(3);
               
        sendRequest(1, 1); // request 1 connection 1
        
        sendRequest(2, 2); // request 1 connection 2
        
        sendRequest(3, 3); // request 1 connection 3
        
        sendRequest(2, 3); // request 2 connection 2
        
        sendRequest(3, 3); // request 2 connection 3
        
        sendRequest(3, 3); // request 3 connection 3
        
        doClose(3);

        assertEquals(3, _connector.getConnections());
        assertEquals(0, _connector.getConnectionsOpen());
        assertEquals(3, _connector.getConnectionsOpenMax());
        assertTrue(_connector.getConnectionsOpen() <= _connector.getConnectionsOpenMax());
        
        assertTrue(_connector.getConnectionsDurationMean() > 0);
        assertTrue(_connector.getConnectionsDurationMax() > 0);
        assertTrue(_connector.getConnectionsDurationMean() <= _connector.getConnectionsDurationMax());
        
        assertEquals(6, _connector.getRequests());
        assertEquals(2.0, _connector.getConnectionsRequestsMean());
        assertEquals(3, _connector.getConnectionsRequestsMax());
        assertTrue(_connector.getConnectionsRequestsMean() <= _connector.getConnectionsRequestsMax());
    }
 
    protected void doInit(int count)
    {
        _socket = new Socket[count];
        _out = new PrintWriter[count];
        _in = new BufferedReader[count];

        _closed = new CountDownLatch(count);
    }
    
    protected void doClose(int count)
        throws Exception
    {
        for (int idx=0; idx < count; idx++)
        {
            if (_out[idx] != null)
                _out[idx].close();

            if (_in[idx] != null)
                _in[idx].close();
            
            if (_socket[idx] != null)
                _socket[idx].close();
        }
        
        _closed.await();
    }
      
    protected void sendRequest(int id, int count)
        throws Exception
    {
        int idx = id - 1;
        
        if (idx < 0)
            throw new IllegalArgumentException("Connection ID <= 0");
        
        _socket[idx]  = _socket[idx] == null ? new Socket("localhost", _connector.getLocalPort()) : _socket[idx];
        _out[idx] = _out[idx] == null ? new PrintWriter(_socket[idx].getOutputStream(), true) : _out[idx];
        _in[idx] = _in[idx] == null ? new BufferedReader(new InputStreamReader(_socket[idx].getInputStream())) : _in[idx];
   
        _connect.reset();
        
        _out[idx].write(_request);
        _out[idx].flush();
        
        _connect.await();
        
        assertEquals(count, _connector.getConnectionsOpen());
        
        while(_in[idx].ready())
        {
            _in[idx].readLine();
        }
    }
}
