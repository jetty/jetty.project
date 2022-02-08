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

package org.eclipse.jetty.server.handler;

import java.io.IOException;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class HandlerTest
{
    @Test
    public void testWrapperSetServer()
    {
        Server s = new Server();
        HandlerWrapper a = new HandlerWrapper();
        HandlerWrapper b = new HandlerWrapper();
        HandlerWrapper c = new HandlerWrapper();
        a.setHandler(b);
        b.setHandler(c);

        a.setServer(s);
        assertThat(b.getServer(), equalTo(s));
        assertThat(c.getServer(), equalTo(s));
    }

    @Test
    public void testWrapperServerSet()
    {
        Server s = new Server();
        HandlerWrapper a = new HandlerWrapper();
        HandlerWrapper b = new HandlerWrapper();
        HandlerWrapper c = new HandlerWrapper();
        a.setServer(s);
        b.setHandler(c);
        a.setHandler(b);

        assertThat(b.getServer(), equalTo(s));
        assertThat(c.getServer(), equalTo(s));
    }

    @Test
    public void testWrapperThisLoop()
    {
        HandlerWrapper a = new HandlerWrapper();

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> a.setHandler(a));
        assertThat(e.getMessage(), containsString("loop"));
    }

    @Test
    public void testWrapperSimpleLoop()
    {
        HandlerWrapper a = new HandlerWrapper();
        HandlerWrapper b = new HandlerWrapper();

        a.setHandler(b);

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> b.setHandler(a));
        assertThat(e.getMessage(), containsString("loop"));
    }

    @Test
    public void testWrapperDeepLoop()
    {
        HandlerWrapper a = new HandlerWrapper();
        HandlerWrapper b = new HandlerWrapper();
        HandlerWrapper c = new HandlerWrapper();

        a.setHandler(b);
        b.setHandler(c);

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> c.setHandler(a));
        assertThat(e.getMessage(), containsString("loop"));
    }

    @Test
    public void testWrapperChainLoop()
    {
        HandlerWrapper a = new HandlerWrapper();
        HandlerWrapper b = new HandlerWrapper();
        HandlerWrapper c = new HandlerWrapper();

        a.setHandler(b);
        c.setHandler(a);

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> b.setHandler(c));
        assertThat(e.getMessage(), containsString("loop"));
    }

    @Test
    public void testHandlerListSetServer()
    {
        Server s = new Server();
        HandlerList a = new HandlerList();
        HandlerList b = new HandlerList();
        HandlerList b1 = new HandlerList();
        HandlerList b2 = new HandlerList();
        HandlerList c = new HandlerList();
        HandlerList c1 = new HandlerList();
        HandlerList c2 = new HandlerList();

        a.addHandler(b);
        a.addHandler(c);
        b.setHandlers(new Handler[]{b1, b2});
        c.setHandlers(new Handler[]{c1, c2});
        a.setServer(s);

        assertThat(b.getServer(), equalTo(s));
        assertThat(c.getServer(), equalTo(s));
        assertThat(b1.getServer(), equalTo(s));
        assertThat(b2.getServer(), equalTo(s));
        assertThat(c1.getServer(), equalTo(s));
        assertThat(c2.getServer(), equalTo(s));
    }

    @Test
    public void testHandlerListServerSet()
    {
        Server s = new Server();
        HandlerList a = new HandlerList();
        HandlerList b = new HandlerList();
        HandlerList b1 = new HandlerList();
        HandlerList b2 = new HandlerList();
        HandlerList c = new HandlerList();
        HandlerList c1 = new HandlerList();
        HandlerList c2 = new HandlerList();

        a.setServer(s);
        a.addHandler(b);
        a.addHandler(c);
        b.setHandlers(new Handler[]{b1, b2});
        c.setHandlers(new Handler[]{c1, c2});

        assertThat(b.getServer(), equalTo(s));
        assertThat(c.getServer(), equalTo(s));
        assertThat(b1.getServer(), equalTo(s));
        assertThat(b2.getServer(), equalTo(s));
        assertThat(c1.getServer(), equalTo(s));
        assertThat(c2.getServer(), equalTo(s));
    }

    @Test
    public void testHandlerListThisLoop()
    {
        HandlerList a = new HandlerList();

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> a.addHandler(a));
        assertThat(e.getMessage(), containsString("loop"));
    }

    @Test
    public void testHandlerListDeepLoop()
    {
        HandlerList a = new HandlerList();
        HandlerList b = new HandlerList();
        HandlerList b1 = new HandlerList();
        HandlerList b2 = new HandlerList();
        HandlerList c = new HandlerList();
        HandlerList c1 = new HandlerList();
        HandlerList c2 = new HandlerList();

        a.addHandler(b);
        a.addHandler(c);
        b.setHandlers(new Handler[]{b1, b2});
        c.setHandlers(new Handler[]{c1, c2});

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> b2.addHandler(a));
        assertThat(e.getMessage(), containsString("loop"));
    }

    @Test
    public void testHandlerListChainLoop()
    {
        HandlerList a = new HandlerList();
        HandlerList b = new HandlerList();
        HandlerList b1 = new HandlerList();
        HandlerList b2 = new HandlerList();
        HandlerList c = new HandlerList();
        HandlerList c1 = new HandlerList();
        HandlerList c2 = new HandlerList();

        a.addHandler(c);
        b.setHandlers(new Handler[]{b1, b2});
        c.setHandlers(new Handler[]{c1, c2});
        b2.addHandler(a);

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> a.addHandler(b));
        assertThat(e.getMessage(), containsString("loop"));
    }

    @Test
    public void testInsertWrapperTail()
    {
        HandlerWrapper a = new HandlerWrapper();
        HandlerWrapper b = new HandlerWrapper();

        a.insertHandler(b);
        assertThat(a.getHandler(), equalTo(b));
        assertThat(b.getHandler(), nullValue());
    }

    @Test
    public void testInsertWrapper()
    {
        HandlerWrapper a = new HandlerWrapper();
        HandlerWrapper b = new HandlerWrapper();
        HandlerWrapper c = new HandlerWrapper();

        a.insertHandler(c);
        a.insertHandler(b);
        assertThat(a.getHandler(), equalTo(b));
        assertThat(b.getHandler(), equalTo(c));
        assertThat(c.getHandler(), nullValue());
    }

    @Test
    public void testInsertWrapperChain()
    {
        HandlerWrapper a = new HandlerWrapper();
        HandlerWrapper b = new HandlerWrapper();
        HandlerWrapper c = new HandlerWrapper();
        HandlerWrapper d = new HandlerWrapper();

        a.insertHandler(d);
        b.insertHandler(c);
        a.insertHandler(b);
        assertThat(a.getHandler(), equalTo(b));
        assertThat(b.getHandler(), equalTo(c));
        assertThat(c.getHandler(), equalTo(d));
        assertThat(d.getHandler(), nullValue());
    }

    @Test
    public void testInsertWrapperBadChain()
    {
        HandlerWrapper a = new HandlerWrapper();
        HandlerWrapper b = new HandlerWrapper();
        HandlerWrapper c = new HandlerWrapper();
        HandlerWrapper d = new HandlerWrapper();

        a.insertHandler(d);
        b.insertHandler(c);
        c.setHandler(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
            }
        });

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> a.insertHandler(b));
        assertThat(e.getMessage(), containsString("bad tail"));
    }
}
