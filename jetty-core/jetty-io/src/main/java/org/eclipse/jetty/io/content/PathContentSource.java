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

package org.eclipse.jetty.io.content;

import java.nio.file.Path;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Content;

/**
 * <p>A {@link Content.Source} that provides the file content of the passed {@link Path}.</p>
 */
public class PathContentSource extends org.eclipse.jetty.io.internal.PathContentSource
{
    public PathContentSource(Path path)
    {
        super(path);
    }

    public PathContentSource(Path path, ByteBufferPool byteBufferPool)
    {
        super(byteBufferPool instanceof ByteBufferPool.Sized sized ? sized : new ByteBufferPool.Sized(byteBufferPool), path);
    }

    public PathContentSource(Path path, ByteBufferPool.Sized sizedBufferPool)
    {
        super(sizedBufferPool, path);
    }
}
