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

public interface SynchronousFrameHandler extends FrameHandler
{
    @Override
    default void onOpen(CoreSession coreSession, Callback callback)
    {
        try
        {
            onOpen(coreSession);
            callback.succeeded();
        }
        catch (Throwable t)
        {
            callback.failed(t);
        }
    }

    default void onOpen(CoreSession coreSession) throws Exception
    {
    }

    @Override
    default void onFrame(Frame frame, Callback callback)
    {
        try
        {
            onFrame(frame);
            callback.succeeded();
        }
        catch (Throwable t)
        {
            callback.failed(t);
        }
    }

    default void onFrame(Frame frame) throws Exception
    {
    }

    @Override
    default void onClosed(CloseStatus closeStatus, Callback callback)
    {
        try
        {
            onClosed(closeStatus);
            callback.succeeded();
        }
        catch (Throwable t)
        {
            callback.failed(t);
        }
    }

    default void onClosed(CloseStatus closeStatus) throws Exception
    {
    }

    @Override
    default void onError(Throwable cause, Callback callback)
    {
        try
        {
            onError(cause);
            callback.succeeded();
        }
        catch (Throwable t)
        {
            callback.failed(t);
        }
    }

    default void onError(Throwable cause) throws Exception
    {
    }
}
