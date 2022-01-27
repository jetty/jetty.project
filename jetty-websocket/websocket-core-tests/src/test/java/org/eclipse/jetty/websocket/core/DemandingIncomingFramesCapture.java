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

package org.eclipse.jetty.websocket.core;

import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.core.internal.WebSocketCoreSession;

public class DemandingIncomingFramesCapture extends IncomingFramesCapture
{
    private final WebSocketCoreSession _coreSession;

    public DemandingIncomingFramesCapture(WebSocketCoreSession coreSession)
    {
        _coreSession = coreSession;
    }

    @Override
    public void onFrame(Frame frame, Callback callback)
    {
        try
        {
            super.onFrame(frame, callback);
        }
        finally
        {
            if (!_coreSession.isDemanding())
                _coreSession.autoDemand();
        }
    }
}
