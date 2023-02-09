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

import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpChannelTest;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.Callback;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * This test checks the mechanism of combining Handlers into a tree, but doesn't check their operation.
 * @see HttpChannelTest for testing of calling Handlers
 */
public class HandlerTest
{
    @Test
    public void testWrapperSetServer()
    {
        Server s = new Server();
        Handler.Singleton a = new Handler.Wrapper();
        Handler.Singleton b = new Handler.Wrapper();
        Handler.Singleton c = new Handler.Wrapper();
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
        Handler.Singleton a = new Handler.Wrapper();
        Handler.Singleton b = new Handler.Wrapper();
        Handler.Singleton c = new Handler.Wrapper();
        a.setServer(s);
        b.setHandler(c);
        a.setHandler(b);

        assertThat(b.getServer(), equalTo(s));
        assertThat(c.getServer(), equalTo(s));
    }

    @Test
    public void testWrapperThisLoop()
    {
        Handler.Singleton a = new Handler.Wrapper();

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> a.setHandler(a));
        assertThat(e.getMessage(), containsString("loop"));
    }

    @Test
    public void testWrapperSimpleLoop()
    {
        Handler.Singleton a = new Handler.Wrapper();
        Handler.Singleton b = new Handler.Wrapper();

        a.setHandler(b);

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> b.setHandler(a));
        assertThat(e.getMessage(), containsString("loop"));
    }

    @Test
    public void testWrapperDeepLoop()
    {
        Handler.Singleton a = new Handler.Wrapper();
        Handler.Singleton b = new Handler.Wrapper();
        Handler.Singleton c = new Handler.Wrapper();

        a.setHandler(b);
        b.setHandler(c);

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> c.setHandler(a));
        assertThat(e.getMessage(), containsString("loop"));
    }

    @Test
    public void testWrapperChainLoop()
    {
        Handler.Singleton a = new Handler.Wrapper();
        Handler.Singleton b = new Handler.Wrapper();
        Handler.Singleton c = new Handler.Wrapper();

        a.setHandler(b);
        c.setHandler(a);

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> b.setHandler(c));
        assertThat(e.getMessage(), containsString("loop"));
    }

    @Test
    public void testHandlerCollectionSetServer()
    {
        Server s = new Server();
        Handler.Sequence a = new Handler.Sequence();
        Handler.Sequence b = new Handler.Sequence();
        Handler.Sequence b1 = new Handler.Sequence();
        Handler.Sequence b2 = new Handler.Sequence();
        Handler.Sequence c = new Handler.Sequence();
        Handler.Sequence c1 = new Handler.Sequence();
        Handler.Sequence c2 = new Handler.Sequence();

        a.addHandler(b);
        a.addHandler(c);
        b.setHandlers(b1, b2);
        c.setHandlers(c1, c2);
        a.setServer(s);

        assertThat(b.getServer(), equalTo(s));
        assertThat(c.getServer(), equalTo(s));
        assertThat(b1.getServer(), equalTo(s));
        assertThat(b2.getServer(), equalTo(s));
        assertThat(c1.getServer(), equalTo(s));
        assertThat(c2.getServer(), equalTo(s));
    }

    @Test
    public void testHandlerCollectionServerSet()
    {
        Server s = new Server();
        Handler.Sequence a = new Handler.Sequence();
        Handler.Sequence b = new Handler.Sequence();
        Handler.Sequence b1 = new Handler.Sequence();
        Handler.Sequence b2 = new Handler.Sequence();
        Handler.Sequence c = new Handler.Sequence();
        Handler.Sequence c1 = new Handler.Sequence();
        Handler.Sequence c2 = new Handler.Sequence();

        a.setServer(s);
        a.addHandler(b);
        a.addHandler(c);
        b.setHandlers(b1, b2);
        c.setHandlers(c1, c2);

        assertThat(b.getServer(), equalTo(s));
        assertThat(c.getServer(), equalTo(s));
        assertThat(b1.getServer(), equalTo(s));
        assertThat(b2.getServer(), equalTo(s));
        assertThat(c1.getServer(), equalTo(s));
        assertThat(c2.getServer(), equalTo(s));
    }

    @Test
    public void testHandlerCollectionThisLoop()
    {
        Handler.Sequence a = new Handler.Sequence();

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> a.addHandler(a));
        assertThat(e.getMessage(), containsString("loop"));
    }

    @Test
    public void testHandlerCollectionDeepLoop()
    {
        Handler.Sequence a = new Handler.Sequence();
        Handler.Sequence b = new Handler.Sequence();
        Handler.Sequence b1 = new Handler.Sequence();
        Handler.Sequence b2 = new Handler.Sequence();
        Handler.Sequence c = new Handler.Sequence();
        Handler.Sequence c1 = new Handler.Sequence();
        Handler.Sequence c2 = new Handler.Sequence();

        a.addHandler(b);
        a.addHandler(c);
        b.setHandlers(b1, b2);
        c.setHandlers(c1, c2);

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> b2.addHandler(a));
        assertThat(e.getMessage(), containsString("loop"));
    }

    @Test
    public void testHandlerCollectionChainLoop()
    {
        Handler.Sequence a = new Handler.Sequence();
        Handler.Sequence b = new Handler.Sequence();
        Handler.Sequence b1 = new Handler.Sequence();
        Handler.Sequence b2 = new Handler.Sequence();
        Handler.Sequence c = new Handler.Sequence();
        Handler.Sequence c1 = new Handler.Sequence();
        Handler.Sequence c2 = new Handler.Sequence();

        a.addHandler(c);
        b.setHandlers(b1, b2);
        c.setHandlers(c1, c2);
        b2.addHandler(a);

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> a.addHandler(b));
        assertThat(e.getMessage(), containsString("loop"));
    }

    @Test
    public void testInsertWrapperTail()
    {
        Handler.Singleton a = new Handler.Wrapper();
        Handler.Singleton b = new Handler.Wrapper();

        a.insertHandler(b);
        assertThat(a.getHandler(), equalTo(b));
        assertThat(b.getHandler(), nullValue());
    }

    @Test
    public void testInsertWrapper()
    {
        Handler.Singleton a = new Handler.Wrapper();
        Handler.Singleton b = new Handler.Wrapper();
        Handler.Singleton c = new Handler.Wrapper();

        a.insertHandler(c);
        a.insertHandler(b);
        assertThat(a.getHandler(), equalTo(b));
        assertThat(b.getHandler(), equalTo(c));
        assertThat(c.getHandler(), nullValue());
    }

    @Test
    public void testInsertWrapperChain()
    {
        Handler.Singleton a = new Handler.Wrapper();
        Handler.Singleton b = new Handler.Wrapper();
        Handler.Singleton c = new Handler.Wrapper();
        Handler.Singleton d = new Handler.Wrapper();

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
        Handler.Singleton a = new Handler.Wrapper();
        Handler.Singleton b = new Handler.Wrapper();
        Handler.Singleton c = new Handler.Wrapper();
        Handler.Singleton d = new Handler.Wrapper();

        a.insertHandler(d);
        b.insertHandler(c);
        c.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean process(Request request, Response response, Callback callback)
            {
                return false;
            }
        });

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> a.insertHandler(b));
        assertThat(e.getMessage(), containsString("bad tail"));
    }

    @Test
    public void testSetServerPropagation()
    {
        Handler.Singleton wrapper = new Handler.Wrapper();
        Handler.Sequence collection = new Handler.Sequence();
        Handler handler = new Handler.Abstract()
        {
            @Override
            public boolean process(Request request, Response response, Callback callback) throws Exception
            {
                return false;
            }
        };

        collection.addHandler(wrapper);
        wrapper.setHandler(handler);

        Server server = new Server();
        collection.setServer(server);

        assertThat(handler.getServer(), sameInstance(server));
    }

    @Test
    public void testSetHandlerServerPropagation()
    {
        Handler.Singleton wrapper = new Handler.Wrapper();
        Handler.Sequence collection = new Handler.Sequence();
        Handler handler = new Handler.Abstract()
        {
            @Override
            public boolean process(Request request, Response response, Callback callback) throws Exception
            {
                return false;
            }
        };

        Server server = new Server();
        collection.setServer(server);

        collection.addHandler(wrapper);
        wrapper.setHandler(handler);

        assertThat(handler.getServer(), sameInstance(server));
    }
}
