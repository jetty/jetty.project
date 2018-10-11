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
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.core.CloseStatus;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.FrameHandler;

public class FrameEcho implements FrameHandler
{
    private Logger LOG = Log.getLogger(FrameEcho.class);

    private CoreSession coreSession;

    @Override
    public void onOpen(CoreSession coreSession) throws Exception
    {
        this.coreSession = coreSession;
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
    public void onClosed(CloseStatus closeStatus)
    {
        coreSession = null;
    }

    @Override
    public void onError(Throwable cause) throws Exception
    {
        if (LOG.isDebugEnabled())
            LOG.debug(this + " onError ", cause);
    }
}
