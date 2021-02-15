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
import java.nio.file.Paths;

import org.eclipse.jetty.start.BaseHome;
import org.eclipse.jetty.start.config.ConfigSources;
import org.eclipse.jetty.start.config.JettyBaseConfigSource;
import org.eclipse.jetty.start.config.JettyHomeConfigSource;
import org.eclipse.jetty.start.fileinits.MavenLocalRepoFileInitializer.Coordinates;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
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
        Path homeDir = testdir.getEmptyPathDir();

        ConfigSources config = new ConfigSources();
        config.add(new JettyHomeConfigSource(homeDir));
        config.add(new JettyBaseConfigSource(homeDir));

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
        String ref = "maven://org.eclipse.jetty/jetty-distribution/9.3.x/zip";
        Coordinates coords = repo.getCoordinates(URI.create(ref));
        assertThat("Coordinates", coords, notNullValue());

        assertThat("coords.groupId", coords.groupId, is("org.eclipse.jetty"));
        assertThat("coords.artifactId", coords.artifactId, is("jetty-distribution"));
        assertThat("coords.version", coords.version, is("9.3.x"));
        assertThat("coords.type", coords.type, is("zip"));
        assertThat("coords.classifier", coords.classifier, nullValue());

        assertThat("coords.toCentralURI", coords.toCentralURI().toASCIIString(),
            is(repo.getRemoteUri() + "org/eclipse/jetty/jetty-distribution/9.3.x/jetty-distribution-9.3.x.zip"));
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
        String ref = "maven://org.eclipse.jetty/jetty-http/9.4.10.v20180503/jar/tests";
        Coordinates coords = repo.getCoordinates(URI.create(ref));
        assertThat("Coordinates", coords, notNullValue());

        assertThat("coords.groupId", coords.groupId, is("org.eclipse.jetty"));
        assertThat("coords.artifactId", coords.artifactId, is("jetty-http"));
        assertThat("coords.version", coords.version, is("9.4.10.v20180503"));
        assertThat("coords.type", coords.type, is("jar"));
        assertThat("coords.classifier", coords.classifier, is("tests"));

        assertThat("coords.toCentralURI", coords.toCentralURI().toASCIIString(),
            is(repo.getRemoteUri() + "org/eclipse/jetty/jetty-http/9.4.10.v20180503/jetty-http-9.4.10.v20180503-tests.jar"));

        Path destination = Paths.get(System.getProperty("java.io.tmpdir"), "jetty-http-9.4.10.v20180503-tests.jar");
        Files.deleteIfExists(destination);
        repo.download(coords.toCentralURI(), destination);
        assertThat(Files.exists(destination), is(true));
        assertThat(destination.toFile().length(), is(962621L));
    }
}
