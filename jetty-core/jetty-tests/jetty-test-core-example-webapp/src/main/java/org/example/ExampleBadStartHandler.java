//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.example;

import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;

/**
 * A Handler that will always fail to startup.
 */
public class ExampleBadStartHandler extends Handler.Abstract
{
    @Override
    public boolean handle(Request request, Response response, Callback callback)
    {
        throw new IllegalStateException("This code should never have run, this Handler should have never started.");
    }

    @Override
    protected void doStart()
    {
        throw new RuntimeException("Example of failing startup");
    }
}
