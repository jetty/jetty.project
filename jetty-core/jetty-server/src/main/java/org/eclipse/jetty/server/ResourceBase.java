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

package org.eclipse.jetty.server;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.jetty.util.paths.PathCollection;

public class ResourceBase
{
    private final PathCollection _pathCollection;

    public ResourceBase(PathCollection pathCollection)
    {
        _pathCollection = pathCollection;
    }

    public Path resolve(String path, String... extraPaths)
    {
        // TODO call alias checker
        return _pathCollection.stream()
            .map(Path::toUri)
            .map(uri ->
            {
                String p = path;
                if (p.startsWith("/"))
                    p = p.substring(1);
                URI resolved = uri.resolve(p);

                for (String extraPath : extraPaths)
                {
                    if (extraPath.startsWith("/"))
                        extraPath = extraPath.substring(1);
                    resolved = resolved.resolve(extraPath);
                }

                return resolved;
            })
            .map(Path::of)
            .filter(Files::exists)
            .findFirst()
            .orElse(null);
    }
}
