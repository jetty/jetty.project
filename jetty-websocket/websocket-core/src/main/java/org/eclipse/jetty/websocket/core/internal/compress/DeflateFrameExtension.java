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

package org.eclipse.jetty.websocket.core.internal.compress;

import org.eclipse.jetty.websocket.core.internal.WebSocketCoreSession;

/**
 * Implementation of the
 * <a href="https://tools.ietf.org/id/draft-tyoshino-hybi-websocket-perframe-deflate.txt">deflate-frame</a>
 * extension seen out in the wild.
 */
public class DeflateFrameExtension extends CompressExtension
{
    @Override
    public String getName()
    {
        return "deflate-frame";
    }

    @Override
    CompressionMode getCompressionMode()
    {
        return CompressionMode.FRAME;
    }

    @Override
    public void setWebSocketCoreSession(WebSocketCoreSession coreSession)
    {
        // Frame auto-fragmentation must not be used with DeflateFrameExtension
        coreSession.setAutoFragment(false);
        super.setWebSocketCoreSession(coreSession);
    }
}
