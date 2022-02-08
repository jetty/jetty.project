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

package org.eclipse.jetty.websocket.javax.common;

import javax.websocket.SendHandler;
import javax.websocket.SendResult;

import org.eclipse.jetty.util.Callback;

/**
 * Wrapper of user provided {@link SendHandler} to Jetty internal {@link Callback}
 */
public class SendHandlerCallback implements Callback
{
    private final SendHandler sendHandler;

    public SendHandlerCallback(SendHandler sendHandler)
    {
        this.sendHandler = sendHandler;
    }

    @Override
    public void failed(Throwable x)
    {
        sendHandler.onResult(new SendResult(x));
    }

    @Override
    public void succeeded()
    {
        sendHandler.onResult(new SendResult());
    }
}
