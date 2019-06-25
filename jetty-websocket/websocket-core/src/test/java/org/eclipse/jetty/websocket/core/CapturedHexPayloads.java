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

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.toolchain.test.Hex;
import org.eclipse.jetty.util.Callback;

public class CapturedHexPayloads implements OutgoingFrames
{
    private List<String> captured = new ArrayList<>();

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
