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

import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.when;
import static org.powermock.api.mockito.PowerMockito.mock;
import java.io.IOException;
import java.net.URI;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.eclipse.jetty.http.spi.util.SpiConstants;
import org.junit.Before;
import org.junit.Test;
import com.sun.net.httpserver.HttpContext;

public class JettyHttpExchangeDelegateTest
{

    private HttpContext jaxWsContext;

    private HttpServletRequest req;

    private HttpServletResponse resp;

    private JettyHttpExchangeDelegate httpDelegate;

    @Before
    public void setUp() throws Exception
    {
        jaxWsContext = mock(HttpContext.class);
        req = mock(HttpServletRequest.class);
        resp = mock(HttpServletResponse.class);
    }

    @Test(expected = RuntimeException.class)
    public void testInputStreamRuntimeException() throws Exception
    {
        // given
        when(req.getInputStream()).thenThrow(new IOException());

        // when
        new JettyHttpExchangeDelegate(jaxWsContext,req,resp);

        // then
        fail("A RuntimeException must have been occured by now as getInputStream() throwing exception");
    }

    @Test
    public void testGetRequestUri()
    {
        // given
        httpDelegate = new JettyHttpExchangeDelegate(jaxWsContext,req,resp);
        when(req.getQueryString()).thenReturn(null);
        when(req.getRequestURI()).thenReturn(SpiConstants.REQUEST_URI);

        // when
        URI uri = httpDelegate.getRequestURI();

        // then
        assertNull("QueryString must be null as we set it up in request scope",uri.getQuery());
    }
}
