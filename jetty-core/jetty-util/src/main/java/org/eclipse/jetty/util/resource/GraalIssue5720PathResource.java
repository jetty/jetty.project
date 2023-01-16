//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.util.resource;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.FileSystemAlreadyExistsException;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * GraalVM Native-Image {@link Path} Resource.
 * 
 * @see <a href="https://github.com/oracle/graal/issues/5720">Graal issue 5720</a>
 */
final class GraalIssue5720PathResource extends PathResource
{
    private static final Logger LOG = LoggerFactory.getLogger(GraalIssue5720PathResource.class);
    private static final String URI_BAD_RESOURCE_PREFIX = "file:///resources!";

    GraalIssue5720PathResource(Path path, URI uri, boolean bypassAllowedSchemeCheck)
    {
        super(path, correctResourceURI(uri), (bypassAllowedSchemeCheck || isResourceScheme(uri)));
    }

    private static final boolean isResourceScheme(URI uri)
    {
        return "resource".equals(uri.getScheme());
    }

    /**
     * Checks if the given resource URL is affected by Graal issue 5720.
     * 
     * @param url
     *            The URL to check.
     * @return {@code true} if affected.
     */
    static boolean isAffectedURL(URL url)
    {
        if (url == null || !"resource".equals(url.getProtocol()))
        {
            return false;
        }

        URI uri;
        try
        {
            uri = url.toURI();
        }
        catch (URISyntaxException e)
        {
            return false;
        }

        try
        {
            try
            {
                uri = Path.of(uri).toUri();
            }
            catch (FileSystemNotFoundException e)
            {
                try
                {
                    FileSystems.newFileSystem(uri, Collections.emptyMap());
                }
                catch (FileSystemAlreadyExistsException e2)
                {
                    LOG.debug("Race condition upon calling FileSystems.newFileSystem for: {}", uri, e);
                }
                uri = Path.of(uri).toUri();
            }
        }
        catch (IOException | RuntimeException e)
        {
            LOG.warn("Could not check URL: {}", url, e);
        }

        return uri.getSchemeSpecificPart().startsWith(URI_BAD_RESOURCE_PREFIX);
    }

    /**
     * Corrects any bad {@code resource} based URIs, such as those starting with {@code resource:file:///resources!}.
     * 
     * @param uri
     *            The URI to correct.
     * @return the corrected URI, or the original URI.
     * @see <a href="https://github.com/oracle/graal/issues/5720">Graal issue 5720</a>
     */
    static URI correctResourceURI(URI uri)
    {
        if (uri == null || !isResourceScheme(uri))
            return uri;

        String ssp = uri.getSchemeSpecificPart();
        if (ssp.startsWith(URI_BAD_RESOURCE_PREFIX))
        {
            return URI.create("resource:" + ssp.substring(URI_BAD_RESOURCE_PREFIX.length()));
        }
        else
        {
            return uri;
        }
    }

    @Override
    public URI getRealURI()
    {
        Path realPath = getRealPath();
        return (realPath == null) ? null : correctResourceURI(realPath.toUri());
    }

    @Override
    protected URI toUri(Path path)
    {
        URI pathUri = correctResourceURI(path.toUri());
        String rawUri = pathUri.toASCIIString();

        if (Files.isDirectory(path) && !rawUri.endsWith("/"))
        {
            return URI.create(rawUri + '/');
        }
        return pathUri;
    }
}
