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
