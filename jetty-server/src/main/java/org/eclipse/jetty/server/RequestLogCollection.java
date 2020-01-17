//
//  ========================================================================
//  Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.server;

import java.util.ArrayList;

import static java.util.Arrays.asList;

class RequestLogCollection
    implements RequestLog
{
    private final ArrayList<RequestLog> delegates;

    public RequestLogCollection(RequestLog... requestLogs)
    {
        delegates = new ArrayList<>(asList(requestLogs));
    }

    public void add(RequestLog requestLog)
    {
        delegates.add(requestLog);
    }

    @Override
    public void log(Request request, Response response)
    {
        for (RequestLog delegate : delegates)
        {
            delegate.log(request, response);
        }
    }
}
