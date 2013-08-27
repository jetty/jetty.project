//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.common.extensions.compress;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.WriteCallback;
import org.eclipse.jetty.websocket.api.extensions.Frame;
import org.eclipse.jetty.websocket.api.extensions.OutgoingFrames;
import org.eclipse.jetty.websocket.common.Hex;
import org.eclipse.jetty.websocket.common.OpCode;

public class CapturedHexPayloads implements OutgoingFrames
{
    private static final Logger LOG = Log.getLogger(CapturedHexPayloads.class);
    private List<String> captured = new ArrayList<>();

    @Override
    public void outgoingFrame(Frame frame, WriteCallback callback)
    {
        String hexPayload = Hex.asHex(frame.getPayload());
        LOG.debug("outgoingFrame({}: \"{}\", {})",
                OpCode.name(frame.getOpCode()),
                hexPayload, callback!=null?callback.getClass().getSimpleName():"<null>");
        captured.add(hexPayload);
        if (callback != null)
        {
            callback.writeSuccess();
        }
    }

    public List<String> getCaptured()
    {
        return captured;
    }
}
