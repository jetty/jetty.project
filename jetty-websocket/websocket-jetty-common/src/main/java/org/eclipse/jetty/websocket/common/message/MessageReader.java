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

package org.eclipse.jetty.websocket.common.message;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.common.MessageSink;
import org.eclipse.jetty.websocket.core.Frame;

/**
 * Support class for reading a (single) WebSocket TEXT message via a Reader.
 * <p>
 * In compliance to the WebSocket spec, this reader always uses the {@link StandardCharsets#UTF_8}.
 */
public class MessageReader extends InputStreamReader implements MessageSink
{
    private final MessageInputStream stream;

    public MessageReader(MessageInputStream stream)
    {
        super(stream, StandardCharsets.UTF_8);
        this.stream = stream;
    }

    @Override
    public void accept(Frame frame, Callback callback)
    {
        this.stream.accept(frame, callback);
    }
}
