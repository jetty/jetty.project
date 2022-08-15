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

package org.eclipse.jetty.demo;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.util.BufferUtil;

public class HelloHandler extends Handler.Abstract
{
    @Override
    public Request.Processor handle(Request req)
    {
        return (request, response, callback) ->
        {
            response.setStatus(200);
            response.getHeaders().add(HttpHeader.CONTENT_TYPE, "text/plain");
            response.write(true, BufferUtil.toBuffer("Hello World\n"), callback);
        };
    }
}
