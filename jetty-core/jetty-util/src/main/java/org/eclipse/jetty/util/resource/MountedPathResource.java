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

/**
 * Java NIO Path Resource with file system pooling. {@link FileSystem} implementations that must be closed
 * must use this class, for instance the one handling the `jar` scheme.
 */
public class MountedPathResource extends PathResource
{
    MountedPathResource(URI uri) throws IOException
    {
        super(uri, true);
    }

    @Override
    public boolean isContainedIn(Resource r)
    {
        return r.getURI().equals(FileSystemPool.containerUri(getURI()));
    }

    public Path getContainerPath()
    {
        URI uri = FileSystemPool.containerUri(getURI());
        return uri == null ? null : Path.of(uri);
    }
}
