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

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.ee10.servlet.ServletContextRequest;

/**
 * This is an extension to {@link DoSFilter} that uses Jetty APIs to
 * abruptly close the connection when the request times out.
 */

public class CloseableDoSFilter extends DoSFilter
{
    @Override
    protected void onRequestTimeout(HttpServletRequest request, HttpServletResponse response, Thread handlingThread)
    {
        //TODO: need to change visibility of getServletChannel to make this work
        ServletContextRequest baseRequest = ServletContextRequest.getBaseRequest(request);   
        baseRequest.getServletChannel().getEndPoint().close();
    }
}
