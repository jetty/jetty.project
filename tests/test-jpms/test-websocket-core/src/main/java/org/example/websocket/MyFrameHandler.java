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

package org.example.websocket;

import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.core.CloseStatus;
import org.eclipse.jetty.websocket.core.CoreSession;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.FrameHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MyFrameHandler implements FrameHandler
{
    private static final Logger LOG = LoggerFactory.getLogger(MyFrameHandler.class);

    private final String _id;

    public MyFrameHandler(String id)
    {
        _id = id;
    }

    @Override
    public void onOpen(CoreSession coreSession, Callback callback)
    {
        LOG.info(_id + " onOpen");
        callback.succeeded();
    }

    @Override
    public void onFrame(Frame frame, Callback callback)
    {
        LOG.info(_id + " onFrame");
        callback.succeeded();
    }

    @Override
    public void onError(Throwable cause, Callback callback)
    {
        LOG.info(_id + " onError");
        callback.succeeded();
    }

    @Override
    public void onClosed(CloseStatus closeStatus, Callback callback)
    {
        LOG.info(_id + " onClosed");
        callback.succeeded();
    }
}
