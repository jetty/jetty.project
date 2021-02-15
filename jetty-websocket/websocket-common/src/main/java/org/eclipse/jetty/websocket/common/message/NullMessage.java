//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.websocket.common.message;

import java.nio.ByteBuffer;

public class NullMessage implements MessageAppender
{
    public static final MessageAppender INSTANCE = new NullMessage();

    @Override
    public void appendFrame(ByteBuffer framePayload, boolean isLast)
    {
        // consume payload
        framePayload.position(framePayload.limit());
    }

    @Override
    public void messageComplete()
    {
    }
}
