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
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

/**
 * GraalVM Native-Image {@link PathResourceFactory}.
 */
public class NativeImagePathResourceFactory extends PathResourceFactory
{
    static final boolean ENABLE_GRAALVM_RESOURCE_SCHEME = (System.getProperty("org.graalvm.nativeimage.kind") != null);

    public NativeImagePathResourceFactory()
    {
        if (ENABLE_GRAALVM_RESOURCE_SCHEME)
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
        uri = NativeImagePathResource.correctResourceURI(uri.normalize());
        Path path = Path.of(uri);

        if (!Files.exists(path))
            return null;

        return new NativeImagePathResource(path, uri, false);
    }
}
