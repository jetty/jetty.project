//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.jsr356.tests.framehandlers;

import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.common.AbstractFrameTypeHandler;
import org.eclipse.jetty.websocket.core.frames.Frame;
import org.eclipse.jetty.websocket.core.io.BatchMode;

public class FrameEcho extends AbstractFrameTypeHandler
{
    @Override
    public void onText(org.eclipse.jetty.websocket.core.frames.Frame frame, Callback callback)
    {
        channel.sendFrame(Frame.copy(frame), callback, BatchMode.OFF);
    }

    @Override
    public void onBinary(org.eclipse.jetty.websocket.core.frames.Frame frame, Callback callback)
    {
        channel.sendFrame(Frame.copy(frame), callback, BatchMode.OFF);
    }

    @Override
    public void onContinuation(org.eclipse.jetty.websocket.core.frames.Frame frame, Callback callback)
    {
        channel.sendFrame(Frame.copy(frame), callback, BatchMode.OFF);
    }
}
