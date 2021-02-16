//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.start.fileinits;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.jetty.start.BaseHome;
import org.eclipse.jetty.start.FS;
import org.eclipse.jetty.start.FileInitializer;
import org.eclipse.jetty.start.StartLog;
import org.eclipse.jetty.start.Utils;

/**
 * Attempt to download a <code>maven://</code> URI, by first attempting to find
 * the resource in the maven repository system (starting with local, then
 * central)
 * <p>
 * Valid URI Formats:
 * <dl>
 * <dt><code>maven://&lt;groupId&gt;/&lt;artifactId&gt;/&lt;version&gt;</code></dt>
 * <dd>minimum requirement (type defaults to <code>jar</code>, with no
 * classifier)</dd>
 * <dt><code>maven://&lt;groupId&gt;/&lt;artifactId&gt;/&lt;version&gt;/&lt;type&gt;</code></dt>
 * <dd>optional type requirement</dd>
 * <dt>
 * <code>maven://&lt;groupId&gt;/&lt;artifactId&gt;/&lt;version&gt;/&lt;type&gt;/&lt;classifier&gt;</code>
 * </dt>
 * <dd>optional type and classifier requirement</dd>
 * </dl>
 */
public class MavenLocalRepoFileInitializer extends FileInitializer
{
    public static class Coordinates
    {
        public String groupId;
        public String artifactId;
        public String version;
        public String type;
        public String classifier;
        private String mavenRepoUri = DEFAULT_REMOTE_REPO;

        public String toPath()
        {
            StringBuilder pathlike = new StringBuilder();
            pathlike.append(groupId.replace('.', '/'));
            pathlike.append('/').append(artifactId);
            pathlike.append('/').append(version);
            pathlike.append('/').append(artifactId);
            pathlike.append('-').append(version);
            if (classifier != null)
            {
                pathlike.append('-').append(classifier);
            }
            pathlike.append('.').append(type);
            return pathlike.toString();
        }

        public URI toCentralURI()
        {
            return URI.create(mavenRepoUri + toPath());
        }
    }

    private static final String DEFAULT_REMOTE_REPO = "https://repo1.maven.org/maven2/";
    private Path localRepositoryDir;
    private final boolean readonly;
    private String mavenRepoUri;

    public MavenLocalRepoFileInitializer(BaseHome baseHome)
    {
        this(baseHome, null, true);
    }

    public MavenLocalRepoFileInitializer(BaseHome baseHome, Path localRepoDir, boolean readonly)
    {
        super(baseHome, "maven");
        this.localRepositoryDir = localRepoDir;
        this.readonly = readonly;
    }

    public MavenLocalRepoFileInitializer(BaseHome baseHome, Path localRepoDir, boolean readonly, String mavenRepoUri)
    {
        super(baseHome, "maven");
        this.localRepositoryDir = localRepoDir;
        this.readonly = readonly;
        this.mavenRepoUri = mavenRepoUri;
    }

    @Override
    public boolean create(URI uri, String location) throws IOException
    {
        Coordinates coords = getCoordinates(uri);
        if (coords == null)
        {
            // Skip, not a maven:// URI
            return false;
        }

        Path destination = getDestination(uri, location);

        if (isFilePresent(destination))
            return false;

        // If using local repository
        if (this.localRepositoryDir != null)
        {
            // Grab copy from local repository (download if needed to local
            // repository)
            Path localRepoFile = getLocalRepoFile(coords);

            if (localRepoFile != null)
            {
                if (FS.ensureDirectoryExists(destination.getParent()))
                    StartLog.log("MKDIR", _basehome.toShortForm(destination.getParent()));
                StartLog.log("COPY ", "%s to %s", localRepoFile, _basehome.toShortForm(destination));
                Files.copy(localRepoFile, destination);
                return true;
            }
        }

        // normal non-local repo version
        download(coords.toCentralURI(), destination);
        return true;
    }

    private Path getLocalRepoFile(Coordinates coords) throws IOException
    {
        Path localFile = localRepositoryDir.resolve(coords.toPath());
        if (FS.canReadFile(localFile))
            return localFile;

        // Download, if needed
        if (!readonly)
        {
            download(coords.toCentralURI(), localFile);
            return localFile;
        }

        return null;
    }

    public String getRemoteUri()
    {
        if (this.mavenRepoUri != null)
        {
            return this.mavenRepoUri;
        }
        else
        {
            return System.getProperty("maven.repo.uri", DEFAULT_REMOTE_REPO);
        }
    }

    public Coordinates getCoordinates(URI uri)
    {
        if (!"maven".equalsIgnoreCase(uri.getScheme()))
        {
            return null;
        }

        String ssp = uri.getSchemeSpecificPart();

        if (ssp.startsWith("//"))
        {
            ssp = ssp.substring(2);
        }

        String[] parts = ssp.split("/");

        if (StartLog.isDebugEnabled())
        {
            StartLog.debug("ssp = %s", ssp);
            StartLog.debug("parts = %d", parts.length);
            for (int i = 0; i < parts.length; i++)
            {
                StartLog.debug("  part[%2d]: [%s]", i, parts[i]);
            }
        }

        if (parts.length < 3)
        {
            throw new RuntimeException("Not a valid maven:// uri - " + uri);
        }

        Coordinates coords = new Coordinates();
        coords.groupId = parts[0];
        coords.artifactId = parts[1];
        coords.version = parts[2];
        coords.type = "jar";
        coords.classifier = null;
        coords.mavenRepoUri = getRemoteUri();

        if (parts.length >= 4)
        {
            if (Utils.isNotBlank(parts[3]))
            {
                coords.type = parts[3];
            }

            if ((parts.length == 5) && (Utils.isNotBlank(parts[4])))
            {
                coords.classifier = parts[4];
            }
        }

        return coords;
    }

    /**
     * protected only for testing purpose
     *
     * @param uri the the uri to download
     * @param destination the destination File
     */
    @Override
    protected void download(URI uri, Path destination)
        throws IOException
    {
        super.download(uri, destination);
    }
}
