//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.common.extensions;


import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.extensions.Frame;
import org.eclipse.jetty.websocket.api.extensions.FrameHandler;
import org.eclipse.jetty.websocket.api.extensions.FrameHandlerWrapper;

public abstract class FrameHandlerAdapter implements FrameHandlerWrapper
{
    private Logger log;
    private FrameHandler nextHandler;

    public FrameHandlerAdapter()
    {
        log = Log.getLogger(this.getClass());
    }

    @Override
    public FrameHandler getNextHandler()
    {
        return nextHandler;
    }

    protected void nextHandler(Frame frame)
    {
        if (log.isDebugEnabled())
        {
            log.debug("nextHandler({}) -> {}",frame,nextHandler);
        }
        nextHandler.handleFrame(frame);
    }

    @Override
    public void setNextHandler(FrameHandler handler)
    {
        this.nextHandler = handler;
    }
}
