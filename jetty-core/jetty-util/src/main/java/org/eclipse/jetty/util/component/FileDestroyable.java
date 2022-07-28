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

package org.eclipse.jetty.util.component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.resource.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FileDestroyable implements Destroyable
{
    private static final Logger LOG = LoggerFactory.getLogger(FileDestroyable.class);
    final List<Path> _paths = new ArrayList<Path>();
    private final ContainerLifeCycle mountContainer = new ContainerLifeCycle();

    public FileDestroyable()
    {
        LifeCycle.start(mountContainer);
    }

    public FileDestroyable(String file) throws IOException
    {
        _paths.add(Resource.newResource(file).getPath());
    }

    public FileDestroyable(Path path)
    {
        _paths.add(path);
    }

    public void addFile(String file) throws IOException
    {
        _paths.add(Resource.newResource(file).getPath());
    }

    public void addPath(Path path)
    {
        _paths.add(path);
    }

    public void addPaths(Collection<Path> paths)
    {
        _paths.addAll(paths);
    }

    public void removeFile(String file) throws IOException
    {
        _paths.remove(Resource.newResource(file, mountContainer).getPath());
    }

    public void removeFile(Path path)
    {
        _paths.remove(path);
    }

    @Override
    public void destroy()
    {
        LifeCycle.stop(mountContainer);
        for (Path path : _paths)
        {
            if (Files.exists(path))
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Destroy {}", path);
                IO.delete(path);
            }
        }
    }
}
