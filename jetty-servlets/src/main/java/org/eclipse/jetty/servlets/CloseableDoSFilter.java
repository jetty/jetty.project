//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.servlets;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.HttpChannel;
import org.eclipse.jetty.server.Request;

/* ------------------------------------------------------------ */
/** Closeable DoS Filter.
 * This is an extension to the {@link DoSFilter} that uses Jetty APIs to allow
 * connections to be closed cleanly.
 */

public class CloseableDoSFilter extends DoSFilter
{
    @Override
    protected void closeConnection(HttpServletRequest request, HttpServletResponse response, Thread thread)
    {
        Request base_request=(request instanceof Request)?(Request)request:HttpChannel.getCurrentHttpChannel().getRequest();
        base_request.getHttpChannel().getEndPoint().close();
    }
}
