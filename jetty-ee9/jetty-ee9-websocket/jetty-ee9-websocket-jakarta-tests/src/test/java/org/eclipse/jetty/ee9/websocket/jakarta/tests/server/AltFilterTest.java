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

package org.eclipse.jetty.ee9.websocket.jakarta.tests.server;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.ee9.servlet.FilterHolder;
import org.eclipse.jetty.ee9.websocket.jakarta.tests.Fuzzer;
import org.eclipse.jetty.ee9.websocket.jakarta.tests.WSServer;
import org.eclipse.jetty.ee9.websocket.jakarta.tests.server.sockets.echo.BasicEchoSocket;
import org.eclipse.jetty.ee9.websocket.servlet.WebSocketUpgradeFilter;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.eclipse.jetty.websocket.core.CloseStatus;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.OpCode;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * Testing the use of an alternate {@link WebSocketUpgradeFilter}
 * defined in the WEB-INF/web.xml
 */
@ExtendWith(WorkDirExtension.class)
public class AltFilterTest
{
    public WorkDir testdir;

    @Disabled
    @Test
    public void testEcho() throws Exception
    {
        WSServer wsb = new WSServer(testdir.getPath());
        WSServer.WebApp app = wsb.createWebApp("app");
        app.copyWebInf("alt-filter-web.xml");
        // the endpoint (extends jakarta.websocket.Endpoint)
        app.copyClass(BasicEchoSocket.class);
        app.deploy();

        try
        {
            wsb.start();

            FilterHolder filterWebXml = app.getWebAppContext().getServletHandler().getFilter(WebSocketUpgradeFilter.class.getName());
            assertThat("Filter[wsuf-test]", filterWebXml, notNullValue());

            FilterHolder filterSCI = app.getWebAppContext().getServletHandler().getFilter("Jetty_WebSocketUpgradeFilter");
            assertThat("Filter[Jetty_WebSocketUpgradeFilter]", filterSCI, nullValue());

            List<Frame> send = new ArrayList<>();
            send.add(new Frame(OpCode.TEXT).setPayload("Hello Echo"));
            send.add(CloseStatus.toFrame(CloseStatus.NORMAL));

            List<Frame> expect = new ArrayList<>();
            expect.add(new Frame(OpCode.TEXT).setPayload("Hello Echo"));
            expect.add(CloseStatus.toFrame(CloseStatus.NORMAL));

            try (Fuzzer session = wsb.newNetworkFuzzer("/app/echo;jsession=xyz"))
            {
                session.sendFrames(send);
                session.expect(expect);
            }
        }
        finally
        {
            wsb.stop();
        }
    }
}
