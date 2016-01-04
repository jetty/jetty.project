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

package org.eclipse.jetty.start.fileinits;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;

import org.eclipse.jetty.start.FS;
import org.eclipse.jetty.start.FileInitializer;
import org.eclipse.jetty.start.StartLog;

/**
 * In a start testing scenario, it is often not important to actually download
 * or initialize a file, this implementation is merely a no-op for the
 * {@link FileInitializer}
 */
public class TestFileInitializer implements FileInitializer
{
    @Override
    public boolean init(URI uri, Path file, String fileRef) throws IOException
    {
        FS.ensureDirectoryExists(file.getParent());

        StartLog.log("TESTING MODE","Skipping download of " + uri);
        return true;
    }
}
