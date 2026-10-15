//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.jetty.start.BaseHome;
import org.eclipse.jetty.start.StartArgs;
import org.eclipse.jetty.start.StartLog;

public class UriFileInitializer extends DownloadFileInitializer
{
    protected final boolean _allowInsecureHttpDownloads;

    public UriFileInitializer(StartArgs startArgs, BaseHome baseHome)
    {
        super(baseHome, "http", "https");
        _allowInsecureHttpDownloads = startArgs.isAllowInsecureHttpDownloads();
    }

    @Override
    protected boolean allowInsecureHttpDownloads()
    {
        return _allowInsecureHttpDownloads;
    }

    @Override
    public boolean exists(URI uri) throws IOException
    {
        HttpClient httpClient = getHttpClient();

        try
        {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(uri)
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .build();

            HttpResponse<Void> response =
                httpClient.send(request, HttpResponse.BodyHandlers.discarding());

            return response.statusCode() == 200;
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            throw new IOException("Failed to HEAD: " + uri, e);
        }
    }

    @Override
    public boolean create(URI uri, String location) throws IOException
    {
        Path destination = getDestination(uri, location);

        if (Files.isDirectory(destination))
            destination = destination.resolve(uri.getSchemeSpecificPart().substring(uri.getRawSchemeSpecificPart().lastIndexOf('/') + 1));

        if (isFilePresent(destination))
            return false;

        if (isProvidedByHome(location))
        {
            StartLog.debug("skipping download of %s : ${jetty.home} provides %s", uri, location);
            return false;
        }

        download(uri, destination);

        return true;
    }
}
