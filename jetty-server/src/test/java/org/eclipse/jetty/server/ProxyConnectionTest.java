//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

import org.eclipse.jetty.http.HttpParser;
import org.eclipse.jetty.server.handler.ErrorHandler;
import org.eclipse.jetty.util.log.StacklessLogging;
import org.hamcrest.Matchers;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 *
 */
public class ProxyConnectionTest
{
    private Server _server;
    private LocalConnector _connector;

    @Before
    public void init() throws Exception
    {
        _server = new Server();

        
        HttpConnectionFactory http = new HttpConnectionFactory();
        http.getHttpConfiguration().setRequestHeaderSize(1024);
        http.getHttpConfiguration().setResponseHeaderSize(1024);
        
        ProxyConnectionFactory proxy = new ProxyConnectionFactory();
        
        _connector = new LocalConnector(_server,null,null,null,1,proxy,http);
        _connector.setIdleTimeout(1000);
        _server.addConnector(_connector);
        _server.setHandler(new DumpHandler());
        ErrorHandler eh=new ErrorHandler();
        eh.setServer(_server);
        _server.addBean(eh);
        _server.start();
    }

    @After
    public void destroy() throws Exception
    {
        _server.stop();
        _server.join();
    }

    @Test
    public void testSimple() throws Exception
    {
        String response=_connector.getResponses("PROXY TCP 1.2.3.4 5.6.7.8 111 222\r\n"+
                "GET /path HTTP/1.1\n"+
                "Host: server:80\n"+
                "Connection: close\n"+
                "\n");
        
        Assert.assertThat(response,Matchers.containsString("HTTP/1.1 200"));
        Assert.assertThat(response,Matchers.containsString("pathInfo=/path"));
        Assert.assertThat(response,Matchers.containsString("local=5.6.7.8:222"));
        Assert.assertThat(response,Matchers.containsString("remote=1.2.3.4:111"));
    }
    
    @Test
    public void testIPv6() throws Exception
    {
        String response=_connector.getResponses("PROXY UNKNOWN eeee:eeee:eeee:eeee:eeee:eeee:eeee:eeee ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff 65535 65535\r\n"+
                "GET /path HTTP/1.1\n"+
                "Host: server:80\n"+
                "Connection: close\n"+
                "\n");
        
        Assert.assertThat(response,Matchers.containsString("HTTP/1.1 200"));
        Assert.assertThat(response,Matchers.containsString("pathInfo=/path"));
        Assert.assertThat(response,Matchers.containsString("remote=eeee:eeee:eeee:eeee:eeee:eeee:eeee:eeee:65535"));
        Assert.assertThat(response,Matchers.containsString("local=ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff:65535"));
    }
    
    @Test
    public void testTooLong() throws Exception
    {
        String response=_connector.getResponses("PROXY TOOLONG!!! eeee:eeee:eeee:eeee:0000:0000:0000:0000 ffff:ffff:ffff:ffff:0000:0000:0000:0000 65535 65535\r\n"+
                "GET /path HTTP/1.1\n"+
                "Host: server:80\n"+
                "Connection: close\n"+
                "\n");
        
        Assert.assertThat(response,Matchers.equalTo(""));
    }
    
    @Test
    public void testNotComplete() throws Exception
    {
        _connector.setIdleTimeout(100);
        String response=_connector.getResponses("PROXY TIMEOUT");
        Assert.assertThat(response,Matchers.equalTo(""));
    }
    
    @Test
    public void testBadChar() throws Exception
    {
        String response=_connector.getResponses("PROXY\tTCP 1.2.3.4 5.6.7.8 111 222\r\n"+
                "GET /path HTTP/1.1\n"+
                "Host: server:80\n"+
                "Connection: close\n"+
                "\n");
        Assert.assertThat(response,Matchers.equalTo(""));
    }
    
    @Test
    public void testBadCRLF() throws Exception
    {
        String response=_connector.getResponses("PROXY TCP 1.2.3.4 5.6.7.8 111 222\r \n"+
                "GET /path HTTP/1.1\n"+
                "Host: server:80\n"+
                "Connection: close\n"+
                "\n");
        Assert.assertThat(response,Matchers.equalTo(""));
    }
    
    @Test
    public void testBadPort() throws Exception
    {
        try(StacklessLogging stackless = new StacklessLogging(ProxyConnectionFactory.class))
        {
            String response=_connector.getResponses("PROXY TCP 1.2.3.4 5.6.7.8 9999999999999 222\r\n"+
                    "GET /path HTTP/1.1\n"+
                    "Host: server:80\n"+
                    "Connection: close\n"+
                    "\n");
        Assert.assertThat(response,Matchers.equalTo(""));
        }
    }
    
    @Test
    public void testMissingField() throws Exception
    {
        String response=_connector.getResponses("PROXY TCP 1.2.3.4 5.6.7.8 222\r\n"+
                "GET /path HTTP/1.1\n"+
                "Host: server:80\n"+
                "Connection: close\n"+
                "\n");
        Assert.assertThat(response,Matchers.equalTo(""));
    }
    
    @Test
    public void testHTTP() throws Exception
    {
        String response=_connector.getResponses(
                "GET /path HTTP/1.1\n"+
                "Host: server:80\n"+
                "Connection: close\n"+
                "\n");
        Assert.assertThat(response,Matchers.equalTo(""));
    }
}


