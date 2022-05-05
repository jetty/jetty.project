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

package org.eclipse.jetty.client.util;

import java.nio.ByteBuffer;
import java.util.stream.Stream;

import org.eclipse.jetty.client.api.Request;

/**
 * A {@link Request.Content} for byte arrays.
 */
public class BytesRequestContent extends ByteBufferRequestContent
{
    public BytesRequestContent(byte[]... bytes)
    {
        this("application/octet-stream", bytes);
    }

    public BytesRequestContent(String contentType, byte[]... bytes)
    {
        super(contentType, Stream.of(bytes).map(ByteBuffer::wrap).toList());
    }
}
