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

package org.eclipse.jetty.ee9.servlets;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This is an extension to {@link DoSFilter} that uses Jetty APIs to
 * abruptly close the connection when the request times out.
 */

public class CloseableDoSFilter extends DoSFilter
{
    private static final Logger LOG = LoggerFactory.getLogger(CloseableDoSFilter.class);

    @Override
    protected void onRequestTimeout(HttpServletRequest request, HttpServletResponse response, Thread handlingThread)
    {
        try
        {
            response.sendError(-1);
        }
        catch (IOException e)
        {
            LOG.debug("Unable to trigger channel close", e);
        }
    }
}
