//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.websocket.common.frames;

import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.websocket.common.OpCode;

public class CloseFrame extends ControlFrame
{
    public CloseFrame()
    {
        super(OpCode.CLOSE);
    }

    @Override
    public Type getType()
    {
        return Type.CLOSE;
    }

    /**
     * Truncate arbitrary reason into something that will fit into the CloseFrame limits.
     *
     * @param reason the arbitrary reason to possibly truncate.
     * @return the possibly truncated reason string.
     */
    public static String truncate(String reason)
    {
        return StringUtil.truncate(reason, (ControlFrame.MAX_CONTROL_PAYLOAD - 2));
    }
}
