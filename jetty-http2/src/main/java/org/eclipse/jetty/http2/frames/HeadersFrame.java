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

package org.eclipse.jetty.http2.frames;

import org.eclipse.jetty.http.HttpFields;

public class HeadersFrame
{
    private final int streamId;
    private final HttpFields fields;
    private final PriorityFrame priority;
    private final boolean endStream;

    public HeadersFrame(int streamId, HttpFields fields, PriorityFrame priority, boolean endStream)
    {
        this.streamId = streamId;
        this.fields = fields;
        this.priority = priority;
        this.endStream = endStream;
    }
}
