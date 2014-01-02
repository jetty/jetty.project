//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.mux;

import java.nio.ByteBuffer;

import org.eclipse.jetty.websocket.api.UpgradeRequest;

public class MuxRequest extends UpgradeRequest
{
    public static final String HEADER_VALUE_DELIM="\"\\\n\r\t\f\b%+ ;=";

    public static UpgradeRequest merge(UpgradeRequest baseReq, UpgradeRequest deltaReq)
    {
        MuxRequest req = new MuxRequest(baseReq);

        // TODO: finish

        return req;
    }

    private static String overlay(String val, String defVal)
    {
        if (val == null)
        {
            return defVal;
        }
        return val;
    }

    public static UpgradeRequest parse(ByteBuffer handshake)
    {
        MuxRequest req = new MuxRequest();
        // TODO Auto-generated method stub
        return req;
    }

    public MuxRequest()
    {
        super();
    }

    public MuxRequest(UpgradeRequest copy)
    {
        super();
    }
}
