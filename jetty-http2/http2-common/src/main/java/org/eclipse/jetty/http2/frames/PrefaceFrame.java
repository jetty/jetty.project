//
//  ========================================================================
//  Copyright (c) 1995-2015 Mort Bay Consulting Pty. Ltd.
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

public class PrefaceFrame extends Frame
{
    public static final byte[] PREFACE_BYTES = new byte[]
            {
                    0x50, 0x52, 0x49, 0x20, 0x2a, 0x20, 0x48, 0x54,
                    0x54, 0x50, 0x2f, 0x32, 0x2e, 0x30, 0x0d, 0x0a,
                    0x0d, 0x0a, 0x53, 0x4d, 0x0d, 0x0a, 0x0d, 0x0a
            };

    public PrefaceFrame()
    {
        super(FrameType.PREFACE);
    }
}
