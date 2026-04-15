//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
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
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.eclipse.jetty.util.URIUtil;

/**
 * A ResourceFactory for mounted FileSystems.
 * <p>
 * Example, for the "jar" scheme, this would use the JDK built-in ZipFileSystemProvider to load JAR files
 * as a {@link FileSystem} suitable for {@link PathResource}.
 * </p>
 */
public class MountedPathResourceFactory implements ResourceFactory
{
    private static final Map<String, String> ENV_MULTIRELEASE_RUNTIME;

    static
    {
        Map<String, String> env = new HashMap<>();
        // Key and Value documented at https://docs.oracle.com/en/java/javase/17/docs/api/jdk.zipfs/module-summary.html
        env.put("releaseVersion", "runtime");
        ENV_MULTIRELEASE_RUNTIME = env;
    }

    /**
     * In some FileSystem types, there is no FileSystemProvider that can
     * properly create that FileSystem.  In those cases, a FileSystem is created
     * using other FileSystem specific APIs and that FileSystem is just referenced
     * here and used.  This forced FileSystem is never closed by the Jetty implementation.
     */
    private final FileSystem forcedFileSystem;

    public MountedPathResourceFactory()
    {
        this(null);
    }

    /**
     * Pre-initialize a {@link MountedPathResourceFactory} with a known FileSystem that must always be used.
     *
     * @param fileSystem if not null, use the provided {@link FileSystem} when calls to {@link #newResource(URI)} is used.
     * if null, then the JDK {@link FileSystems#newFileSystem} API is used for all {@link #newResource(URI)} calls.
     */
    public MountedPathResourceFactory(FileSystem fileSystem)
    {
        this.forcedFileSystem = fileSystem;
    }

    @Override
    public Resource newResource(URI uri)
    {
        // Most FileSystemProviders only supports absolute URIs
        if (!uri.isAbsolute())
            throw new IllegalArgumentException("not an absolute uri: " + uri);

        // Unwrap any containers found.
        // Examples:
        //    "jar:file:/path/to/foo.jar!/deep/reference.txt" becomes "file:/path/to/foo.jar"
        //    "jimfs://testNavigateResource/" becomes ""jimfs://testNavigateResource/"
        URI containerURI = URIUtil.unwrapContainer(uri);
        Path containerPath = Path.of(containerURI.normalize());
        if (!Files.exists(containerPath))
            return null;

        FileSystem fs = forcedFileSystem != null ? forcedFileSystem : newFileSystem(containerPath);

        // Get a Path that is the deep reference specified in the URI.
        // Examples:
        //   "jar:file:/path/to/foo.jar!/deep/ref.txt"
        //   "jar:file:/path/to/bar.jar!/"
        //   "jimfs://a3cc0bda-1238-4847-864f-22fae7614146/path/inside/"
        Path ref = getDeepPathReference(fs, uri);

        // Check for existence of the URI deep reference, we don't want to create
        // Resource objects that don't have an associated file/dir.
        if (!Files.exists(ref))
        {
            if (forcedFileSystem == null)
            {
                try
                {
                    fs.close();
                }
                catch (IOException e)
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("Unable to close non-existent FileSystem: {}", uri, e);
                }
            }
            return null;
        }

        return new MountedPathResource(forcedFileSystem == null ? fs : null, ref, uri);
    }

    private Path getDeepPathReference(FileSystem fs, URI uri)
    {
        String ssp = uri.getSchemeSpecificPart();
        int idx = ssp.indexOf("!/");
        if (idx >= 0)
            return fs.getPath(ssp.substring(idx + 1));
        else
            return Path.of(uri);
    }

    private FileSystem newFileSystem(Path containerPath)
    {
        try
        {
            //noinspection resource (handled by MountedPathResource)
            return FileSystems.newFileSystem(containerPath, ENV_MULTIRELEASE_RUNTIME);
        }
        catch (IOException e)
        {
            throw new RuntimeException("Unable to create FileSystem for " + containerPath, e);
        }
    }
}
