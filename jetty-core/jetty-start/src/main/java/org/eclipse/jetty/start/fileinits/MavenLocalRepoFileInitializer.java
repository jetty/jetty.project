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

package org.eclipse.jetty.start.fileinits;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import javax.xml.parsers.ParserConfigurationException;

import org.eclipse.jetty.start.BaseHome;
import org.eclipse.jetty.start.FS;
import org.eclipse.jetty.start.StartArgs;
import org.eclipse.jetty.start.StartLog;
import org.eclipse.jetty.start.Utils;
import org.eclipse.jetty.util.ExceptionUtil;
import org.xml.sax.SAXException;

import static java.nio.charset.StandardCharsets.UTF_8;

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
 * <dt>{@code maven://<groupId>/<artifactId>/<version>/<type>/<classifier>}</dt>
 * <dd>optional type and classifier requirement</dd>
 * </dl>
 */
public class MavenLocalRepoFileInitializer extends DownloadFileInitializer
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

        @Override
        public String toString()
        {
            StringBuilder str = new StringBuilder();
            str.append(groupId);
            str.append(':').append(artifactId);
            str.append(':').append(version);
            str.append(':').append(type);
            if (classifier != null)
                str.append(':').append(classifier);
            return str.toString();
        }
    }

    private static final String DEFAULT_REMOTE_REPO = "https://repo1.maven.org/maven2/";

    private final Path localRepositoryDir;

    private final boolean readonly;
    private final String mavenRepoUri;
    private boolean offline;

    /**
     * @deprecated use {@link MavenLocalRepoFileInitializer(BaseHome, StartArgs)} instead
     */
    @Deprecated(forRemoval = true, since = "12.1.14")
    public MavenLocalRepoFileInitializer(BaseHome baseHome)
    {
        this(baseHome, null, true);
    }

    /**
     * @deprecated use {@link MavenLocalRepoFileInitializer(BaseHome, StartArgs, Path)} instead
     */
    @Deprecated(forRemoval = true, since = "12.1.14")
    public MavenLocalRepoFileInitializer(BaseHome baseHome, Path localRepoDir, boolean readonly)
    {
        this(baseHome, localRepoDir, readonly, null);
    }

    /**
     * @deprecated use {@link MavenLocalRepoFileInitializer(BaseHome, StartArgs, Path, String)} instead
     */
    @Deprecated(forRemoval = true, since = "12.1.14")
    public MavenLocalRepoFileInitializer(BaseHome baseHome, Path localRepoDir, boolean readonly, String mavenRepoUri)
    {
        super(baseHome, "maven");
        this.localRepositoryDir = localRepoDir != null ? localRepoDir : newTempRepo();
        this.readonly = readonly;
        this.mavenRepoUri = Utils.isNotBlank(mavenRepoUri) ? mavenRepoUri : DEFAULT_REMOTE_REPO;
    }

    public MavenLocalRepoFileInitializer(BaseHome baseHome, StartArgs startArgs)
    {
        this(baseHome, startArgs, null);
    }

    public MavenLocalRepoFileInitializer(BaseHome baseHome, StartArgs startArgs, Path localRepoDir)
    {
        this(baseHome, startArgs, localRepoDir, null);
    }

    public MavenLocalRepoFileInitializer(BaseHome baseHome, StartArgs startArgs, Path localRepoDir, String mavenRepoUri)
    {
        super(baseHome, "maven");
        this.localRepositoryDir = localRepoDir != null ? localRepoDir : newTempRepo();
        // An explicitly configured local repo is writable by default (it gets
        // populated); an auto-discovered repo (e.g. the user's ~/.m2) is read-only
        // by default, so downloads go into the ${jetty.base} instead. The
        // maven.local.repo.readonly property/env var overrides this default.
        boolean readonlyByDefault = startArgs.getMavenLocalRepoDir() == null;
        this.readonly = startArgs.readonlyMavenLocalRepo(readonlyByDefault);
        this.offline = startArgs.useMavenOffline();
        this.mavenRepoUri = Utils.isNotBlank(mavenRepoUri) ? mavenRepoUri : startArgs.getMavenBaseUri();
    }

    @Override
    protected boolean allowInsecureHttpDownloads()
    {
        // Always allow insecure http downloads in this file initializer.

        // The user is either using the DEFAULT_REMOTE_REPO, or has redeclared it to a new URI.
        // If the `maven.repo.uri` property has been changed from default, this indicates a change
        // to a different maven uri, overwhelmingly pointing to a maven repository manager
        // like artifactory or nexus.   This is viewed as an intentional decision by the
        // user and as such we should not put additional hurdles in their way.
        return true;
    }

    private static Path newTempRepo()
    {
        Path javaTempDir = Paths.get(System.getProperty("java.io.tmpdir"));
        // Simple return here, don't create the directory, unless it's being used.
        return javaTempDir.resolve("jetty-start-downloads");
    }

    @Override
    public boolean exists(URI uri) throws IOException
    {
        Coordinates coords = getCoordinates(uri);
        if (coords == null)
        {
            // Skip, not a maven:// URI
            return false;
        }

        if (coords.version.endsWith("-SNAPSHOT"))
            return true; // assume valid on SNAPSHOT versions

        // Check local repo first
        Path localFile = localRepositoryDir.resolve(coords.toPath());
        if (Files.isRegularFile(localFile))
            return true;

        if (offline)
        {
            StartLog.warn("Maven is offline, but Local Maven Repo does not contain: %s%n", coords);
            return false;
        }

        // Don't write into the local repo (e.g. the user's ~/.m2); verify remotely instead.
        return existsOnCentralRepo(coords);
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
                if (offline)
                {
                    StartLog.warn("Maven is offline, but Local Maven Repo does not contain: %s%n", coords);
                    throw new IllegalStateException("Maven is offline, but Local Maven Repo does not contain: %s%n".formatted(coords));
                }
                else
                {
                    if (FS.ensureDirectoryExists(localFile.getParent()))
                        StartLog.info("mkdir %s", _basehome.toShortForm(localFile.getParent()));
                    download(coords, localFile);
                    if (!FS.canReadFile(localFile))
                    {
                        throw new IOException("Unable to establish temp copy of file to extract: " + localFile);
                    }
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

            if (isProvidedByHome(location))
            {
                StartLog.debug("skipping download of %s : ${jetty.home} provides %s", uri, location);
                return false;
            }

            // Grab copy from local repository (download if needed to local repository)
            Path localRepoFile = getLocalRepoFile(coords);

            if (localRepoFile != null)
            {
                if (FS.ensureDirectoryExists(destination.getParent()))
                    StartLog.info("mkdir %s", _basehome.toShortForm(destination.getParent()));
                StartLog.info("copy %s to %s", localRepoFile, _basehome.toShortForm(destination));
                Files.copy(localRepoFile, destination);
                return true;
            }

            if (offline)
            {
                StartLog.warn("Maven is offline, but Local Maven Repo does not contain: %s%n", coords);
                throw new IllegalStateException("Maven is offline, but Local Maven Repo does not contain: %s%n".formatted(coords));
            }
            else
            {
                // normal non-local repo version
                download(coords, destination);
            }
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
            if (offline)
            {
                StartLog.warn("Maven is offline, but Local Maven Repo does not contain: %s%n", coords);
                throw new IllegalStateException("Maven is offline, but Local Maven Repo does not contain: %s%n".formatted(coords));
            }
            download(coords, localFile);
            return localFile;
        }

        return null;
    }

    public String getRemoteUri()
    {
        return this.mavenRepoUri;
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
     * Remotely validate that the artifact exists on the central repository.
     *
     * @param coords the coordinates to check.
     * @return true if the artifact exists remotely (or is a SNAPSHOT, which is not remotely validated)
     * @throws IOException if unable to check for I/O reasons
     */
    protected boolean existsOnCentralRepo(Coordinates coords)
        throws IOException
    {
        // Verify remotely with a HEAD request (shared with DownloadFileInitializer), so the
        // local repository is never written to.
        return existsViaHead(coords.toCentralURI());
    }

    protected void download(Coordinates coords, Path destination)
        throws IOException
    {
        if (offline)
        {
            StartLog.warn("Maven configuration is set to offline, unable to download: %s", coords);
            return;
        }

        // Track what this call writes so a failure can restore the local repository to the
        // state we found it in, rather than deleting valid cached content: the artifact never
        // pre-exists here (callers only download when it is absent), but the SNAPSHOT metadata
        // may already be present - and must not be deleted when this call did not touch it, or
        // when overwriting it fails part way through.
        Path metadataFile = null;
        String metadataOriginal = null;  // existing metadata contents, held in memory before overwrite (UTF-8); null if none
        boolean metadataWritten = false; // this call (re)downloaded the metadata
        try
        {
            if (coords.version.endsWith("-SNAPSHOT"))
            {
                metadataFile = localRepositoryDir.resolve(coords.toMetadataPath());
                if (isMetadataStale(metadataFile))
                {
                    // Hold any existing metadata in memory before overwriting it, so it can be
                    // restored if a later step fails. Metadata files are small (well under 100k).
                    if (Files.exists(metadataFile))
                        metadataOriginal = Files.readString(metadataFile, UTF_8);
                    metadataWritten = true;

                    // Grab a fresh copy of the metadata xml
                    URI mavenMetadataURI = coords.toSnapshotMetadataXmlURI();
                    download(mavenMetadataURI, metadataFile);
                }

                if (Files.exists(metadataFile))
                {
                    // parse metadata to get actual SNAPSHOT version
                    String actualVersion = getMetadataVersion(metadataFile, coords);
                    download(coords.toCentralURI(actualVersion), destination);
                }
            }
            else
            {
                download(coords.toCentralURI(), destination);
            }
        }
        catch (Throwable t)
        {
            // Restore the prior on-disk state without masking the original failure.
            // Remove the partial artifact we created.
            deleteFileWhileSuppressingErrors(destination, t);
            // Only touch the metadata if this call wrote it: rewrite the contents we held in
            // memory, or remove the file we created where none existed. Untouched (fresh)
            // metadata is left exactly as we found it.
            if (metadataWritten)
            {
                if (metadataOriginal != null)
                    writeFileWhileSuppressingErrors(metadataFile, metadataOriginal, t);
                else
                    deleteFileWhileSuppressingErrors(metadataFile, t);
            }
            throw (t instanceof IOException io) ? io : new IOException("Failed to download " + coords, t);
        }
    }

    private static void deleteFileWhileSuppressingErrors(Path file, Throwable primary)
    {
        try
        {
            if (file != null)
                Files.deleteIfExists(file);
        }
        catch (IOException e)
        {
            ExceptionUtil.addSuppressedIfNotAssociated(primary, e);
        }
    }

    private static void writeFileWhileSuppressingErrors(Path file, String originalContents, Throwable primary)
    {
        try
        {
            Files.writeString(file, originalContents, UTF_8);
        }
        catch (IOException e)
        {
            ExceptionUtil.addSuppressedIfNotAssociated(primary, e);
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
     * @param uri the uri to download
     * @param destination the destination File
     */
    @Override
    protected void download(URI uri, Path destination)
        throws IOException
    {
        super.download(uri, destination);
    }

    public MavenLocalRepoFileInitializer offline(boolean offline)
    {
        this.offline = offline;
        return this;
    }
}
