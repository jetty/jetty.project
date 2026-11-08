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
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.start.BaseHome;
import org.eclipse.jetty.start.StartArgs;
import org.eclipse.jetty.start.config.ConfigSources;
import org.eclipse.jetty.start.config.JettyBaseConfigSource;
import org.eclipse.jetty.start.config.JettyHomeConfigSource;
import org.eclipse.jetty.start.fileinits.MavenLocalRepoFileInitializer.Coordinates;
import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.eclipse.jetty.toolchain.test.PathMatchers.isRegularFile;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

@ExtendWith(WorkDirExtension.class)
public class MavenLocalRepoFileInitializerTest
{
    public WorkDir workDir;
    public Path testdir;

    private BaseHome baseHome;

    @BeforeEach
    public void setupBaseHome() throws IOException
    {
        testdir = workDir.getEmptyPathDir();
        Path homeDir = testdir.resolve("home");
        Path baseDir = testdir.resolve("base");

        FS.ensureDirExists(homeDir);
        FS.ensureDirExists(baseDir);

        ConfigSources config = new ConfigSources();
        config.add(new JettyHomeConfigSource(homeDir));
        config.add(new JettyBaseConfigSource(baseDir));

        this.baseHome = new BaseHome(config);
    }

    @Test
    public void testGetCoordinateNotMaven()
    {
        MavenLocalRepoFileInitializer repo = new MavenLocalRepoFileInitializer(baseHome, new StartArgs(baseHome));
        String ref = "https://jetty.org/";
        Coordinates coords = repo.getCoordinates(URI.create(ref));
        assertThat("Coords", coords, nullValue());
    }

    @Test
    public void testGetCoordinateInvalidMaven()
    {
        MavenLocalRepoFileInitializer repo = new MavenLocalRepoFileInitializer(baseHome, new StartArgs(baseHome));
        String ref = "maven://eclipse.dev/jetty";
        RuntimeException x = assertThrows(RuntimeException.class, () -> repo.getCoordinates(URI.create(ref)));
        assertThat(x.getMessage(), containsString("Not a valid maven:// uri"));
    }

    @Test
    public void testGetCoordinateNormal()
    {
        MavenLocalRepoFileInitializer repo = new MavenLocalRepoFileInitializer(baseHome, new StartArgs(baseHome));
        String ref = "maven://org.eclipse.jetty/jetty-start/12.0.x";
        Coordinates coords = repo.getCoordinates(URI.create(ref));
        assertThat("Coordinates", coords, notNullValue());

        assertThat("coords.groupId", coords.groupId, is("org.eclipse.jetty"));
        assertThat("coords.artifactId", coords.artifactId, is("jetty-start"));
        assertThat("coords.version", coords.version, is("12.0.x"));
        assertThat("coords.type", coords.type, is("jar"));
        assertThat("coords.classifier", coords.classifier, nullValue());

        assertThat("coords.toCentralURI", coords.toCentralURI().toASCIIString(),
            is(repo.getRemoteUri() + "org/eclipse/jetty/jetty-start/12.0.x/jetty-start-12.0.x.jar"));
    }

    @Test
    public void testGetCoordinateZip()
    {
        MavenLocalRepoFileInitializer repo = new MavenLocalRepoFileInitializer(baseHome, new StartArgs(baseHome));
        String ref = "maven://org.eclipse.jetty/jetty-home/12.0.21/zip";
        Coordinates coords = repo.getCoordinates(URI.create(ref));
        assertThat("Coordinates", coords, notNullValue());

        assertThat("coords.groupId", coords.groupId, is("org.eclipse.jetty"));
        assertThat("coords.artifactId", coords.artifactId, is("jetty-home"));
        assertThat("coords.version", coords.version, is("12.0.21"));
        assertThat("coords.type", coords.type, is("zip"));
        assertThat("coords.classifier", coords.classifier, nullValue());

        assertThat("coords.toCentralURI", coords.toCentralURI().toASCIIString(),
                   is(repo.getRemoteUri() + "org/eclipse/jetty/jetty-home/12.0.21/jetty-home-12.0.21.zip"));
    }

    @Test
    public void testGetCoordinateTestJar()
    {
        MavenLocalRepoFileInitializer repo = new MavenLocalRepoFileInitializer(baseHome, new StartArgs(baseHome));
        String ref = "maven://org.eclipse.jetty/jetty-http/12.0.x/jar/tests";
        Coordinates coords = repo.getCoordinates(URI.create(ref));
        assertThat("Coordinates", coords, notNullValue());

        assertThat("coords.groupId", coords.groupId, is("org.eclipse.jetty"));
        assertThat("coords.artifactId", coords.artifactId, is("jetty-http"));
        assertThat("coords.version", coords.version, is("12.0.x"));
        assertThat("coords.type", coords.type, is("jar"));
        assertThat("coords.classifier", coords.classifier, is("tests"));

        assertThat("coords.toCentralURI", coords.toCentralURI().toASCIIString(),
            is(repo.getRemoteUri() + "org/eclipse/jetty/jetty-http/12.0.x/jetty-http-12.0.x-tests.jar"));
    }

    @Test
    public void testGetCoordinateTestUnspecifiedType()
    {
        MavenLocalRepoFileInitializer repo = new MavenLocalRepoFileInitializer(baseHome, new StartArgs(baseHome));
        String ref = "maven://org.eclipse.jetty/jetty-http/12.0.x//tests";
        Coordinates coords = repo.getCoordinates(URI.create(ref));
        assertThat("Coordinates", coords, notNullValue());

        assertThat("coords.groupId", coords.groupId, is("org.eclipse.jetty"));
        assertThat("coords.artifactId", coords.artifactId, is("jetty-http"));
        assertThat("coords.version", coords.version, is("12.0.x"));
        assertThat("coords.type", coords.type, is("jar"));
        assertThat("coords.classifier", coords.classifier, is("tests"));

        assertThat("coords.toCentralURI", coords.toCentralURI().toASCIIString(),
            is(repo.getRemoteUri() + "org/eclipse/jetty/jetty-http/12.0.x/jetty-http-12.0.x-tests.jar"));
    }

    @Test
    public void testGetCoordinateTestMavenBaseUri()
    {
        MavenLocalRepoFileInitializer repo =
            new MavenLocalRepoFileInitializer(baseHome, new StartArgs(baseHome), null,
                "https://repo1.maven.org/maven2/");
        String ref = "maven://org.eclipse.jetty/jetty-http/12.0.x/jar/tests";
        Coordinates coords = repo.getCoordinates(URI.create(ref));
        assertThat("Coordinates", coords, notNullValue());

        assertThat("coords.groupId", coords.groupId, is("org.eclipse.jetty"));
        assertThat("coords.artifactId", coords.artifactId, is("jetty-http"));
        assertThat("coords.version", coords.version, is("12.0.x"));
        assertThat("coords.type", coords.type, is("jar"));
        assertThat("coords.classifier", coords.classifier, is("tests"));

        assertThat("coords.toCentralURI", coords.toCentralURI().toASCIIString(),
            is(repo.getRemoteUri() + "org/eclipse/jetty/jetty-http/12.0.x/jetty-http-12.0.x-tests.jar"));
    }

    @Test
    @Tag("external")
    public void testDownloadUnspecifiedRepo()
        throws Exception
    {
        MavenLocalRepoFileInitializer repo =
            new MavenLocalRepoFileInitializer(baseHome, new StartArgs(baseHome), null);
        String ref = "maven://org.eclipse.jetty/jetty-session/12.0.40/jar/tests";
        Coordinates coords = repo.getCoordinates(URI.create(ref));
        assertThat("Coordinates", coords, notNullValue());

        assertThat("coords.groupId", coords.groupId, is("org.eclipse.jetty"));
        assertThat("coords.artifactId", coords.artifactId, is("jetty-session"));
        assertThat("coords.version", coords.version, is("12.0.40"));
        assertThat("coords.type", coords.type, is("jar"));
        assertThat("coords.classifier", coords.classifier, is("tests"));

        assertThat("coords.toCentralURI", coords.toCentralURI().toASCIIString(),
                   is(repo.getRemoteUri() + "org/eclipse/jetty/jetty-session/12.0.40/jetty-session-12.0.40-tests.jar"));

        Path destination = testdir.resolve("jetty-session-12.0.40-tests.jar");
        Files.deleteIfExists(destination);
        repo.download(coords.toCentralURI(), destination);
        assertThat(Files.exists(destination), is(true));
        assertThat(Files.size(destination), is(90325L));
    }

    @Test
    public void testDownloadButOffline()
        throws Exception
    {
        Path snapshotLocalRepoDir = testdir.resolve("snapshot-repo");
        FS.ensureEmpty(snapshotLocalRepoDir);

        // Create a jar file in this "local repo" that doesn't exist on a real maven repo (for testing reasons, don't want false positives)
        Path jarFile = snapshotLocalRepoDir.resolve("org/eclipse/jetty/eeX/demos/jetty-eeX-demo-simple-webapp/12.99.9/jetty-eeX-demo-simple-webapp-12.99.9-config.jar");
        FS.ensureDirExists(jarFile.getParent());

        Map<String, String> env = new HashMap<>();
        env.put("create", "true");
        URI uri = URI.create("jar:" + jarFile.toUri().toASCIIString());
        try (FileSystem zipfs = FileSystems.newFileSystem(uri, env))
        {
            Path root = zipfs.getPath("/");
            Path dir = root.resolve("modules");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("eeX-demo.mod"), """
                [description]
                Test Demo Module (doesn't do anything)
                """, UTF_8);
        }

        MavenLocalRepoFileInitializer repo =
            new MavenLocalRepoFileInitializer(baseHome, new StartArgs(baseHome), snapshotLocalRepoDir,
                "https://central.sonatype.com/repository/maven-snapshots/").offline(true);
        String ref = "maven://org.eclipse.jetty.eeX.demos/jetty-eeX-demo-simple-webapp/12.99.9/jar/config";
        Path baseDir = baseHome.getBasePath();
        repo.create(URI.create(ref), "extract:/");

        assertThat(baseDir.resolve("modules/eeX-demo.mod"), isRegularFile());
    }

    @Test
    public void testSkipDownloadWhenHomeProvidesFile()
        throws Exception
    {
        // The distribution already ships this jar.
        String location = "lib/ee10/jetty-ee10-webapp-12.99.9.jar";
        Path homeFile = baseHome.getHomePath().resolve(location);
        FS.ensureDirExists(homeFile.getParent());
        Files.writeString(homeFile, "not really a jar", UTF_8);

        // Offline with an empty repo, so any download attempt would fail.
        Path emptyLocalRepoDir = testdir.resolve("empty-repo");
        FS.ensureEmpty(emptyLocalRepoDir);

        MavenLocalRepoFileInitializer repo =
            new MavenLocalRepoFileInitializer(baseHome, new StartArgs(baseHome), emptyLocalRepoDir).offline(true);
        String ref = "maven://org.eclipse.jetty.ee10/jetty-ee10-webapp/12.99.9/jar";

        assertThat("nothing to do", repo.create(URI.create(ref), location), is(false));
        assertThat("not copied into base", Files.exists(baseHome.getBasePath().resolve(location)), is(false));
    }

    @Test
    public void testExistsChecksRemotelyWithoutPopulatingLocalRepo()
        throws Exception
    {
        // A local repo that does not contain the artifact.
        Path localRepoDir = testdir.resolve("local-repo");
        FS.ensureEmpty(localRepoDir);

        // Capture the remote existence check instead of hitting the network.
        AtomicReference<Coordinates> remotelyChecked = new AtomicReference<>();
        MavenLocalRepoFileInitializer repo =
            new MavenLocalRepoFileInitializer(baseHome, new StartArgs(baseHome), localRepoDir, "https://repo1.maven.org/maven2/")
            {
                @Override
                protected boolean existsOnCentralRepo(Coordinates coords)
                {
                    remotelyChecked.set(coords);
                    return true;
                }
            };

        String ref = "maven://org.eclipse.jetty/jetty-util/12.0.21/jar";
        assertThat("exists (verified remotely)", repo.exists(URI.create(ref)), is(true));

        // The existence check must have been performed remotely ...
        assertThat("remote existence check performed", remotelyChecked.get(), notNullValue());
        assertThat("checked coordinates", remotelyChecked.get().artifactId, is("jetty-util"));

        // ... and exists() must never write into the local repo.
        Path localFile = localRepoDir.resolve("org/eclipse/jetty/jetty-util/12.0.21/jetty-util-12.0.21.jar");
        assertThat("local repo not populated", Files.exists(localFile), is(false));
    }

    @Test
    public void testCreateReadonlyDownloadsToBaseNotLocalRepo()
        throws Exception
    {
        // A read-only local repo (e.g. the user's ~/.m2) that does not contain the artifact.
        Path localRepoDir = testdir.resolve("readonly-repo");
        FS.ensureEmpty(localRepoDir);

        StartArgs startArgs = new StartArgs(baseHome);
        startArgs.getJettyEnvironment().getProperties()
            .setProperty("maven.local.repo.readonly", "true", "test");

        // Capture (and simulate) the download instead of hitting the network.
        AtomicReference<Path> downloadedTo = new AtomicReference<>();
        MavenLocalRepoFileInitializer repo =
            new MavenLocalRepoFileInitializer(baseHome, startArgs, localRepoDir, "https://repo1.maven.org/maven2/")
            {
                @Override
                protected void download(Coordinates coords, Path destination) throws IOException
                {
                    downloadedTo.set(destination);
                    FS.ensureDirExists(destination.getParent());
                    Files.writeString(destination, "downloaded", UTF_8);
                }
            };

        String location = "lib/jetty-util-12.0.21.jar";
        String ref = "maven://org.eclipse.jetty/jetty-util/12.0.21/jar";
        assertThat("created", repo.create(URI.create(ref), location), is(true));

        // Because the local repo is read-only and does not hold the artifact, create() must
        // download straight into the ${jetty.base}, never writing into the local repo.
        Path baseFile = baseHome.getBasePath().resolve(location);
        assertThat("download target was the base destination", downloadedTo.get(), is(baseFile));
        assertThat("downloaded into base", baseFile, isRegularFile());

        Path localRepoFile = localRepoDir.resolve("org/eclipse/jetty/jetty-util/12.0.21/jetty-util-12.0.21.jar");
        assertThat("read-only local repo not populated", Files.exists(localRepoFile), is(false));
    }

    @Test
    public void testDownloadPreservesFreshMetadataWhenArtifactDownloadFails()
        throws Exception
    {
        Path localRepoDir = testdir.resolve("snapshot-repo");
        FS.ensureEmpty(localRepoDir);

        MavenLocalRepoFileInitializer repo =
            new MavenLocalRepoFileInitializer(baseHome, new StartArgs(baseHome), localRepoDir, "https://repo1.maven.org/maven2/")
            {
                @Override
                protected void download(URI uri, Path destination) throws IOException
                {
                    if (destination.getFileName().toString().equals("maven-metadata.xml"))
                        throw new AssertionError("fresh metadata must not be re-downloaded");
                    // The artifact download fails.
                    throw new IOException("simulated artifact download failure");
                }
            };

        Coordinates coords = repo.getCoordinates(URI.create("maven://org.eclipse.jetty/jetty-foo/12.99.9-SNAPSHOT/jar"));
        Path metadataFile = localRepoDir.resolve(coords.toMetadataPath());
        FS.ensureDirExists(metadataFile.getParent());
        // Metadata already present and fresh (dated far in the future), so it is not re-fetched.
        String originalMetadata = snapshotMetadata("20991231000000", "pre-existing");
        Files.writeString(metadataFile, originalMetadata, UTF_8);

        Path artifact = localRepoDir.resolve("org/eclipse/jetty/jetty-foo/12.99.9-SNAPSHOT/jetty-foo-12.99.9-SNAPSHOT.jar");

        IOException thrown = assertThrows(IOException.class, () -> repo.download(coords, artifact));

        // The original failure is reported, not masked by any cleanup.
        assertThat(thrown.getMessage(), containsString("simulated artifact download failure"));
        // The valid cached metadata this call never touched is left exactly as it was.
        assertThat("metadata preserved", Files.exists(metadataFile), is(true));
        assertThat("metadata unchanged", Files.readString(metadataFile, UTF_8), is(originalMetadata));
        assertThat("no partial artifact left behind", Files.exists(artifact), is(false));
        assertThat("no backup left behind", hasBackupFile(metadataFile.getParent()), is(false));
    }

    @Test
    public void testDownloadRestoresStaleMetadataWhenArtifactDownloadFails()
        throws Exception
    {
        Path localRepoDir = testdir.resolve("snapshot-repo");
        FS.ensureEmpty(localRepoDir);

        MavenLocalRepoFileInitializer repo =
            new MavenLocalRepoFileInitializer(baseHome, new StartArgs(baseHome), localRepoDir, "https://repo1.maven.org/maven2/")
            {
                @Override
                protected void download(URI uri, Path destination) throws IOException
                {
                    if (destination.getFileName().toString().equals("maven-metadata.xml"))
                    {
                        // Overwrite the stale metadata with a fresh copy.
                        FS.ensureDirExists(destination.getParent());
                        Files.writeString(destination, snapshotMetadata("20991231000000", "freshly-downloaded"), UTF_8);
                        return;
                    }
                    // The artifact download then fails, after the metadata was overwritten.
                    throw new IOException("simulated artifact download failure");
                }
            };

        Coordinates coords = repo.getCoordinates(URI.create("maven://org.eclipse.jetty/jetty-foo/12.99.9-SNAPSHOT/jar"));
        Path metadataFile = localRepoDir.resolve(coords.toMetadataPath());
        FS.ensureDirExists(metadataFile.getParent());
        // Metadata present but stale (dated in the past), so it is re-downloaded (overwritten).
        String originalMetadata = snapshotMetadata("20000101000000", "pre-existing-stale");
        Files.writeString(metadataFile, originalMetadata, UTF_8);

        Path artifact = localRepoDir.resolve("org/eclipse/jetty/jetty-foo/12.99.9-SNAPSHOT/jetty-foo-12.99.9-SNAPSHOT.jar");

        IOException thrown = assertThrows(IOException.class, () -> repo.download(coords, artifact));

        assertThat(thrown.getMessage(), containsString("simulated artifact download failure"));
        // The overwrite is rolled back: the original (stale) metadata is restored, not deleted.
        assertThat("metadata restored", Files.exists(metadataFile), is(true));
        assertThat("metadata restored to original", Files.readString(metadataFile, UTF_8), is(originalMetadata));
        assertThat("no partial artifact left behind", Files.exists(artifact), is(false));
        assertThat("no backup left behind", hasBackupFile(metadataFile.getParent()), is(false));
    }

    private static boolean hasBackupFile(Path dir) throws IOException
    {
        try (var entries = Files.list(dir))
        {
            return entries.anyMatch(p -> p.getFileName().toString().contains(".bak."));
        }
    }

    private static String snapshotMetadata(String lastUpdated, String marker)
    {
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <metadata modelVersion="1.1.0">
              <groupId>org.eclipse.jetty</groupId>
              <artifactId>jetty-foo</artifactId>
              <version>12.99.9-SNAPSHOT</version>
              <!-- %s -->
              <versioning>
                <snapshot>
                  <timestamp>20990101.000000</timestamp>
                  <buildNumber>7</buildNumber>
                </snapshot>
                <lastUpdated>%s</lastUpdated>
                <snapshotVersions>
                  <snapshotVersion>
                    <extension>jar</extension>
                    <value>12.99.9-20990101.000000-7</value>
                    <updated>20990101000000</updated>
                  </snapshotVersion>
                </snapshotVersions>
              </versioning>
            </metadata>
            """.formatted(marker, lastUpdated);
    }
}
