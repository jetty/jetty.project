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

import org.eclipse.jetty.start.BaseHome;
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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

@ExtendWith(WorkDirExtension.class)
public class MavenLocalRepoFileInitializerTest
{
    public WorkDir testdir;

    private BaseHome baseHome;

    @BeforeEach
    public void setupBaseHome() throws IOException
    {
        Path homeDir = testdir.getEmptyPathDir().resolve("home");
        Path baseDir = testdir.getEmptyPathDir().resolve("base");

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
        MavenLocalRepoFileInitializer repo = new MavenLocalRepoFileInitializer(baseHome);
        String ref = "https://www.eclipse.org/jetty/";
        Coordinates coords = repo.getCoordinates(URI.create(ref));
        assertThat("Coords", coords, nullValue());
    }

    @Test
    public void testGetCoordinateInvalidMaven()
    {
        MavenLocalRepoFileInitializer repo = new MavenLocalRepoFileInitializer(baseHome);
        String ref = "maven://www.eclipse.org/jetty";
        RuntimeException x = assertThrows(RuntimeException.class, () -> repo.getCoordinates(URI.create(ref)));
        assertThat(x.getMessage(), containsString("Not a valid maven:// uri"));
    }

    @Test
    public void testGetCoordinateNormal()
    {
        MavenLocalRepoFileInitializer repo = new MavenLocalRepoFileInitializer(baseHome);
        String ref = "maven://org.eclipse.jetty/jetty-start/9.3.x";
        Coordinates coords = repo.getCoordinates(URI.create(ref));
        assertThat("Coordinates", coords, notNullValue());

        assertThat("coords.groupId", coords.groupId, is("org.eclipse.jetty"));
        assertThat("coords.artifactId", coords.artifactId, is("jetty-start"));
        assertThat("coords.version", coords.version, is("9.3.x"));
        assertThat("coords.type", coords.type, is("jar"));
        assertThat("coords.classifier", coords.classifier, nullValue());

        assertThat("coords.toCentralURI", coords.toCentralURI().toASCIIString(),
            is(repo.getRemoteUri() + "org/eclipse/jetty/jetty-start/9.3.x/jetty-start-9.3.x.jar"));
    }

    @Test
    public void testGetCoordinateZip()
    {
        MavenLocalRepoFileInitializer repo = new MavenLocalRepoFileInitializer(baseHome);
        String ref = "maven://org.eclipse.jetty/jetty-home/10.0.0/zip";
        Coordinates coords = repo.getCoordinates(URI.create(ref));
        assertThat("Coordinates", coords, notNullValue());

        assertThat("coords.groupId", coords.groupId, is("org.eclipse.jetty"));
        assertThat("coords.artifactId", coords.artifactId, is("jetty-home"));
        assertThat("coords.version", coords.version, is("10.0.0"));
        assertThat("coords.type", coords.type, is("zip"));
        assertThat("coords.classifier", coords.classifier, nullValue());

        assertThat("coords.toCentralURI", coords.toCentralURI().toASCIIString(),
                   is(repo.getRemoteUri() + "org/eclipse/jetty/jetty-home/10.0.0/jetty-home-10.0.0.zip"));
    }

    @Test
    public void testGetCoordinateTestJar()
    {
        MavenLocalRepoFileInitializer repo = new MavenLocalRepoFileInitializer(baseHome);
        String ref = "maven://org.eclipse.jetty/jetty-http/9.3.x/jar/tests";
        Coordinates coords = repo.getCoordinates(URI.create(ref));
        assertThat("Coordinates", coords, notNullValue());

        assertThat("coords.groupId", coords.groupId, is("org.eclipse.jetty"));
        assertThat("coords.artifactId", coords.artifactId, is("jetty-http"));
        assertThat("coords.version", coords.version, is("9.3.x"));
        assertThat("coords.type", coords.type, is("jar"));
        assertThat("coords.classifier", coords.classifier, is("tests"));

        assertThat("coords.toCentralURI", coords.toCentralURI().toASCIIString(),
            is(repo.getRemoteUri() + "org/eclipse/jetty/jetty-http/9.3.x/jetty-http-9.3.x-tests.jar"));
    }

    @Test
    public void testGetCoordinateTestUnspecifiedType()
    {
        MavenLocalRepoFileInitializer repo = new MavenLocalRepoFileInitializer(baseHome);
        String ref = "maven://org.eclipse.jetty/jetty-http/9.3.x//tests";
        Coordinates coords = repo.getCoordinates(URI.create(ref));
        assertThat("Coordinates", coords, notNullValue());

        assertThat("coords.groupId", coords.groupId, is("org.eclipse.jetty"));
        assertThat("coords.artifactId", coords.artifactId, is("jetty-http"));
        assertThat("coords.version", coords.version, is("9.3.x"));
        assertThat("coords.type", coords.type, is("jar"));
        assertThat("coords.classifier", coords.classifier, is("tests"));

        assertThat("coords.toCentralURI", coords.toCentralURI().toASCIIString(),
            is(repo.getRemoteUri() + "org/eclipse/jetty/jetty-http/9.3.x/jetty-http-9.3.x-tests.jar"));
    }

    @Test
    public void testGetCoordinateTestMavenBaseUri()
    {
        MavenLocalRepoFileInitializer repo =
            new MavenLocalRepoFileInitializer(baseHome, null, false,
                "https://repo1.maven.org/maven2/");
        String ref = "maven://org.eclipse.jetty/jetty-http/9.3.x/jar/tests";
        Coordinates coords = repo.getCoordinates(URI.create(ref));
        assertThat("Coordinates", coords, notNullValue());

        assertThat("coords.groupId", coords.groupId, is("org.eclipse.jetty"));
        assertThat("coords.artifactId", coords.artifactId, is("jetty-http"));
        assertThat("coords.version", coords.version, is("9.3.x"));
        assertThat("coords.type", coords.type, is("jar"));
        assertThat("coords.classifier", coords.classifier, is("tests"));

        assertThat("coords.toCentralURI", coords.toCentralURI().toASCIIString(),
            is(repo.getRemoteUri() + "org/eclipse/jetty/jetty-http/9.3.x/jetty-http-9.3.x-tests.jar"));
    }

    @Test
    @Tag("external")
    public void testDownloadUnspecifiedRepo()
        throws Exception
    {
        MavenLocalRepoFileInitializer repo =
            new MavenLocalRepoFileInitializer(baseHome, null, false);
        String ref = "maven://org.eclipse.jetty/jetty-http/9.4.31.v20200723/jar/tests";
        Coordinates coords = repo.getCoordinates(URI.create(ref));
        assertThat("Coordinates", coords, notNullValue());

        assertThat("coords.groupId", coords.groupId, is("org.eclipse.jetty"));
        assertThat("coords.artifactId", coords.artifactId, is("jetty-http"));
        assertThat("coords.version", coords.version, is("9.4.31.v20200723"));
        assertThat("coords.type", coords.type, is("jar"));
        assertThat("coords.classifier", coords.classifier, is("tests"));

        assertThat("coords.toCentralURI", coords.toCentralURI().toASCIIString(),
                   is(repo.getRemoteUri() + "org/eclipse/jetty/jetty-http/9.4.31.v20200723/jetty-http-9.4.31.v20200723-tests.jar"));

        Path destination = testdir.getEmptyPathDir().resolve("jetty-http-9.4.31.v20200723-tests.jar");
        Files.deleteIfExists(destination);
        repo.download(coords.toCentralURI(), destination);
        assertThat(Files.exists(destination), is(true));
        assertThat(Files.size(destination), is(986193L));
    }

    @Test
    @Tag("external")
    public void testDownloadSnapshotRepo()
        throws Exception
    {
        Path snapshotLocalRepoDir = testdir.getPath().resolve("snapshot-repo");
        FS.ensureEmpty(snapshotLocalRepoDir);

        MavenLocalRepoFileInitializer repo =
            new MavenLocalRepoFileInitializer(baseHome, snapshotLocalRepoDir, false, "https://oss.sonatype.org/content/repositories/jetty-snapshots/");
        String ref = "maven://org.eclipse.jetty/jetty-rewrite/11.0.0-SNAPSHOT/jar";
        Coordinates coords = repo.getCoordinates(URI.create(ref));
        assertThat("Coordinates", coords, notNullValue());

        assertThat("coords.groupId", coords.groupId, is("org.eclipse.jetty"));
        assertThat("coords.artifactId", coords.artifactId, is("jetty-rewrite"));
        assertThat("coords.version", coords.version, is("11.0.0-SNAPSHOT"));
        assertThat("coords.type", coords.type, is("jar"));
        assertThat("coords.classifier", coords.classifier, is(nullValue()));

        assertThat("coords.toCentralURI", coords.toCentralURI().toASCIIString(),
            is("https://oss.sonatype.org/content/repositories/jetty-snapshots/org/eclipse/jetty/jetty-rewrite/11.0.0-SNAPSHOT/jetty-rewrite-11.0.0-SNAPSHOT.jar"));

        Path destination = baseHome.getBasePath().resolve("jetty-rewrite-11.0.0-SNAPSHOT.jar");
        repo.download(coords, destination);
        assertThat(Files.exists(destination), is(true));
        assertThat("Snapshot File size", Files.size(destination), greaterThan(10_000L));
    }

    @Test
    @Tag("external")
    public void testDownloadSnapshotRepoWithExtractDeep()
        throws Exception
    {
        Path snapshotLocalRepoDir = testdir.getPath().resolve("snapshot-repo");
        FS.ensureEmpty(snapshotLocalRepoDir);

        MavenLocalRepoFileInitializer repo =
            new MavenLocalRepoFileInitializer(baseHome, snapshotLocalRepoDir, false,
                "https://oss.sonatype.org/content/repositories/jetty-snapshots/");
        String ref = "maven://org.eclipse.jetty/test-jetty-webapp/11.0.0-SNAPSHOT/jar/config";
        Path baseDir = baseHome.getBasePath();
        repo.create(URI.create(ref), "extract:company/");

        assertThat(Files.exists(baseDir.resolve("company/webapps/test.d/override-web.xml")), is(true));
    }

    @Test
    @Tag("external")
    public void testDownloadSnapshotRepoWithExtractDefault()
        throws Exception
    {
        Path snapshotLocalRepoDir = testdir.getPath().resolve("snapshot-repo");
        FS.ensureEmpty(snapshotLocalRepoDir);

        MavenLocalRepoFileInitializer repo =
            new MavenLocalRepoFileInitializer(baseHome, snapshotLocalRepoDir, false,
                "https://oss.sonatype.org/content/repositories/jetty-snapshots/");
        String ref = "maven://org.eclipse.jetty/test-jetty-webapp/11.0.0-SNAPSHOT/jar/config";
        Path baseDir = baseHome.getBasePath();
        repo.create(URI.create(ref), "extract:/");

        assertThat(Files.exists(baseDir.resolve("webapps/test.d/override-web.xml")), is(true));
    }
}
