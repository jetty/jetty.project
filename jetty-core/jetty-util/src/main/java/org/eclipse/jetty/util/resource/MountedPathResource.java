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
import java.nio.file.FileSystem;
import java.nio.file.Path;

import org.eclipse.jetty.util.URIUtil;

/**
 * Java NIO Path Resource with file system pooling. {@link FileSystem} implementations that must be closed
 * must use this class, for instance the one handling the `jar` scheme.
 */
public class MountedPathResource extends PathResource
{
    private final URI containerUri;

    MountedPathResource(URI uri) throws IOException
    {
        super(uri, true);
        containerUri = URIUtil.unwrapContainer(getURI());
    }

    MountedPathResource(Path path, URI uri)
    {
        super(path, uri, true);
        containerUri = URIUtil.unwrapContainer(getURI());
    }

    @Override
    protected Resource newResource(Path path, URI uri)
    {
        return new MountedPathResource(path, uri);
    }

    @Override
    public boolean isContainedIn(Resource r)
    {
        return URIUtil.unwrapContainer(r.getURI()).equals(containerUri);
    }

    public Path getContainerPath()
    {
        return containerUri == null ? null : Path.of(containerUri);
    }

    @Override
    public String getName()
    {
        Path abs = getPath();
        // If a "jar:file:" based path, we should normalize here, as the toAbsolutePath() does not resolve "/../" style segments in all cases
        if ("jar".equalsIgnoreCase(abs.toUri().getScheme()))
            abs = abs.normalize();
        // Get the absolute path
        abs = abs.toAbsolutePath();
        return abs.toString();
    }
}
