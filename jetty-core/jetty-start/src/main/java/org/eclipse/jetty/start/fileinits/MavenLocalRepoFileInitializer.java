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

package org.eclipse.jetty.start.fileinits;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import javax.xml.parsers.ParserConfigurationException;

import org.eclipse.jetty.start.BaseHome;
import org.eclipse.jetty.start.FS;
import org.eclipse.jetty.start.FileInitializer;
import org.eclipse.jetty.start.StartLog;
import org.eclipse.jetty.start.Utils;
import org.xml.sax.SAXException;

/**
 * Attempt to download a <code>maven://</code> URI, by first attempting to find
 * the resource in the maven repository system (starting with local, then
 * central)
 * <p>
 * Valid URI Formats:
 * <dl>
 * <dt>{@code maven://<groupId>/<artifactId>/<version>}</dt>
 * <dd>minimum requirement (type defaults to <code>jar</code>, with no classifier)</dd>
 * <dt>{@code maven://<groupId>/<artifactId>/<version>/<type>}</dt>
 * <dd>optional type requirement</dd>
 * <dt><code>{@code maven://<groupId>/<artifactId>/<version>/<type>/<classifier>}</code></dt>
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
            return toActualPath(version);
        }

        private String toActualPath(String actualVersion)
        {
            StringBuilder pathlike = new StringBuilder();
            pathlike.append(groupId.replace('.', '/'));
            pathlike.append('/').append(artifactId);
            pathlike.append('/').append(version);
            pathlike.append('/').append(artifactId);
            pathlike.append('-').append(actualVersion);
            if (classifier != null)
            {
                pathlike.append('-').append(classifier);
            }
            pathlike.append('.').append(type);
            return pathlike.toString();
        }

        public String toMetadataPath()
        {
            StringBuilder pathLike = new StringBuilder();
            pathLike.append(groupId.replace('.', '/'));
            pathLike.append('/').append(artifactId);
            pathLike.append('/').append(version);
            pathLike.append("/maven-metadata.xml");

            return pathLike.toString();
        }

        public URI toCentralURI()
        {
            return URI.create(mavenRepoUri + toPath());
        }

        public URI toCentralURI(String actualVersion)
        {
            return URI.create(mavenRepoUri + toActualPath(actualVersion));
        }

        public URI toSnapshotMetadataXmlURI()
        {
            return URI.create(mavenRepoUri + toMetadataPath());
        }
    }

    private final Path localRepositoryDir;

    private static final String DEFAULT_REMOTE_REPO = "https://repo1.maven.org/maven2/";

    private final boolean readonly;
    private String mavenRepoUri;

    public MavenLocalRepoFileInitializer(BaseHome baseHome)
    {
        this(baseHome, null, true);
    }

    public MavenLocalRepoFileInitializer(BaseHome baseHome, Path localRepoDir, boolean readonly)
    {
        this(baseHome, localRepoDir, readonly, null);
    }

    public MavenLocalRepoFileInitializer(BaseHome baseHome, Path localRepoDir, boolean readonly, String mavenRepoUri)
    {
        super(baseHome, "maven");
        this.localRepositoryDir = localRepoDir != null ? localRepoDir : newTempRepo();
        this.readonly = readonly;
        this.mavenRepoUri = mavenRepoUri;
    }

    private static Path newTempRepo()
    {
        Path javaTempDir = Paths.get(System.getProperty("java.io.tmpdir"));
        // Simple return here, don't create the directory, unless it's being used.
        return javaTempDir.resolve("jetty-start-downloads");
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

        URI destURI = URI.create(location);
        if (destURI.isAbsolute() && destURI.getScheme().equals("extract"))
        {
            // Extract Flow

            // Download to local repo.
            Path localFile = localRepositoryDir.resolve(coords.toPath());
            if (!FS.canReadFile(localFile))
            {
                if (FS.ensureDirectoryExists(localFile.getParent()))
                    StartLog.info("mkdir " + _basehome.toShortForm(localFile.getParent()));
                download(coords, localFile);
                if (!FS.canReadFile(localFile))
                {
                    throw new IOException("Unable to establish temp copy of file to extract: " + localFile);
                }
            }

            // Destination Directory
            Path destination;
            String extractLocation = destURI.getSchemeSpecificPart();
            if (extractLocation.equals("/"))
            {
                destination = _basehome.getBasePath();
            }
            else
            {
                extractLocation = extractLocation.replaceFirst("^[/\\\\]*", "");
                if (!extractLocation.endsWith("/"))
                    throw new IOException("Extract mode can only unpack to a directory, end your URL with a slash: " + location);
                destination = _basehome.getBasePath().resolve(extractLocation);

                if (Files.exists(destination) && !Files.isDirectory(destination))
                    throw new IOException("Destination already exists, and is not a directory: " + destination);

                if (!destination.startsWith(_basehome.getBasePath()))
                    throw new IOException("For security reasons, Jetty start is unable to extract outside of the ${jetty.base} - " + location);
            }

            FS.extract(localFile, destination);
        }
        else
        {
            // Copy Flow
            Path destination = getDestination(uri, location);
            if (isFilePresent(destination))
                return false;

            // Grab copy from local repository (download if needed to local repository)
            Path localRepoFile = getLocalRepoFile(coords);

            if (localRepoFile != null)
            {
                if (FS.ensureDirectoryExists(destination.getParent()))
                    StartLog.info("mkdir " + _basehome.toShortForm(destination.getParent()));
                StartLog.info("copy %s to %s", localRepoFile, _basehome.toShortForm(destination));
                Files.copy(localRepoFile, destination);
                return true;
            }

            // normal non-local repo version
            download(coords, destination);
        }

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
            download(coords, localFile);
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

    protected void download(Coordinates coords, Path destination)
        throws IOException
    {
        if (coords.version.endsWith("-SNAPSHOT"))
        {
            Path localRepoMetadataPath = localRepositoryDir.resolve(coords.toMetadataPath());
            if (isMetadataStale(localRepoMetadataPath))
            {
                // Grab a fresh copy of the metadata xml
                URI mavenMetadataURI = coords.toSnapshotMetadataXmlURI();
                super.download(mavenMetadataURI, localRepoMetadataPath);
            }

            if (Files.exists(localRepoMetadataPath))
            {
                // parse metadata to get actual SNAPSHOT version
                String actualVersion = getMetadataVersion(localRepoMetadataPath, coords);
                super.download(coords.toCentralURI(actualVersion), destination);
            }
        }
        else
        {
            super.download(coords.toCentralURI(), destination);
        }
    }

    private boolean isMetadataStale(Path localRepoMetadataPath)
    {
        if (!Files.exists(localRepoMetadataPath))
        {
            // doesn't exist, it's stale.
            return true;
        }

        try
        {
            MavenMetadata mavenMetadata = new MavenMetadata(localRepoMetadataPath);
            return MavenMetadata.isExpiredTimestamp(mavenMetadata.getLastUpdated());
        }
        catch (IOException | ParserConfigurationException | SAXException e)
        {
            return true;
        }
    }

    private String getMetadataVersion(Path localRepoMetadataPath, Coordinates coords) throws IOException
    {
        try
        {
            MavenMetadata mavenMetadata = new MavenMetadata(localRepoMetadataPath);
            MavenMetadata.Snapshot snapshot = mavenMetadata.getSnapshot(coords.classifier, coords.type);
            return snapshot.value;
        }
        catch (IOException | ParserConfigurationException | SAXException e)
        {
            throw new IOException("Unable to parse " + localRepoMetadataPath, e);
        }
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
