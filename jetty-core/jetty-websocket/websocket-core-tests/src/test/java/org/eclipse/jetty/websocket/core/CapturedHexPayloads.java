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

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.toolchain.test.Hex;
import org.eclipse.jetty.util.Callback;

public class CapturedHexPayloads implements OutgoingFrames
{
    private final List<String> captured = new ArrayList<>();

    @Override
    public void sendFrame(Frame frame, Callback callback, boolean batch)
    {
        String hexPayload = Hex.asHex(frame.getPayload());
        captured.add(hexPayload);
        if (callback != null)
        {
            callback.succeeded();
        }
    }

    public List<String> getCaptured()
    {
        return captured;
    }
}
