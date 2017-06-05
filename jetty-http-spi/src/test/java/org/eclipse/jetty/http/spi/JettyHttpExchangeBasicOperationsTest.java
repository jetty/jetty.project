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

package org.eclipse.jetty.http.spi;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;
import static org.powermock.api.mockito.PowerMockito.doNothing;
import static org.powermock.api.mockito.PowerMockito.mock;
import static org.powermock.api.mockito.PowerMockito.when;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.eclipse.jetty.http.spi.util.SpiConstants;
import org.junit.Test;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpPrincipal;

public class JettyHttpExchangeBasicOperationsTest extends JettyHttpExchangeBase
{

    private InetSocketAddress address;

    private URI uri;

    private String reqMethod;

    private String protocol;

    private HttpPrincipal principal;

    @Test
    public void testBasicOperations()
    {
        assertNotNull("Hashcode shouldn't be null",jettyHttpExchange.hashCode());
        assertNotNull("String representation shouldn't be null",jettyHttpExchange.toString());
        assertEquals("Context should be equal",context,jettyHttpExchange.getHttpContext());
        assertEquals("Default size must be equal to zero",SpiConstants.ZERO,jettyHttpExchange.getResponseHeaders().size());
    }

    @Test
    public void testUri() throws Exception
    {
        // given
        when(request.getRequestURI()).thenReturn(SpiConstants.REQUEST_URI);
        when(request.getQueryString()).thenReturn(SpiConstants.QUERY_STRING);

        // when
        uri = jettyHttpExchange.getRequestURI();

        // then
        assertEquals("Query strings must be equal",SpiConstants.QUERY_STRING,uri.getQuery());
        assertEquals("Query strings must be equal",SpiConstants.REQUEST_URI,uri.getPath());
    }

    @Test
    public void testRemoteAddress() throws Exception
    {
        // given
        when(request.getRemoteAddr()).thenReturn(SpiConstants.LOCAL_HOST);
        when(request.getRemotePort()).thenReturn(SpiConstants.DEFAULT_PORT);

        // when
        address = jettyHttpExchange.getRemoteAddress();

        // then
        assertEquals("Host name must be equal with local host",SpiConstants.LOCAL_HOST,address.getHostName());
        assertEquals("Port value must be equal to default port",SpiConstants.DEFAULT_PORT,address.getPort());
    }

    @Test
    public void testLocalAddress() throws Exception
    {
        // given
        when(request.getLocalAddr()).thenReturn(SpiConstants.LOCAL_HOST);
        when(request.getLocalPort()).thenReturn(SpiConstants.DEFAULT_PORT);

        // when
        address = jettyHttpExchange.getLocalAddress();

        // then
        assertEquals("Host name must be equal with local host",SpiConstants.LOCAL_HOST,address.getHostName());
        assertEquals("Port value must be equal to default port",SpiConstants.DEFAULT_PORT,address.getPort());
    }

    @Test
    public void testGetMethod() throws Exception
    {
        // given
        when(request.getMethod()).thenReturn(SpiConstants.REQUEST_METHOD);

        // when
        reqMethod = jettyHttpExchange.getRequestMethod();

        // then
        assertEquals("Request method must be POST",SpiConstants.REQUEST_METHOD,reqMethod);
    }

    @Test
    public void testProtocol() throws Exception
    {
        // given
        when(request.getProtocol()).thenReturn(SpiConstants.PROTOCOL);

        // when
        protocol = jettyHttpExchange.getProtocol();

        // then
        assertEquals("Protocol must be equal to HTTP",SpiConstants.PROTOCOL,protocol);
    }

    @Test
    public void testPrincipal() throws Exception
    {
        // given
        principal = mock(HttpPrincipal.class);

        // when
        jettyHttpExchange.setPrincipal(principal);

        // then
        assertEquals("Principal instances must be equal",principal,jettyHttpExchange.getPrincipal());
    }

    @Test(expected = RuntimeException.class)
    public void testClose() throws Exception
    {
        // given
        doOutputStreamSetup();

        // when
        jettyHttpExchange.close();
        jettyHttpExchange.close();

        // then
        fail("A RuntimeException must have occured by now as we are closing a stream which has been already closed");
    }

    private void doOutputStreamSetup() throws Exception
    {
        request = mock(HttpServletRequest.class);
        response = mock(HttpServletResponse.class);
        context = mock(HttpContext.class);
        ServletOutputStream os = mock(ServletOutputStream.class);
        doNothing().doThrow(new IOException("Test")).when(os).close();
        when(response.getOutputStream()).thenReturn(os);
        jettyHttpExchange = new JettyHttpExchange(context,request,response);
    }
}
