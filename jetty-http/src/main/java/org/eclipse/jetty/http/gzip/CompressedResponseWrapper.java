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

import java.io.IOException;
import java.util.Set;

import javax.servlet.http.HttpServletResponse;

/* ------------------------------------------------------------ */
/**
 * A ResponseWrapper interface that adds compress functionality to a ResponseWrapper
 */
public interface CompressedResponseWrapper extends HttpServletResponse
{

    /**
     * Sets the mime types.
     * 
     * @param mimeTypes
     *            the new mime types
     */
    public void setMimeTypes(Set<String> mimeTypes);

    /**
     * Sets the min compress size.
     * 
     * @param minCompressSize
     *            the new min compress size
     */
    public void setMinCompressSize(int minCompressSize);

    /* ------------------------------------------------------------ */
    /**
     * No compression.
     */
    public void noCompression();

    /**
     * Finish.
     * 
     * @throws IOException
     *             Signals that an I/O exception has occurred.
     */
    public void finish() throws IOException;

}