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
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import org.eclipse.jetty.start.BaseHome;
import org.eclipse.jetty.start.FS;
import org.eclipse.jetty.start.FileInitializer;
import org.eclipse.jetty.start.StartLog;

public class UriFileInitializer extends FileInitializer
{    
    public UriFileInitializer(BaseHome baseHome)
    {
        super(baseHome,"http", "https");
    }
    
    @Override
    public boolean create(URI uri, String location) throws IOException
    {
        Path destination = getDestination(uri,location);
        
        if (Files.isDirectory(destination))
            destination = destination.resolve(uri.getSchemeSpecificPart().substring(uri.getRawSchemeSpecificPart().lastIndexOf('/')+1));
        
        if(isFilePresent(destination))
            return false;

        download(uri,destination);

        return true;
    }
}
