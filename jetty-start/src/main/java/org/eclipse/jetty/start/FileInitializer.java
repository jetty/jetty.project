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

package org.eclipse.jetty.start;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;

/**
 * Interface for initializing a file resource.
 */
public interface FileInitializer
{
    /**
     * Initialize a file resource
     * 
     * @param uri
     *            the remote URI of the resource acting as its source
     * @param file
     *            the local file resource to initialize. (often in ${jetty.base} directory) 
     * @param fileRef
     *            the simple string reference to the output file, suitable for searching
     *            for the file in other locations (like ${jetty.home} or ${jetty.dir})
     * @return true if local file is initialized (resulted in a change on disk), false if this
     *         {@link FileInitializer} did nothing.
     * @throws IOException
     *             if there was an attempt to initialize, but an error occurred.
     */
    public boolean init(URI uri, Path file, String fileRef) throws IOException;
}
