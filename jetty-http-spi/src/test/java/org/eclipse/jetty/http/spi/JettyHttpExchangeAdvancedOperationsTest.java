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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.powermock.api.mockito.PowerMockito.mock;
import static org.powermock.api.mockito.PowerMockito.when;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import org.eclipse.jetty.http.spi.util.SpiConstants;
import org.junit.Test;
import com.sun.net.httpserver.Headers;

public class JettyHttpExchangeAdvancedOperationsTest extends JettyHttpExchangeBase
{

    private JettyHttpExchange mockjettyHttpExchange;

    private Boolean match;

    private Headers headers;

    @Test
    public void testAdvancedOperations() throws Exception
    {
        // given
        mockjettyHttpExchange = mock(JettyHttpExchange.class);

        // when
        match = jettyHttpExchange.equals(mockjettyHttpExchange);

        // then
        assertFalse("This should return false as both instances shouldn't equal",match);
    }

    @Test
    public void testRequestHeaders() throws Exception
    {
        // given
        when(request.getHeaderNames()).thenReturn(Collections.enumeration(getAcceptCharsetHeader().keySet()));
        when(request.getHeaders(SpiConstants.ACCEPT_CHARSET)).thenReturn(Collections.enumeration(getAcceptCharsetHeader().get(SpiConstants.ACCEPT_CHARSET)));

        // when
        headers = jettyHttpExchange.getRequestHeaders();

        // then
        assertTrue("CharSetKey must be registered in headers list",headers.containsKey(SpiConstants.ACCEPT_CHARSET));
        assertEquals("Charset value must be UTF8",SpiConstants.UTF_8,headers.get(SpiConstants.ACCEPT_CHARSET).get(SpiConstants.ZERO));
    }

    private Map<String, List<String>> getAcceptCharsetHeader()
    {
        Map<String, List<String>> headers = new Hashtable<>();
        ArrayList<String> valueSet = new ArrayList<String>();
        valueSet.add(SpiConstants.UTF_8);
        headers.put(SpiConstants.ACCEPT_CHARSET,valueSet);
        return headers;
    }

    @Test
    public void testResponseHeaders() throws Exception
    {
        // when
        jettyHttpExchange.sendResponseHeaders(200,1000);

        // then
        assertEquals("Response must be equal to 200",200,jettyHttpExchange.getResponseCode());
    }

    @Test
    public void testInputStream() throws Exception
    {
        // given
        InputStream is = mock(InputStream.class);
        OutputStream os = mock(OutputStream.class);

        // when
        jettyHttpExchange.setStreams(is,os);

        // then
        assertEquals("Input stream must be equal",is,jettyHttpExchange.getRequestBody());
        assertEquals("Output stream must be equal",os,jettyHttpExchange.getResponseBody());
    }

    @Test
    public void testAttributes() throws Exception
    {
        // given
        when(request.getAttribute("tone")).thenReturn("vone");

        // when
        jettyHttpExchange.setAttribute("tone","vone");

        // then
        assertEquals("Attribute value must be equal to vone","vone",(String)jettyHttpExchange.getAttribute("tone"));
    }
}
