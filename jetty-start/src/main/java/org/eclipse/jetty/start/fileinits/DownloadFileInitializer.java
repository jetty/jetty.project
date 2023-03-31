//
// ========================================================================
// Copyright (c) 1995-2023 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.start.fileinits;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import org.eclipse.jetty.start.BaseHome;
import org.eclipse.jetty.start.FS;
import org.eclipse.jetty.start.FileInitializer;
import org.eclipse.jetty.start.StartArgs;
import org.eclipse.jetty.start.StartLog;

public abstract class DownloadFileInitializer extends FileInitializer
{
    protected final boolean _allowInsecureHttpDownloads;

    protected DownloadFileInitializer(StartArgs startArgs, BaseHome basehome, String... scheme)
    {
        super(basehome, scheme);
        _allowInsecureHttpDownloads = startArgs.isAllowInsecureHttpDownloads();
    }

    protected boolean allowInsecureHttpDownloads()
    {
        return _allowInsecureHttpDownloads;
    }

    protected void download(URI uri, Path destination) throws IOException
    {
        if ("http".equalsIgnoreCase(uri.getScheme()) && !allowInsecureHttpDownloads())
            throw new IOException("Insecure HTTP download not allowed (use " + StartArgs.ARG_ALLOW_INSECURE_HTTP_DOWNLOADS + " to bypass): " + uri);

        if (FS.ensureDirectoryExists(destination.getParent()))
            StartLog.info("mkdir " + _basehome.toShortForm(destination.getParent()));

        StartLog.info("download %s to %s", uri, _basehome.toShortForm(destination));

        URLConnection connection = uri.toURL().openConnection();

        if (connection instanceof HttpURLConnection)
        {
            HttpURLConnection http = (HttpURLConnection)uri.toURL().openConnection();
            http.setInstanceFollowRedirects(true);
            http.setAllowUserInteraction(false);

            int status = http.getResponseCode();

            if (status != HttpURLConnection.HTTP_OK)
            {
                throw new IOException("URL GET Failure [" + status + "/" + http.getResponseMessage() + "] on " + uri);
            }
        }

        try (InputStream in = connection.getInputStream())
        {
            Files.copy(in, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
