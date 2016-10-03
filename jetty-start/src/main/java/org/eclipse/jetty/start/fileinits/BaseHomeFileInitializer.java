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
import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.jetty.start.BaseHome;
import org.eclipse.jetty.start.FS;
import org.eclipse.jetty.start.FileInitializer;

/**
 * Copy a file found in {@link BaseHome} from a URI of the form
 * "basehome:some/path"
 * {@link FileInitializer}
 */
public class BaseHomeFileInitializer implements FileInitializer
{
    private final BaseHome _basehome;
    
    public BaseHomeFileInitializer(BaseHome basehome)
    {
        _basehome=basehome;
    }
    
    @Override
    public boolean init(URI uri, Path file, String fileRef) throws IOException
    {
        if (!"basehome".equalsIgnoreCase(uri.getScheme()) || uri.getSchemeSpecificPart().startsWith("/"))
            return false;
        
        Path source = _basehome.getPath(uri.getSchemeSpecificPart());

        if (FS.exists(source) && !FS.exists(file))
        {
            FS.ensureDirectoryExists(file.getParent());
            Files.copy(source,file);
            return true;
        }
        
        return false;
    }
}
