//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.websocket.core;

import org.eclipse.jetty.util.Callback;

public class EchoFrameHandler extends TestAsyncFrameHandler
{
    private boolean throwOnFrame;

    public void throwOnFrame()
    {
        throwOnFrame = true;
    }

    public EchoFrameHandler(String name)
    {
        super(name);
    }

    @Override
    public void onFrame(Frame frame, Callback callback)
    {
        LOG.info("[{}] onFrame {}", name, frame);
        receivedFrames.offer(Frame.copy(frame));

        if (throwOnFrame)
            throw new RuntimeException("intentionally throwing in server onFrame()");

        if (frame.isDataFrame())
        {
            LOG.info("[{}] echoDataFrame {}", name, frame);
            coreSession.sendFrame(new Frame(frame.getOpCode(), frame.getPayload()), callback, false);
        }
        else
        {
            callback.succeeded();
        }
    }
}
