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

import java.io.File;
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
    final List<Path> _files = new ArrayList<Path>();

    public FileDestroyable()
    {
    }

    public FileDestroyable(String file) throws IOException
    {
        _files.add(Resource.newResource(file).getPath());
    }

    public FileDestroyable(Path file)
    {
        _files.add(file);
    }

    public void addFile(String file) throws IOException
    {
        try (Resource r = Resource.newResource(file);)
        {
            _files.add(r.getPath());
        }
    }

    public void addFile(Path file)
    {
        _files.add(file);
    }

    public void addFiles(Collection<Path> files)
    {
        _files.addAll(files);
    }

    public void removeFile(String file) throws IOException
    {
        try (Resource r = Resource.newResource(file);)
        {
            _files.remove(r.getPath());
        }
    }

    public void removeFile(File file)
    {
        _files.remove(file);
    }

    @Override
    public void destroy()
    {
        for (Path file : _files)
        {
            if (Files.exists(file))
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Destroy {}", file);
                IO.delete(file);
            }
        }
    }
}
