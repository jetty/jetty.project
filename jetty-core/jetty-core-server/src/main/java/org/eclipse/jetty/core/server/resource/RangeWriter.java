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

package org.eclipse.jetty.core.server.resource;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Interface for writing sections (ranges) of a single resource (SeekableByteChannel, Resource, etc) to an outputStream.
 */
public interface RangeWriter extends Closeable
{
    /**
     * Write the specific range (start, size) to the outputStream.
     *
     * @param outputStream the stream to write to
     * @param skipTo the offset / skip-to / seek-to / position in the resource to start the write from
     * @param length the size of the section to write
     */
    void writeTo(OutputStream outputStream, long skipTo, long length) throws IOException;
}
