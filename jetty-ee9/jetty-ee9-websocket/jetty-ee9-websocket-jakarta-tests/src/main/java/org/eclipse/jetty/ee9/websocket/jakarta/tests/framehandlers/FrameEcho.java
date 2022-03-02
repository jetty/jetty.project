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

package org.eclipse.jetty.ee9.websocket.jakarta.tests.framehandlers;

import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.core.CloseStatus;
import org.eclipse.jetty.websocket.core.CoreSession;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.FrameHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FrameEcho implements FrameHandler
{
    private static final Logger LOG = LoggerFactory.getLogger(FrameEcho.class);

    private CoreSession coreSession;

    @Override
    public void onOpen(CoreSession coreSession, Callback callback)
    {
        this.coreSession = coreSession;
        callback.succeeded();
    }

    @Override
    public void onFrame(Frame frame, Callback callback)
    {
        if (frame.isControlFrame())
            callback.succeeded();
        else
            coreSession.sendFrame(Frame.copy(frame), callback, false);
    }

    @Override
    public void onError(Throwable cause, Callback callback)
    {
        if (LOG.isDebugEnabled())
            LOG.debug(this + " onError ", cause);
        callback.succeeded();
    }

    @Override
    public void onClosed(CloseStatus closeStatus, Callback callback)
    {
        coreSession = null;
        callback.succeeded();
    }
}
