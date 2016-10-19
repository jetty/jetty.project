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

package org.eclipse.jetty.http.spi;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.powermock.api.mockito.PowerMockito.mock;
import static org.powermock.api.mockito.PowerMockito.when;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collections;
import org.eclipse.jetty.http.spi.util.SpiConstants;
import org.eclipse.jetty.http.spi.util.SpiUtility;
import org.junit.Test;
import com.sun.net.httpserver.Headers;

public class JettyHttpsExchangeAdvancedOperationsTest extends JettyHttpsExchangeBase
{

    private JettyHttpsExchange mockJettyHttpsExchange;

    private Boolean match;

    private Headers headers;

    private InputStream is;

    private OutputStream os;

    @Test
    public void testAdvancedOperations() throws Exception
    {
        // given
        mockJettyHttpsExchange = mock(JettyHttpsExchange.class);

        // when
        match = jettyHttpsExchange.equals(mockJettyHttpsExchange);

        // then
        assertFalse("This should return false as both instances shouldn't equal",match);
    }

    @Test
    public void testRequestHeaders() throws Exception
    {
        // given
        when(request.getHeaderNames()).thenReturn(Collections.enumeration(SpiUtility.getAcceptCharsetHeader().keySet()));
        when(request.getHeaders(SpiConstants.ACCEPT_CHARSET))
                .thenReturn(Collections.enumeration(SpiUtility.getAcceptCharsetHeader().get(SpiConstants.ACCEPT_CHARSET)));

        // when
        headers = jettyHttpsExchange.getRequestHeaders();

        // then
        assertTrue("CharSetKey must be registered in headers list",headers.containsKey(SpiConstants.ACCEPT_CHARSET));
        assertEquals("Charset value must be UTF8",SpiConstants.UTF_8,headers.get(SpiConstants.ACCEPT_CHARSET).get(SpiConstants.ZERO));
    }

    @Test
    public void testResponseHeaders() throws Exception
    {
        // when
        jettyHttpsExchange.sendResponseHeaders(SpiConstants.TWO_HUNDRED,SpiConstants.THOUSAND);

        // then
        assertEquals("Response must be equal to 200",SpiConstants.TWO_HUNDRED,(Integer)jettyHttpsExchange.getResponseCode());
    }

    @Test
    public void testInputStream() throws Exception
    {
        // given
        is = mock(InputStream.class);
        os = mock(OutputStream.class);

        // when
        jettyHttpsExchange.setStreams(is,os);

        // then
        assertEquals("Input stream must be equal",is,jettyHttpsExchange.getRequestBody());
        assertEquals("Output stream must be equal",os,jettyHttpsExchange.getResponseBody());
    }
}
