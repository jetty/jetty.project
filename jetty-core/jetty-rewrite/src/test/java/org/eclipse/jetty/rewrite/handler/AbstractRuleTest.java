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

package org.eclipse.jetty.rewrite.handler;

import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.AfterEach;

public abstract class AbstractRuleTest
{
    protected Server _server = new Server();
    protected HttpConfiguration httpConfig = new HttpConfiguration();
    protected LocalConnector _connector = new LocalConnector(_server, new HttpConnectionFactory(httpConfig));
    protected RewriteHandler _rewriteHandler = new RewriteHandler();

    @AfterEach
    public void stop() throws Exception
    {
        LifeCycle.stop(_server);
    }

    protected void start(Handler handler) throws Exception
    {
        _server.addConnector(_connector);
        _server.setHandler(_rewriteHandler);
        _rewriteHandler.setHandler(handler);
        _server.start();
    }
}
