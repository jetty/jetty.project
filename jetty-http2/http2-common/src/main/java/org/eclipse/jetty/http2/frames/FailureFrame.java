//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.http2.frames;

public class FailureFrame extends Frame
{
    private final int error;
    private final String reason;

    public FailureFrame(int error, String reason)
    {
        super(FrameType.FAILURE);
        this.error = error;
        this.reason = reason;
    }

    public int getError()
    {
        return error;
    }

    public String getReason()
    {
        return reason;
    }
}
