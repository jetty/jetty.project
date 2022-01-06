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

package org.eclipse.jetty.websocket.core;

import org.eclipse.jetty.util.Callback;

public class AutoDemandingTestFrameHandler extends TestFrameHandler
{
    @Override
    public void onOpen(CoreSession coreSession, Callback callback)
    {
        super.onOpen(coreSession, callback);
        coreSession.demand(1);
    }

    @Override
    public void onFrame(Frame frame, Callback callback)
    {
        super.onFrame(frame, callback);
        coreSession.demand(1);
    }

    @Override
    public boolean isDemanding()
    {
        return true;
    }
}
