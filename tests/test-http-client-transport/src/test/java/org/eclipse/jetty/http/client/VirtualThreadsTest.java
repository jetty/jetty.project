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

package org.eclipse.jetty.http.client;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.util.VirtualThreads;
import org.eclipse.jetty.util.thread.ThreadPool;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.condition.DisabledForJreRange;
import org.junit.jupiter.api.condition.JRE;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisabledForJreRange(max = JRE.JAVA_18)
public class VirtualThreadsTest extends AbstractTest<TransportScenario>
{
    @Override
    public void init(Transport transport) throws IOException
    {
        setScenario(new TransportScenario(transport));
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testServletInvokedOnVirtualThread(Transport transport) throws Exception
    {
        // No virtual thread support in FCGI server-side.
        Assumptions.assumeTrue(transport != Transport.FCGI);

        init(transport);
        scenario.prepareServer(new EmptyServerHandler()
        {
            @Override
            protected void service(String target, Request jettyRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                if (!VirtualThreads.isVirtualThread())
                    response.setStatus(HttpStatus.NOT_IMPLEMENTED_501);
            }
        });
        ThreadPool threadPool = scenario.server.getThreadPool();
        if (threadPool instanceof VirtualThreads.Configurable)
            ((VirtualThreads.Configurable)threadPool).setUseVirtualThreads(true);
        scenario.server.start();
        scenario.startClient();

        ContentResponse response = scenario.client.newRequest(scenario.newURI())
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertEquals(HttpStatus.OK_200, response.getStatus(), " for transport " + transport);
    }
}
