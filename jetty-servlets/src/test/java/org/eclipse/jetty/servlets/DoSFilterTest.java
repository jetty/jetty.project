// ========================================================================
// Copyright 2009 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
// http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
//========================================================================

package org.eclipse.jetty.servlets;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.servlets.DoSFilter.RateTracker;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
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
            try {
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
