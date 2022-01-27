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

package org.eclipse.jetty.websocket.core.autobahn;

import java.nio.ByteBuffer;
import java.time.Duration;

import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.core.CoreSession;
import org.eclipse.jetty.websocket.core.TestMessageHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AutobahnFrameHandler extends TestMessageHandler
{
    protected static final Logger LOG = LoggerFactory.getLogger(AutobahnFrameHandler.class);

    @Override
    public void onOpen(CoreSession coreSession, Callback callback)
    {
        coreSession.setIdleTimeout(Duration.ofSeconds(5));
        coreSession.setMaxTextMessageSize(Integer.MAX_VALUE);
        coreSession.setMaxBinaryMessageSize(Integer.MAX_VALUE);
        super.onOpen(coreSession, callback);
    }

    @Override
    public void onBinary(ByteBuffer wholeMessage, Callback callback)
    {
        sendBinary(wholeMessage, callback, false);
    }

    @Override
    public void onText(String wholeMessage, Callback callback)
    {
        sendText(wholeMessage, callback, false);
    }

    @Override
    public void onError(Throwable cause, Callback callback)
    {
        LOG.warn("Error from AutobahnFrameHandler: {}", cause.toString());
        super.onError(cause, callback);
    }
}
