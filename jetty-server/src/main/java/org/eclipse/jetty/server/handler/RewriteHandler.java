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

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;

public class RewriteHandler extends Handler.Wrapper
{
    @Override
    public void handle(Request request) throws Exception
    {
        Request rewritten = rewrite(request);
        if (request.isAccepted())
            return;
        super.handle(request);
    }

    protected Request rewrite(Request request)
    {
        // TODO run the rules, but ultimately wrap for any changes:
        return new Request.Wrapper(request)
        {
            @Override
            public HttpURI getHttpURI()
            {
                // TODO return alternative URI
                return super.getHttpURI();
            }

            @Override
            public HttpFields getHeaders()
            {
                // TODO return modified headers
                return super.getHeaders();
            }
        };
    }
}
