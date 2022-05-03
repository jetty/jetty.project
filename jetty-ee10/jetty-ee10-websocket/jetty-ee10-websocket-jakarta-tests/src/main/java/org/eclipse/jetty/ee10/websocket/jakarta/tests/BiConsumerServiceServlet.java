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

package org.eclipse.jetty.ee10.websocket.jakarta.tests;

import java.io.IOException;
import java.util.function.BiConsumer;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Utility Servlet to make easier testcases
 */
public class BiConsumerServiceServlet extends HttpServlet
{
    private final BiConsumer<HttpServletRequest, HttpServletResponse> consumer;

    public BiConsumerServiceServlet(BiConsumer<HttpServletRequest, HttpServletResponse> consumer)
    {
        this.consumer = consumer;
    }

    @Override
    protected void service(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
    {
        this.consumer.accept(req, resp);
    }
}
