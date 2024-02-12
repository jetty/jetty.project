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

package org.eclipse.jetty.tests.multipart;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.List;

public interface MultiPartResults
{
    public int getCount();

    public List<PartResult> get(String name);

    interface PartResult
    {
        String getContentType();

        ByteBuffer asByteBuffer() throws IOException;

        String asString(Charset charset) throws IOException;

        String getFileName();

        InputStream asInputStream() throws IOException;
    }
}
