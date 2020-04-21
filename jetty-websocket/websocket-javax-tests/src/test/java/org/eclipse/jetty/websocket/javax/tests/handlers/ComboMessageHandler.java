//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.websocket.javax.tests.handlers;

import java.nio.ByteBuffer;
import javax.websocket.MessageHandler;

/**
 * A particularly annoying type of MessageHandler. One defining 2 implementations.
 */
public class ComboMessageHandler extends AbstractHandler implements MessageHandler.Whole<String>, MessageHandler.Partial<ByteBuffer>
{
    @Override
    public void onMessage(ByteBuffer partialMessage, boolean last)
    {
        sendBinary(partialMessage, last);
    }

    @Override
    public void onMessage(String message)
    {
        sendText(message, true);
    }
}
