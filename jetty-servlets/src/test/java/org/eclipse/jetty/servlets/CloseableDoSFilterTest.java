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

package org.eclipse.jetty.servlets;


import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.junit.BeforeClass;

public class CloseableDoSFilterTest extends AbstractDoSFilterTest
{
    private static final Logger LOG = Log.getLogger(CloseableDoSFilterTest.class);

    @BeforeClass
    public static void setUp() throws Exception
    {
        startServer(CloseableDoSFilter2.class);
    }

    public static class CloseableDoSFilter2 extends CloseableDoSFilter
    {
        @Override
        public void closeConnection(HttpServletRequest request, HttpServletResponse response, Thread thread)
        {
            try
            {
                response.getWriter().append("DoSFilter: timeout");
                response.flushBuffer();
                super.closeConnection(request, response, thread);
            }
            catch (Exception e)
            {
                LOG.warn(e);
            }
        }
    }

    public void testUnresponsiveClient() throws Exception
    {
        // TODO work out why this intermittently fails
    	LOG.warn("Ignored Closeable testUnresponsiveClient");
    }
}
