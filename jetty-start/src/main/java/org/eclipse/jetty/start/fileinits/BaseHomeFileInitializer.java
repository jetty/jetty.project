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
import org.eclipse.jetty.start.StartLog;

/**
 * Copy a file found in {@link BaseHome} from a URI of the form
 * "basehome:some/path"
 * {@link FileInitializer}
 */
public class BaseHomeFileInitializer extends FileInitializer
{    
    public BaseHomeFileInitializer(BaseHome basehome)
    {
        super(basehome,"basehome");
    }
    
    @Override
    public boolean create(URI uri, String location) throws IOException
    {
        if (uri.getSchemeSpecificPart().startsWith("/"))
            throw new IllegalArgumentException(String.format("Bad file arg: %s",uri));
        
        Path source = _basehome.getPath(uri.getSchemeSpecificPart());

        if (!FS.exists(source))
            throw new IllegalArgumentException(String.format("File does not exist: %s",uri));
        
        Path destination = location==null?_basehome.getBasePath():getDestination(uri,location);
     
        boolean modified=false;
        
        if (Files.isDirectory(source))
        {
            // Check destination
            if (destination!=null && Files.exists(destination))
            {
                if (!Files.isDirectory(destination))
                {
                    StartLog.error("Cannot copy directory %s to file %s",source,destination);
                    return false;
                }
            }
            else if (FS.ensureDirectoryExists(destination))
            {
                modified = true;
                StartLog.log("MKDIR",_basehome.toShortForm(destination));
            }

            copyDirectory(source,destination);
        }
        else
        {
            if (FS.ensureDirectoryExists(destination.getParent()))
            {
                modified = true;
                StartLog.log("MKDIR",_basehome.toShortForm(destination.getParent()));
            }

            if (!FS.exists(destination))
            {
                StartLog.log("COPY ","%s to %s",_basehome.toShortForm(source),_basehome.toShortForm(destination));
                Files.copy(source,destination);
                modified = true;
            }
        }

        return modified;
    }



}
