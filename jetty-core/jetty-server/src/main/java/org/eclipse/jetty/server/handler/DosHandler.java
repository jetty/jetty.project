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

package org.eclipse.jetty.server.handler;

import java.util.function.Function;

import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;

@Deprecated(since = "12.1.0", forRemoval = true)
public class DosHandler extends DoSHandler
{
    public DosHandler()
    {
        super();
    }

    public DosHandler(Function<Request, String> getId, int maxRequestsPerSecond, int maxTrackers)
    {
        super(getId, maxRequestsPerSecond, maxTrackers);
    }

    public DosHandler(Function<Request, String> getId, RateControl.Factory rateControlFactory, Request.Handler rejectHandler, int maxTrackers)
    {
        super(getId, rateControlFactory, rejectHandler, maxTrackers);
    }

    public DosHandler(Handler handler, Function<Request, String> getId, RateControl.Factory rateControlFactory, Request.Handler rejectHandler, int maxTrackers)
    {
        super(handler, getId, rateControlFactory, rejectHandler, maxTrackers);
    }

    public DosHandler(int maxRequestsPerSecond)
    {
        super(maxRequestsPerSecond);
    }
}
