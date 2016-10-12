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

package org.eclipse.jetty.http2.generator;

import java.nio.ByteBuffer;

import org.eclipse.jetty.http2.frames.Frame;
import org.eclipse.jetty.http2.frames.PrefaceFrame;
import org.eclipse.jetty.io.ByteBufferPool;

public class PrefaceGenerator extends FrameGenerator
{
    public PrefaceGenerator()
    {
        super(null);
    }

    @Override
    public int generate(ByteBufferPool.Lease lease, Frame frame)
    {
        lease.append(ByteBuffer.wrap(PrefaceFrame.PREFACE_BYTES), false);
        return PrefaceFrame.PREFACE_BYTES.length;
    }
}
