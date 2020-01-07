//
//  ========================================================================
//  Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.server.resource;

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
