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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.servlets.DoSFilter.RateTracker;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

public class DoSFilterTest extends AbstractDoSFilterTest
{
    private static final Logger LOG = Log.getLogger(DoSFilterTest.class);

    @BeforeClass
    public static void setUp() throws Exception
    {
        startServer(DoSFilter2.class);
    }

    public static class DoSFilter2 extends DoSFilter
    {
        @Override
        public void closeConnection(HttpServletRequest request, HttpServletResponse response, Thread thread)
        {
            try
            {
                response.getWriter().append("DoSFilter: timeout");
                super.closeConnection(request,response,thread);
            }
            catch (Exception e)
            {
                LOG.warn(e);
            }
        }
    }

    @Test
    public void isRateExceededTest() throws InterruptedException
    {
        DoSFilter doSFilter = new DoSFilter();

        boolean exceeded = hitRateTracker(doSFilter,0);
        assertTrue("Last hit should have exceeded",exceeded);

        int sleep = 250;
        exceeded = hitRateTracker(doSFilter,sleep);
        assertFalse("Should not exceed as we sleep 300s for each hit and thus do less than 4 hits/s",exceeded);
    }

    @Test
    public void testWhitelist() throws Exception
    {
        DoSFilter filter = new DoSFilter();
        List<String> whitelist = new ArrayList<String>();
        whitelist.add("192.168.0.1");
        whitelist.add("10.0.0.0/8");
        whitelist.add("4d8:0:a:1234:ABc:1F:b18:17");
        whitelist.add("4d8:0:a:1234:ABc:1F:0:0/96");
        Assert.assertTrue(filter.checkWhitelist(whitelist, "192.168.0.1"));
        Assert.assertFalse(filter.checkWhitelist(whitelist, "192.168.0.2"));
        Assert.assertFalse(filter.checkWhitelist(whitelist, "11.12.13.14"));
        Assert.assertTrue(filter.checkWhitelist(whitelist, "10.11.12.13"));
        Assert.assertTrue(filter.checkWhitelist(whitelist, "10.0.0.0"));
        Assert.assertFalse(filter.checkWhitelist(whitelist, "0.0.0.0"));
        Assert.assertTrue(filter.checkWhitelist(whitelist, "4d8:0:a:1234:ABc:1F:b18:17"));
        Assert.assertTrue(filter.checkWhitelist(whitelist, "4d8:0:a:1234:ABc:1F:b18:0"));
        Assert.assertFalse(filter.checkWhitelist(whitelist, "4d8:0:a:1234:ABc:1D:0:0"));
    }

    private boolean hitRateTracker(DoSFilter doSFilter, int sleep) throws InterruptedException
    {
        boolean exceeded = false;
        RateTracker rateTracker = doSFilter.new RateTracker("test2",0,4);

        for (int i = 0; i < 5; i++)
        {
            Thread.sleep(sleep);
            if (rateTracker.isRateExceeded(System.currentTimeMillis()))
                exceeded = true;
        }
        return exceeded;
    }
}
