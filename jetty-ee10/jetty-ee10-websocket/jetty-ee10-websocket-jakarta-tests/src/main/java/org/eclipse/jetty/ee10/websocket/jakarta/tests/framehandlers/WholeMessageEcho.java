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

package org.eclipse.jetty.ee10.websocket.jakarta.tests.framehandlers;

import java.nio.ByteBuffer;

import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.core.internal.MessageHandler;

public class WholeMessageEcho extends MessageHandler
{
    @Override
    public void onBinary(ByteBuffer wholeMessage, Callback callback)
    {
        sendBinary(wholeMessage, callback, false);
    }

    @Override
    public void onText(String wholeMessage, Callback callback)
    {
        sendText(wholeMessage, callback, false);
    }
}
