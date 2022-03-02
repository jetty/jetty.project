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

package org.eclipse.jetty.ee9.websocket.tests.examples;

import java.time.Duration;

import jakarta.servlet.annotation.WebServlet;
import org.eclipse.jetty.ee9.websocket.server.JettyWebSocketServlet;
import org.eclipse.jetty.ee9.websocket.server.JettyWebSocketServletFactory;

@SuppressWarnings("serial")
@WebServlet(name = "MyAdvanced Echo WebSocket Servlet", urlPatterns = {"/advecho"})
public class MyAdvancedEchoServlet extends JettyWebSocketServlet
{
    @Override
    public void configure(JettyWebSocketServletFactory factory)
    {
        // set a 10 second timeout
        factory.setIdleTimeout(Duration.ofSeconds(10));

        // set a custom WebSocket creator
        factory.setCreator(new MyAdvancedEchoCreator());
    }
}
