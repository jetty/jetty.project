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

package org.eclipse.jetty.ee9.maven.plugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import org.eclipse.jetty.util.resource.Resource;

/**
 * Overlay
 * 
 * An Overlay represents overlay information derived from the
 * maven-war-plugin.
 */
public class Overlay
{
    private OverlayConfig _config;
    private Resource _resource;

    public Overlay(OverlayConfig config, Resource resource)
    {
        _config = config;
        _resource = resource;
    }

    public Overlay(OverlayConfig config)
    {
        _config = config;
    }

    public void setResource(Resource r)
    {
        _resource = r;
    }

    public Resource getResource()
    {
        return _resource;
    }

    public OverlayConfig getConfig()
    {
        return _config;
    }

    @Override
    public String toString()
    {
        StringBuilder strbuff = new StringBuilder();
        if (_resource != null)
            strbuff.append(_resource);
        if (_config != null)
        {
            strbuff.append(" [");
            strbuff.append(_config);
            strbuff.append("]");
        }
        return strbuff.toString();
    }
    
    /**
     * Unpack the overlay into the given directory. Only
     * unpack if the directory does not exist, or the overlay
     * has been modified since the dir was created.
     * 
     * @param dir the directory into which to unpack the overlay
     * @throws IOException 
     */
    public void unpackTo(File dir) throws IOException // TODO: change to Path
    {
        if (dir == null)
            throw new IllegalStateException("No overly unpack directory");

        Path pathDir = dir.toPath();
        // only unpack if the overlay is newer
        if (!Files.exists(pathDir))
        {
            getResource().copyTo(pathDir);
        }
        else
        {
            Instant dirLastModified = Files.getLastModifiedTime(pathDir).toInstant();
            if (getResource().lastModified().isAfter(dirLastModified))
                getResource().copyTo(pathDir);
        }
    }
}
