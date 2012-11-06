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

package org.eclipse.jetty.websocket.common.extensions.identity;

import javax.net.websocket.extensions.FrameHandler;

import org.eclipse.jetty.websocket.common.WebSocketFrame;
import org.eclipse.jetty.websocket.common.extensions.AbstractJettyFrameHandler;

/**
 * FrameHandler that just passes frames through with no modification.
 */
public class IdentityFrameHandler extends AbstractJettyFrameHandler
{
    public IdentityFrameHandler(FrameHandler nextHandler)
    {
        super(nextHandler);
    }

    @Override
    public void handleJettyFrame(WebSocketFrame frame)
    {
        nextJettyHandler(frame);
    }
}
