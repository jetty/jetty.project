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

package org.eclipse.jetty.client;

import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Blocker;
import org.eclipse.jetty.util.Callback;

public class EmptyServerHandler extends Handler.Processor.Blocking
{
    protected Blocker.Shared _blocking = new Blocker.Shared();

    @Override
    public void doProcess(Request request, Response response, Callback callback) throws Exception
    {
        try
        {
            service(request, response);
            callback.succeeded();
        }
        catch (Throwable t)
        {
            callback.failed(t);
        }
    }

    protected void service(Request request, Response response) throws Throwable
    {
    }
}
