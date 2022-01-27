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
