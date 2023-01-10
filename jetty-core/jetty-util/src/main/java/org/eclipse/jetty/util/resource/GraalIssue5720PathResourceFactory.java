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
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

/**
 * GraalVM Native-Image {@link PathResourceFactory}.
 * 
 * @see <a href="https://github.com/oracle/graal/issues/5720">Graal issue 5720</a>
 */
final class GraalIssue5720PathResourceFactory extends PathResourceFactory
{
    static final boolean ENABLE_NATIVE_IMAGE_RESOURCE_SCHEME;

    static
    {
        URL url = GraalIssue5720PathResourceFactory.class.getResource("/org/eclipse/jetty/version/build.properties");
        ENABLE_NATIVE_IMAGE_RESOURCE_SCHEME = (url != null && "resource".equals(url.getProtocol()));
    }

    public GraalIssue5720PathResourceFactory()
    {
        if (ENABLE_NATIVE_IMAGE_RESOURCE_SCHEME)
        {
            initNativeImageResourceFileSystem();
        }
    }

    private void initNativeImageResourceFileSystem()
    {
        try
        {
            URI uri = new URI("resource:/");
            try
            {
                Path.of(uri);
            }
            catch (FileSystemNotFoundException e)
            {
                FileSystems.newFileSystem(uri, Collections.emptyMap());
            }
        }
        catch (IOException | URISyntaxException | RuntimeException ignore)
        {
            // ignore
        }
    }

    @Override
    public Resource newResource(URI uri)
    {
        uri = GraalIssue5720PathResource.correctResourceURI(uri.normalize());
        Path path = Path.of(uri);

        if (!Files.exists(path))
            return null;

        return new GraalIssue5720PathResource(path, uri, false);
    }
}
