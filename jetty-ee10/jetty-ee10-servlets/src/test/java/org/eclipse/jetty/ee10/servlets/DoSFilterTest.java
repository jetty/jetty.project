//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee10.servlets;

import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(WorkDirExtension.class)
@Disabled // TODO
public class DoSFilterTest extends AbstractDoSFilterTest
{
    /* TODO
    public WorkDir workDir;

    private static class RemoteAddressRequest extends Request
    {
        public RemoteAddressRequest(String remoteHost, int remotePort)
        {
            super(null, null);
            setRemoteAddr(new InetSocketAddress(remoteHost, remotePort));
        }
    }

    private static class NoOpFilterConfig implements FilterConfig
    {
        @Override
        public String getFilterName()
        {
            return "noop";
        }

        @Override
        public ServletContext getServletContext()
        {
            return null;
        }

        @Override
        public String getInitParameter(String name)
        {
            return null;
        }

        @Override
        public Enumeration<String> getInitParameterNames()
        {
            return Collections.emptyEnumeration();
        }
    }

    @BeforeEach
    public void setUp() throws Exception
    {
        startServer(workDir, DoSFilter.class);
    }

    @Test
    public void testRemotePortLoadIdCreationIpv6() throws ServletException
    {
        final ServletRequest request = new RemoteAddressRequest("::192.9.5.5", 12345);
        DoSFilter doSFilter = new DoSFilter();
        doSFilter.init(new NoOpFilterConfig());
        doSFilter.setRemotePort(true);

        try
        {
            RateTracker tracker = doSFilter.getRateTracker(request);
            assertThat("tracker.id", tracker.getId(),
                anyOf(
                    is("[::192.9.5.5]:12345"), // short form
                    is("[0:0:0:0:0:0:c009:505]:12345") // long form
                ));
        }
        finally
        {
            doSFilter.stopScheduler();
        }
    }

    @Test
    public void testRemotePortLoadIdCreationIpv4() throws ServletException
    {
        final ServletRequest request = new RemoteAddressRequest("127.0.0.1", 12345);
        DoSFilter doSFilter = new DoSFilter();
        doSFilter.init(new NoOpFilterConfig());
        doSFilter.setRemotePort(true);

        try
        {
            RateTracker tracker = doSFilter.getRateTracker(request);
            assertThat("tracker.id", tracker.getId(), is("127.0.0.1:12345"));
        }
        finally
        {
            doSFilter.stopScheduler();
        }
    }

    @Test
    public void testRateIsRateExceeded() throws InterruptedException
    {
        DoSFilter doSFilter = new DoSFilter();
        doSFilter.setName("foo");
        boolean exceeded = hitRateTracker(doSFilter, 0);
        assertTrue(exceeded, "Last hit should have exceeded");

        int sleep = 250;
        exceeded = hitRateTracker(doSFilter, sleep);
        assertFalse(exceeded, "Should not exceed as we sleep 300s for each hit and thus do less than 4 hits/s");
    }

    @Test
    public void testWhitelist() throws Exception
    {
        DoSFilter filter = new DoSFilter();
        filter.setName("foo");
        filter.setWhitelist("192.168.0.1/32,10.0.0.0/8,4d8:0:a:1234:ABc:1F:b18:17,4d8:0:a:1234:ABc:1F:0:0/96");
        assertTrue(filter.checkWhitelist("192.168.0.1"));
        assertFalse(filter.checkWhitelist("192.168.0.2"));
        assertFalse(filter.checkWhitelist("11.12.13.14"));
        assertTrue(filter.checkWhitelist("10.11.12.13"));
        assertTrue(filter.checkWhitelist("10.0.0.0"));
        assertFalse(filter.checkWhitelist("0.0.0.0"));
        assertTrue(filter.checkWhitelist("4d8:0:a:1234:ABc:1F:b18:17"));
        assertTrue(filter.checkWhitelist("4d8:0:a:1234:ABc:1F:b18:0"));
        assertFalse(filter.checkWhitelist("4d8:0:a:1234:ABc:1D:0:0"));
    }

    @Test
    public void testUnresponsiveServer() throws Exception
    {
        String last = "GET /ctx/timeout/?sleep=" + 2 * _requestMaxTime + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n";
        String responses = doRequests("", 0, 0, 0, last);
        assertThat(responses, Matchers.containsString(" 503 "));
    }

    private boolean hitRateTracker(DoSFilter doSFilter, int sleep) throws InterruptedException
    {
        boolean exceeded = false;
        ServletContext context = new ContextHandler.StaticContext();
        RateTracker rateTracker = new RateTracker(context, doSFilter.getName(), "test2", DoSFilter.RateType.UNKNOWN, 4);

        for (int i = 0; i < 5; i++)
        {
            Thread.sleep(sleep);
            if (rateTracker.isRateExceeded(NanoTime.now()) != null)
                exceeded = true;
        }
        return exceeded;
    }

     */
}
