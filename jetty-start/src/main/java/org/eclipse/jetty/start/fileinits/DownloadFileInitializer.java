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
import java.io.InputStream;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

import org.eclipse.jetty.start.BaseHome;
import org.eclipse.jetty.start.FS;
import org.eclipse.jetty.start.FileInitializer;
import org.eclipse.jetty.start.StartArgs;
import org.eclipse.jetty.start.StartLog;

public abstract class DownloadFileInitializer extends FileInitializer
{
    private HttpClient httpClient;

    protected DownloadFileInitializer(BaseHome basehome, String... scheme)
    {
        super(basehome, scheme);
    }

    protected abstract boolean allowInsecureHttpDownloads();

    protected void download(URI uri, Path destination) throws IOException
    {
        if ("http".equalsIgnoreCase(uri.getScheme()) && !allowInsecureHttpDownloads())
            throw new IOException("Insecure HTTP download not allowed (use " + StartArgs.ARG_ALLOW_INSECURE_HTTP_DOWNLOADS + " to bypass): " + uri);

        if (Files.exists(destination))
        {
            if (Files.isRegularFile(destination))
            {
                StartLog.warn("skipping download of %s : file exists in destination %s", uri, destination);
            }
            else
            {
                StartLog.warn("skipping download of %s : path conflict at destination %s", uri, destination);
            }
            return;
        }

        if (FS.ensureDirectoryExists(destination.getParent()))
            StartLog.info("mkdir " + _basehome.toShortForm(destination.getParent()));

        StartLog.info("download %s to %s", uri, _basehome.toShortForm(destination));

        HttpClient httpClient = getHttpClient();

        try
        {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(uri)
                .GET()
                .build();

            HttpResponse<InputStream> response =
                httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());

            int status = response.statusCode();

            if (!allowInsecureHttpDownloads() && (status >= 300) && (status <= 399))
            {
                // redirection status, provide more details in error
                Optional<String> location = response.headers().firstValue("Location");
                if (location.isPresent())
                {
                    throw new IOException("URL GET Failure [status " + status + "] on " + uri +
                        " wanting to redirect to insecure HTTP location (use " +
                        StartArgs.ARG_ALLOW_INSECURE_HTTP_DOWNLOADS + " to bypass): " +
                        location.get());
                }
            }

            if (status != 200)
            {
                throw new IOException("URL GET Failure [status " + status + "] on " + uri);
            }

            try (InputStream in = response.body())
            {
                Files.copy(in, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        catch (InterruptedException e)
        {
            throw new IOException("Failed to GET: " + uri, e);
        }
    }

    private HttpClient getHttpClient()
    {
        if (httpClient == null)
        {
            httpClient = HttpClient.newBuilder()
                .followRedirects(allowInsecureHttpDownloads() ? HttpClient.Redirect.ALWAYS : HttpClient.Redirect.NORMAL)
                .proxy(ProxySelector.getDefault())
                .build();
        }
        return httpClient;
    }
}
