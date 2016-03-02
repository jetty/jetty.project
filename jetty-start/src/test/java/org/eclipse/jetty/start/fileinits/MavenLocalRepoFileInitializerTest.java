//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertThat;

import java.io.File;
import java.io.IOException;
import java.net.URI;

import org.eclipse.jetty.start.BaseHome;
import org.eclipse.jetty.start.config.ConfigSources;
import org.eclipse.jetty.start.config.JettyBaseConfigSource;
import org.eclipse.jetty.start.config.JettyHomeConfigSource;
import org.eclipse.jetty.start.fileinits.MavenLocalRepoFileInitializer.Coordinates;
import org.eclipse.jetty.toolchain.test.TestingDir;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class MavenLocalRepoFileInitializerTest
{
    @Rule
    public ExpectedException expectedException = ExpectedException.none();
    
    @Rule
    public TestingDir testdir = new TestingDir();
    
    private BaseHome baseHome;
    
    @Before
    public void setupBaseHome() throws IOException
    {
        File homeDir = testdir.getEmptyDir();
        
        ConfigSources config = new ConfigSources();
        config.add(new JettyHomeConfigSource(homeDir.toPath()));
        config.add(new JettyBaseConfigSource(homeDir.toPath()));

        this.baseHome = new BaseHome(config);
    }

    @Test
    public void testGetCoordinate_NotMaven()
    {
        MavenLocalRepoFileInitializer repo = new MavenLocalRepoFileInitializer(baseHome);
        String ref = "http://www.eclipse.org/jetty";
        Coordinates coords = repo.getCoordinates(URI.create(ref));
        assertThat("Coords",coords,nullValue());
    }

    @Test
    public void testGetCoordinate_InvalidMaven()
    {
        MavenLocalRepoFileInitializer repo = new MavenLocalRepoFileInitializer(baseHome);
        String ref = "maven://www.eclipse.org/jetty";
        expectedException.expect(RuntimeException.class);
        expectedException.expectMessage(containsString("Not a valid maven:// uri"));
        repo.getCoordinates(URI.create(ref));
    }

    @Test
    public void testGetCoordinate_Normal()
    {
        MavenLocalRepoFileInitializer repo = new MavenLocalRepoFileInitializer(baseHome);
        String ref = "maven://org.eclipse.jetty/jetty-start/9.3.x";
        Coordinates coords = repo.getCoordinates(URI.create(ref));
        assertThat("Coordinates",coords,notNullValue());

        assertThat("coords.groupId",coords.groupId,is("org.eclipse.jetty"));
        assertThat("coords.artifactId",coords.artifactId,is("jetty-start"));
        assertThat("coords.version",coords.version,is("9.3.x"));
        assertThat("coords.type",coords.type,is("jar"));
        assertThat("coords.classifier",coords.classifier,nullValue());
        
        assertThat("coords.toCentralURI", coords.toCentralURI().toASCIIString(), 
                is("http://central.maven.org/maven2/org/eclipse/jetty/jetty-start/9.3.x/jetty-start-9.3.x.jar"));
    }

    @Test
    public void testGetCoordinate_Zip()
    {
        MavenLocalRepoFileInitializer repo = new MavenLocalRepoFileInitializer(baseHome);
        String ref = "maven://org.eclipse.jetty/jetty-distribution/9.3.x/zip";
        Coordinates coords = repo.getCoordinates(URI.create(ref));
        assertThat("Coordinates",coords,notNullValue());

        assertThat("coords.groupId",coords.groupId,is("org.eclipse.jetty"));
        assertThat("coords.artifactId",coords.artifactId,is("jetty-distribution"));
        assertThat("coords.version",coords.version,is("9.3.x"));
        assertThat("coords.type",coords.type,is("zip"));
        assertThat("coords.classifier",coords.classifier,nullValue());
        
        assertThat("coords.toCentralURI", coords.toCentralURI().toASCIIString(), 
                is("http://central.maven.org/maven2/org/eclipse/jetty/jetty-distribution/9.3.x/jetty-distribution-9.3.x.zip"));
    }

    @Test
    public void testGetCoordinate_TestJar()
    {
        MavenLocalRepoFileInitializer repo = new MavenLocalRepoFileInitializer(baseHome);
        String ref = "maven://org.eclipse.jetty/jetty-http/9.3.x/jar/tests";
        Coordinates coords = repo.getCoordinates(URI.create(ref));
        assertThat("Coordinates",coords,notNullValue());

        assertThat("coords.groupId",coords.groupId,is("org.eclipse.jetty"));
        assertThat("coords.artifactId",coords.artifactId,is("jetty-http"));
        assertThat("coords.version",coords.version,is("9.3.x"));
        assertThat("coords.type",coords.type,is("jar"));
        assertThat("coords.classifier",coords.classifier,is("tests"));
        
        assertThat("coords.toCentralURI", coords.toCentralURI().toASCIIString(), 
                is("http://central.maven.org/maven2/org/eclipse/jetty/jetty-http/9.3.x/jetty-http-9.3.x-tests.jar"));
    }
    
    @Test
    public void testGetCoordinate_Test_UnspecifiedType()
    {
        MavenLocalRepoFileInitializer repo = new MavenLocalRepoFileInitializer(baseHome);
        String ref = "maven://org.eclipse.jetty/jetty-http/9.3.x//tests";
        Coordinates coords = repo.getCoordinates(URI.create(ref));
        assertThat("Coordinates",coords,notNullValue());

        assertThat("coords.groupId",coords.groupId,is("org.eclipse.jetty"));
        assertThat("coords.artifactId",coords.artifactId,is("jetty-http"));
        assertThat("coords.version",coords.version,is("9.3.x"));
        assertThat("coords.type",coords.type,is("jar"));
        assertThat("coords.classifier",coords.classifier,is("tests"));
        
        assertThat("coords.toCentralURI", coords.toCentralURI().toASCIIString(), 
                is("http://central.maven.org/maven2/org/eclipse/jetty/jetty-http/9.3.x/jetty-http-9.3.x-tests.jar"));
    }
}
