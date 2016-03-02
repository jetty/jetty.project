//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

import java.nio.charset.StandardCharsets;

public class PrefaceFrame extends Frame
{
    /**
     * The bytes of the HTTP/2 preface that form a legal HTTP/1.1
     * request, used in the direct upgrade.
     */
    public static final byte[] PREFACE_PREAMBLE_BYTES = (
            "PRI * HTTP/2.0\r\n" +
            "\r\n"
    ).getBytes(StandardCharsets.US_ASCII);

    /**
     * The HTTP/2 preface bytes.
     */
    public static final byte[] PREFACE_BYTES = (
            "PRI * HTTP/2.0\r\n" +
            "\r\n" +
            "SM\r\n" +
            "\r\n"
    ).getBytes(StandardCharsets.US_ASCII);

    public PrefaceFrame()
    {
        super(FrameType.PREFACE);
    }
}
