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

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * GraalVM Native-Image {@link Path} Resource.
 */
public class NativeImagePathResource extends PathResource
{
    private static final String URI_BAD_RESOURCE_PREFIX = "file:///resources!";

    NativeImagePathResource(Path path, URI uri, boolean bypassAllowedSchemeCheck)
    {
        super(path, correctResourceURI(uri), (bypassAllowedSchemeCheck || isResourceScheme(uri)));
    }

    private static final boolean isResourceScheme(URI uri)
    {
        return "resource".equals(uri.getScheme());
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
