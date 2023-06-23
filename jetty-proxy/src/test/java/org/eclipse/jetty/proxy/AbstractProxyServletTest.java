//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.proxy;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.api.Response.CompleteListener;
import org.junit.jupiter.api.Test;

public class AbstractProxyServletTest
{

    @Test
    public void testNewDestroy() throws Exception
    {
        new AbstractProxyServlet()
        {
            private static final long serialVersionUID = 1L;

            @Override
            protected CompleteListener newProxyResponseListener(HttpServletRequest clientRequest, HttpServletResponse proxyResponse) 
            {
                return null;
            }
        }.destroy();
    }
}
