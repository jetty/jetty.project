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

/**
 * HandlerList.
 * This extension of {@link HandlerCollection} will call
 * each contained handler in turn until either an exception is thrown, the response
 * is committed or a positive response status is set.
 */
public class HandlerList extends HandlerCollection
{
    public HandlerList()
    {
    }

    public HandlerList(Handler... handlers)
    {
        super(handlers);
    }

    @Override
    public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response)
        throws IOException, ServletException
    {
        Handler[] handlers = getHandlers();

        if (handlers != null && isStarted())
        {
            for (int i = 0; i < handlers.length; i++)
            {
                handlers[i].handle(target, baseRequest, request, response);
                if (baseRequest.isHandled())
                    return;
            }
        }
    }
}
