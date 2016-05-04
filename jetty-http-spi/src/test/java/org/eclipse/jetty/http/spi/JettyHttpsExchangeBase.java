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

import static org.powermock.api.mockito.PowerMockito.mock;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.junit.Before;
import com.sun.net.httpserver.HttpContext;

public class JettyHttpsExchangeBase
{

    protected HttpServletRequest request;

    protected HttpServletResponse response;

    protected HttpContext context;

    protected JettyHttpsExchange jettyHttpsExchange;

    @Before
    public void setUp() throws Exception
    {
        request = mock(HttpServletRequest.class);
        response = mock(HttpServletResponse.class);
        context = mock(HttpContext.class);
        jettyHttpsExchange = new JettyHttpsExchange(context,request,response);
    }
}
