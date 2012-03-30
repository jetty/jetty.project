// ========================================================================
// Copyright (c) 2009-2009 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at 
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses. 
// ========================================================================

package org.eclipse.jetty.http.gzip;

import java.io.Closeable;
import java.io.Flushable;
import java.io.IOException;
import java.io.OutputStream;

/* ------------------------------------------------------------ */
/**
* Interface for compressed streams
*/
public interface CompressedStream extends Closeable, Flushable
{

    /**
     * Reset buffer.
     */
    public void resetBuffer();

    /**
     * Sets the content length.
     * 
     * @param length
     *            the new content length
     */
    public void setContentLength(long length);

    /* ------------------------------------------------------------ */
    /**
     * @return true if stream is closed
     */
    public boolean isClosed();

    /**
     * Do compress.
     * 
     * @throws IOException
     *             Signals that an I/O exception has occurred.
     */
    public void doCompress() throws IOException;

    /**
     * Do not compress.
     * 
     * @throws IOException
     *             Signals that an I/O exception has occurred.
     */
    public void doNotCompress() throws IOException;

    /**
     * Finish.
     * 
     * @throws IOException
     *             Signals that an I/O exception has occurred.
     */
    public void finish() throws IOException;

    /* ------------------------------------------------------------ */
    /**
     * @return the {@link OutputStream}
     */
    public OutputStream getOutputStream();

}