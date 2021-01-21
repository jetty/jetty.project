//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class ScopedHandlerTest
{
    private StringBuilder _history = new StringBuilder();

    @BeforeEach
    public void resetHistory()
    {
        _history.setLength(0);
    }

    @Test
    public void testSingle() throws Exception
    {
        TestHandler handler0 = new TestHandler("0");
        handler0.setServer(new Server());
        handler0.start();
        handler0.handle("target", null, null, null);
        handler0.stop();
        String history = _history.toString();
        assertEquals(">S0>W0<W0<S0", history);
    }

    @Test
    public void testSimpleDouble() throws Exception
    {
        TestHandler handler0 = new TestHandler("0");
        TestHandler handler1 = new TestHandler("1");
        handler0.setServer(new Server());
        handler1.setServer(handler0.getServer());
        handler0.setHandler(handler1);
        handler0.start();
        handler0.handle("target", null, null, null);
        handler0.stop();
        String history = _history.toString();
        assertEquals(">S0>S1>W0>W1<W1<W0<S1<S0", history);
    }

    @Test
    public void testSimpleTriple() throws Exception
    {
        TestHandler handler0 = new TestHandler("0");
        TestHandler handler1 = new TestHandler("1");
        TestHandler handler2 = new TestHandler("2");
        handler0.setServer(new Server());
        handler1.setServer(handler0.getServer());
        handler2.setServer(handler0.getServer());
        handler0.setHandler(handler1);
        handler1.setHandler(handler2);
        handler0.start();
        handler0.handle("target", null, null, null);
        handler0.stop();
        String history = _history.toString();
        assertEquals(">S0>S1>S2>W0>W1>W2<W2<W1<W0<S2<S1<S0", history);
    }

    @Test
    public void testDouble() throws Exception
    {
        Request request = new Request(null, null);
        Response response = new Response(null, null);

        TestHandler handler0 = new TestHandler("0");
        OtherHandler handlerA = new OtherHandler("A");
        TestHandler handler1 = new TestHandler("1");
        OtherHandler handlerB = new OtherHandler("B");
        handler0.setServer(new Server());
        handlerA.setServer(handler0.getServer());
        handler1.setServer(handler0.getServer());
        handlerB.setServer(handler0.getServer());
        handler0.setHandler(handlerA);
        handlerA.setHandler(handler1);
        handler1.setHandler(handlerB);
        handler0.start();
        handler0.handle("target", request, request, response);
        handler0.stop();
        String history = _history.toString();
        assertEquals(">S0>S1>W0>HA>W1>HB<HB<W1<HA<W0<S1<S0", history);
    }

    @Test
    public void testTriple() throws Exception
    {
        Request request = new Request(null, null);
        Response response = new Response(null, null);

        TestHandler handler0 = new TestHandler("0");
        OtherHandler handlerA = new OtherHandler("A");
        TestHandler handler1 = new TestHandler("1");
        OtherHandler handlerB = new OtherHandler("B");
        TestHandler handler2 = new TestHandler("2");
        OtherHandler handlerC = new OtherHandler("C");
        handler0.setServer(new Server());
        handlerA.setServer(handler0.getServer());
        handler1.setServer(handler0.getServer());
        handlerB.setServer(handler0.getServer());
        handler2.setServer(handler0.getServer());
        handlerC.setServer(handler0.getServer());
        handler0.setHandler(handlerA);
        handlerA.setHandler(handler1);
        handler1.setHandler(handlerB);
        handlerB.setHandler(handler2);
        handler2.setHandler(handlerC);
        handler0.start();
        handler0.handle("target", request, request, response);
        handler0.stop();
        String history = _history.toString();
        assertEquals(">S0>S1>S2>W0>HA>W1>HB>W2>HC<HC<W2<HB<W1<HA<W0<S2<S1<S0", history);
    }

    private class TestHandler extends ScopedHandler
    {
        private final String _name;

        private TestHandler(String name)
        {
            _name = name;
        }

        @Override
        public void doScope(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            try
            {
                _history.append(">S").append(_name);
                super.nextScope(target, baseRequest, request, response);
            }
            finally
            {
                _history.append("<S").append(_name);
            }
        }

        @Override
        public void doHandle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            try
            {
                _history.append(">W").append(_name);
                super.nextHandle(target, baseRequest, request, response);
            }
            finally
            {
                _history.append("<W").append(_name);
            }
        }
    }

    private class OtherHandler extends HandlerWrapper
    {
        private final String _name;

        private OtherHandler(String name)
        {
            _name = name;
        }

        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            try
            {
                _history.append(">H").append(_name);
                super.handle(target, baseRequest, request, response);
            }
            finally
            {
                _history.append("<H").append(_name);
            }
        }
    }
}
