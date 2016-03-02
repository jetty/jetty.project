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

public class UriFileInitializer implements FileInitializer
{
    private final static String[] SUPPORTED_SCHEMES = { "http", "https" };
    protected final BaseHome baseHome;
    
    public UriFileInitializer(BaseHome baseHome)
    {
        this.baseHome = baseHome;
    }
    
    @Override
    public boolean init(URI uri, Path file, String fileRef) throws IOException
    {
        if (!isSupportedScheme(uri))
        {
            // Not a supported scheme.
            return false;
        }

        if(isFilePresent(file, baseHome.getPath(fileRef)))
        {
            // All done
            return false;
        }

        download(uri,file);

        return true;
    }

    protected void download(URI uri, Path file) throws IOException
    {
        StartLog.log("DOWNLOAD","%s to %s",uri,baseHome.toShortForm(file));

        FS.ensureDirectoryExists(file.getParent());
        
        HttpURLConnection http = (HttpURLConnection)uri.toURL().openConnection();
        http.setInstanceFollowRedirects(true);
        http.setAllowUserInteraction(false);
        
        int status = http.getResponseCode();
        
        if(status != HttpURLConnection.HTTP_OK)
        {
            throw new IOException("URL GET Failure [" + status + "/" + http.getResponseMessage() + "] on " + uri);
        }

        byte[] buf = new byte[8192];
        try (InputStream in = http.getInputStream(); OutputStream out = Files.newOutputStream(file,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE))
        {
            while (true)
            {
                int len = in.read(buf);

                if (len > 0)
                {
                    out.write(buf,0,len);
                }
                if (len < 0)
                {
                    break;
                }
            }
        }
    }

    /**
     * Test if any of the Paths exist (as files)
     * 
     * @param paths
     *            the list of paths to check
     * @return true if the path exist (as a file), false if it doesn't exist
     * @throws IOException
     *             if the path points to a non-file, or is not readable.
     */
    protected boolean isFilePresent(Path... paths) throws IOException
    {
        for (Path file : paths)
        {
            if (Files.exists(file))
            {
                if (Files.isDirectory(file))
                {
                    throw new IOException("Directory in the way: " + file.toAbsolutePath());
                }

                if (!Files.isReadable(file))
                {
                    throw new IOException("File not readable: " + file.toAbsolutePath());
                }

                return true;
            }
        }

        return false;
    }

    private boolean isSupportedScheme(URI uri)
    {
        String scheme = uri.getScheme();
        if (scheme == null)
        {
            return false;
        }
        for (String supported : SUPPORTED_SCHEMES)
        {
            if (supported.equalsIgnoreCase(scheme))
            {
                return true;
            }
        }
        return false;
    }
}
