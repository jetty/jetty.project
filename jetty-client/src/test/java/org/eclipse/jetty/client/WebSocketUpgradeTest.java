// ========================================================================
// Copyright (c) 2006-2009 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.client;

import java.io.IOException;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

import javax.servlet.http.HttpServletRequest;

import junit.framework.TestCase;

import org.eclipse.jetty.http.HttpMethods;
import org.eclipse.jetty.io.Buffer;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.eclipse.jetty.websocket.WebSocket;
import org.eclipse.jetty.websocket.WebSocketConnection;
import org.eclipse.jetty.websocket.WebSocketHandler;

/**
 * Functional testing for HttpExchange.
 */
public class WebSocketUpgradeTest extends TestCase
{
    protected Server _server;
    protected int _port;
    protected HttpClient _httpClient;
    protected Connector _connector;
    protected ConcurrentLinkedQueue<TestWebSocket> _webSockets= new ConcurrentLinkedQueue<TestWebSocket>();
    protected WebSocketHandler _handler;
    protected TestWebSocket _websocket;
    final BlockingQueue<Object> _results = new ArrayBlockingQueue<Object>(100);
    
    @Override
    protected void setUp() throws Exception
    {
        startServer();
        _httpClient=new HttpClient();
        _httpClient.setIdleTimeout(2000);
        _httpClient.setTimeout(2500);
        _httpClient.setConnectTimeout(1000);
        _httpClient.setConnectorType(HttpClient.CONNECTOR_SELECT_CHANNEL);
        _httpClient.setMaxConnectionsPerAddress(10);
        _httpClient.start();
    }

    @Override
    protected void tearDown() throws Exception
    {
        _httpClient.stop();
        Thread.sleep(500);
        stopServer();
    }


    public void testGetWithContentExchange() throws Exception
    {
        final WebSocket clientWS = new WebSocket()
        {
            Outbound _outbound;
            
            public void onConnect(Outbound outbound)
            {
                _outbound=outbound;
                _results.add("clientWS.onConnect");
                _results.add(_outbound);
            }

            public void onDisconnect()
            {
            }

            public void onMessage(byte frame, String data)
            {
                _results.add("clientWS.onMessage");
                _results.add(data);
            }

            public void onMessage(byte frame, byte[] data, int offset, int length)
            {
            }
        };
        
        
        HttpExchange httpExchange=new HttpExchange()
        {
            /* ------------------------------------------------------------ */
            /**
             * @see org.eclipse.jetty.client.HttpExchange#onResponseStatus(org.eclipse.jetty.io.Buffer, int, org.eclipse.jetty.io.Buffer)
             */
            @Override
            protected void onResponseStatus(Buffer version, int status, Buffer reason) throws IOException
            {
                waitFor(2);
                _results.add(new Integer(status));
                super.onResponseStatus(version,status,reason);
            }

            /* ------------------------------------------------------------ */
            /**
             * @see org.eclipse.jetty.client.HttpExchange#onSwitchProtocol(org.eclipse.jetty.io.EndPoint)
             */
            @Override
            protected Connection onSwitchProtocol(EndPoint endp) throws IOException
            {
                waitFor(3);
                WebSocketConnection connection = new WebSocketConnection(clientWS,endp);
                
                _results.add("onSwitchProtocol");
                _results.add(connection);
                clientWS.onConnect(connection);
                return connection;
            }    
            
            private void waitFor(int results)
            {
                try
                {
                    int c=10;
                    while(_results.size()<results && c-->0)
                        Thread.sleep(10);
                }
                catch(InterruptedException e)
                {
                    e.printStackTrace();
                }
            }
        };
        
        httpExchange.setURL("http://localhost:"+_port+"/");
        httpExchange.setMethod(HttpMethods.GET);
        
        httpExchange.addRequestHeader("Upgrade","WebSocket");
        httpExchange.addRequestHeader("Connection","Upgrade");
        
        _httpClient.send(httpExchange);
        int status = httpExchange.waitForDone();
        assertEquals(HttpExchange.STATUS_COMPLETED, status);

        System.err.println("results="+_results);
        
        assertEquals("serverWS.onConnect", _results.poll(1,TimeUnit.SECONDS));
        TestWebSocket serverWS = (TestWebSocket)_results.poll(1,TimeUnit.SECONDS);
        
        assertEquals(new Integer(101), _results.poll(1,TimeUnit.SECONDS));
        
        assertEquals("onSwitchProtocol", _results.poll(1,TimeUnit.SECONDS));
        WebSocketConnection client_conn=(WebSocketConnection)_results.poll(1,TimeUnit.SECONDS);

        assertEquals("clientWS.onConnect", _results.poll(1,TimeUnit.SECONDS));
        assertEquals(client_conn, _results.poll(1,TimeUnit.SECONDS));
        
        client_conn.sendMessage("hello world");
        
        assertEquals("serverWS.onMessage", _results.poll(1,TimeUnit.SECONDS));
        assertEquals("hello world", _results.poll(1,TimeUnit.SECONDS));
        
        serverWS.sendMessage("buongiorno");
        
        assertEquals("clientWS.onMessage", _results.poll(1,TimeUnit.SECONDS));
        assertEquals("buongiorno", _results.poll(1,TimeUnit.SECONDS));
        
    }

    protected void newServer() throws Exception
    {
        _server=new Server();
        _server.setGracefulShutdown(500);
        _connector=new SelectChannelConnector();

        _connector.setPort(0);
        _server.setConnectors(new Connector[] { _connector });
    }

    protected void startServer() throws Exception
    {
        newServer();
        _handler= new WebSocketHandler()
        {
            @Override
            protected WebSocket doWebSocketConnect(HttpServletRequest request, String protocol)
            {
                _websocket = new TestWebSocket();
                return _websocket;
            }
        };
        
        _server.setHandler(_handler);
        _server.start();
        _port=_connector.getLocalPort();
    }

    private void stopServer() throws Exception
    {
        _server.stop();
    }

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    class TestWebSocket implements WebSocket
    {
        Outbound _outbound;

        public void onConnect(Outbound outbound)
        {
            _outbound=outbound;
            _webSockets.add(this);
            _results.add("serverWS.onConnect");
            _results.add(this);
        }
        
        public void onMessage(byte frame, byte[] data,int offset, int length)
        {
        }

        public void onMessage(final byte frame, final String data)
        {
            _results.add("serverWS.onMessage");
            _results.add(data);
        }

        public void onDisconnect()
        {
            _results.add("onDisconnect");
            _webSockets.remove(this);
        }
        
        public void sendMessage(String msg) throws IOException
        {
            _outbound.sendMessage(msg);
        }
    }
}
