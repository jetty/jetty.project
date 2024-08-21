//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package com.aayushatharva.brotli4j.encoder;

/**
 * A hack to allow the EncoderJNI.Operation to be seen by Jetty's code.
 *
 * Remove this and use the EncoderJNI.Operation once the fix at brotli4j is released.
 *
 * See <a href="https://github.com/hyperxpro/Brotli4j/issues/169">Request release of brotli4j</a>
 */
public enum EncoderJNIOp
{
    PROCESS(EncoderJNI.Operation.PROCESS),
    FLUSH(EncoderJNI.Operation.FLUSH),
    FINISH(EncoderJNI.Operation.FINISH);

    private final EncoderJNI.Operation op;

    private EncoderJNIOp(EncoderJNI.Operation op)
    {
        this.op = op;
    }

    public EncoderJNI.Operation getOp()
    {
        return op;
    }
}
