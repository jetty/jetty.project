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
 * GraalVM Native-Image {@link PathResourceFactory}.
 * 
 * @see <a href="https://github.com/oracle/graal/issues/5720">Graal issue 5720</a>
 * @see GraalIssue5720PathResource
 */
final class GraalIssue5720PathResourceFactory extends PathResourceFactory
{
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
