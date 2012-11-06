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

import javax.net.websocket.extensions.FrameHandler;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.WebSocketException;
import org.eclipse.jetty.websocket.common.WebSocketFrame;
import org.eclipse.jetty.websocket.common.io.IncomingFrames;

/**
 * Route jetty internal frames into extension {@link FrameHandler} concept.
 */
public class IncomingFrameHandler implements IncomingFrames
{
    private static final Logger LOG = Log.getLogger(IncomingFrameHandler.class);
    private FrameHandler extHandler;

    public IncomingFrameHandler(FrameHandler nextHandler)
    {
        this.extHandler = nextHandler;
    }

    @Override
    public void incomingError(WebSocketException e)
    {
        LOG.info("Not able to forward error into extension stack",e);
    }

    @Override
    public void incomingFrame(WebSocketFrame frame)
    {
        extHandler.handleFrame(frame);
    }
}
