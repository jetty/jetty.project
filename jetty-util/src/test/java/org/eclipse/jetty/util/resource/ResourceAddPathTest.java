//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.TypeUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

public class ResourceAddPathTest
{
    private static List<Resource> resourceImpls(String testname)
    {
        List<Resource> resourceImpls = new ArrayList<>();

        Path testDir = MavenTestingUtils.getTargetTestingPath(testname);
        FS.ensureDirExists(testDir);
        resourceImpls.add(new PathResource(testDir));

        Path spaceDir = MavenTestingUtils.getTargetTestingPath("a test called " + testname);
        FS.ensureDirExists(spaceDir);
        resourceImpls.add(new PathResource(spaceDir));

        try
        {
            URL urlDir = testDir.toUri().toURL();
            resourceImpls.add(new URLResource(urlDir, null));
        }
        catch (MalformedURLException e)
        {
            throw new RuntimeException("Unable to init URLResource", e);
        }

        try
        {
            URI assertionsClassRef = TypeUtil.getLocationOfClass(Assertions.class);
            if (assertionsClassRef == null)
            {
                throw new RuntimeException("Unable to find " + Assertions.class.getName());
            }
            URI jarClassRef = URI.create("jar:" + assertionsClassRef.toASCIIString() + "!/");
            resourceImpls.add(new JarFileResource(jarClassRef.toURL(), false));
        }
        catch (MalformedURLException e)
        {
            throw new RuntimeException("Unable to init JarFileResource", e);
        }

        return resourceImpls;
    }

    public static Stream<Arguments> addPathSuccessCases()
    {
        List<Arguments> cases = new ArrayList<>();
        for (Resource resource : resourceImpls("addPathSuccessCases"))
        {
            // Referencing self
            cases.add(Arguments.of(resource, ".", resource.getURI().toASCIIString() + "./"));
            cases.add(Arguments.of(resource, "/.", resource.getURI().toASCIIString() + "./"));
            cases.add(Arguments.of(resource, "", resource.getURI().toASCIIString()));
            cases.add(Arguments.of(resource, "/", resource.getURI().toASCIIString()));

            // Simple references
            cases.add(Arguments.of(resource, "foo.txt", resource.getURI().toASCIIString() + "foo.txt"));
            cases.add(Arguments.of(resource, "/foo.txt", resource.getURI().toASCIIString() + "foo.txt"));

            // Empty segment references
            cases.add(Arguments.of(resource, "//bar.txt", resource.getURI().toASCIIString() + "/bar.txt"));

            // PathResource specific tests
            if (resource instanceof PathResource)
            {
                if (File.separatorChar == '\\') // Windows
                {
                    // where backslash is path separator
                    cases.add(Arguments.of(resource, "bar\\zed.txt", resource.getURI().toASCIIString() + "bar/zed.txt"));
                }
                else if (File.separatorChar == '/') // Unix / Linux / OSX
                {
                    // where backslash is just a character in a name
                    cases.add(Arguments.of(resource, "bar\\zed.txt", resource.getURI().toASCIIString() + "bar%5Czed.txt"));
                }
            }

            // Special characters - URI Reserved Characters in use
            // The Resource.getURI() should reflect that the URI reserved character, if used, is encoded
            // The only reserved character that is not encoded is '/' (path separator)
            // URI Reserved = gen-delims  = ":" / "/" / "?" / "#" / "[" / "]" / "@"
            cases.add(Arguments.of(resource, "/org:name.dat", resource.getURI().toASCIIString() + "org%3Aname.dat"));
            cases.add(Arguments.of(resource, "/what-now?.xml", resource.getURI().toASCIIString() + "what-now%3F.xml"));
            cases.add(Arguments.of(resource, "/entry #42.json", resource.getURI().toASCIIString() + "entry%20%2342.json"));
            cases.add(Arguments.of(resource, "/mambo[i].js", resource.getURI().toASCIIString() + "mambo%5Bi%5D.js"));
            cases.add(Arguments.of(resource, "/emails@machine.csv", resource.getURI().toASCIIString() + "emails%40machine.csv"));
            // URI Reserved = sub-delims  = "!" / "$" / "&" / "'" / "(" / ")" / "*" / "+" / "," / ";" / "="
            cases.add(Arguments.of(resource, "/alert!.css", resource.getURI().toASCIIString() + "alert%21.css"));
            cases.add(Arguments.of(resource, "/beans&toast.jpeg", resource.getURI().toASCIIString() + "beans%26toast.jpeg"));
            cases.add(Arguments.of(resource, "/a_'widget'.png", resource.getURI().toASCIIString() + "a_%27widget%27.png"));
            cases.add(Arguments.of(resource, "/reason(alt).gz", resource.getURI().toASCIIString() + "reason%28alt%29.gz"));
            cases.add(Arguments.of(resource, "/glob-*.bz", resource.getURI().toASCIIString() + "glob-%2A.bz"));
            cases.add(Arguments.of(resource, "/this+that.dat", resource.getURI().toASCIIString() + "this%2Bthat.dat"));
            cases.add(Arguments.of(resource, "/x,y,z", resource.getURI().toASCIIString() + "x%2Cy%2Cz"));
            cases.add(Arguments.of(resource, "/a;z=100/bar;.txt", resource.getURI().toASCIIString() + "a%3Bz=100/bar%3B.txt"));

            // Unicode
            cases.add(Arguments.of(resource, "swedish-å.txt", resource.getURI().toASCIIString() + "swedish-%C3%A5.txt"));
            cases.add(Arguments.of(resource, "swedish-ä.txt", resource.getURI().toASCIIString() + "swedish-%C3%A4.txt"));
            cases.add(Arguments.of(resource, "swedish-ö.txt", resource.getURI().toASCIIString() + "swedish-%C3%B6.txt"));

            // Spaces
            cases.add(Arguments.of(resource, "a resource with spaces.dat", resource.getURI().toASCIIString() + "a%20resource%20with%20spaces.dat"));
            cases.add(Arguments.of(resource, "/a resource with spaces.dat", resource.getURI().toASCIIString() + "a%20resource%20with%20spaces.dat"));

            // Path traversal
            cases.add(Arguments.of(resource, "zed/../zed/bar.txt", resource.getURI().toASCIIString() + "zed/../zed/bar.txt"));
            cases.add(Arguments.of(resource, "/zed/../zed/bar.txt", resource.getURI().toASCIIString() + "zed/../zed/bar.txt"));

            // Add Path with raw "%" symbol
            cases.add(Arguments.of(resource, "this is 100% valid.txt", resource.getURI().toASCIIString() + "this%20is%20100%25%20valid.txt"));
            cases.add(Arguments.of(resource, "..%5Cbar.txt", resource.getURI().toASCIIString() + "..%255Cbar.txt"));
            cases.add(Arguments.of(resource, "%5C..", resource.getURI().toASCIIString() + "%255C.."));
            cases.add(Arguments.of(resource, "%5C..%5C", resource.getURI().toASCIIString() + "%255C..%255C"));
        }

        return cases.stream();
    }

    @ParameterizedTest(name = "[{index}] {1}, {0}")
    @MethodSource("addPathSuccessCases")
    public void testAddPathSuccess(Resource baseResource, String addPath, String expectedResourceUri) throws IOException
    {
        Resource resource = baseResource.addPath(addPath);
        assertThat(String.format("%s(\"%s\").addPath(\"%s\")", baseResource.getClass().getSimpleName(), baseResource, addPath),
            resource.getURI(),
            equalTo(URI.create(expectedResourceUri)));

        // TODO: make sure resulting URI can generate the same Resource from Resource.newResource(String/URI)
    }

    public static Stream<Arguments> addPathBad()
    {
        List<Arguments> cases = new ArrayList<>();
        for (Resource resource : resourceImpls("addPathBad"))
        {
            cases.add(Arguments.of(resource, "../"));
            cases.add(Arguments.of(resource, "/.."));
            cases.add(Arguments.of(resource, "/../"));
            cases.add(Arguments.of(resource, ".."));

            cases.add(Arguments.of(resource, "../foo.txt"));
            cases.add(Arguments.of(resource, "/../foo.txt"));
            cases.add(Arguments.of(resource, "./../foo.txt"));
            cases.add(Arguments.of(resource, "/./../foo.txt"));

            cases.add(Arguments.of(resource, "..\\"));
            cases.add(Arguments.of(resource, "\\.."));
            cases.add(Arguments.of(resource, "\\..\\"));
            cases.add(Arguments.of(resource, "/..\\"));
            cases.add(Arguments.of(resource, "/\\.."));
            cases.add(Arguments.of(resource, "/\\..\\"));
        }

        return cases.stream();
    }

    @ParameterizedTest(name = "[{index}] {1}, {0}")
    @MethodSource("addPathBad")
    public void testAddPathBad(Resource baseResource, String addPath)
    {
        Assertions.assertThrows(MalformedURLException.class,
            () -> baseResource.addPath(addPath),
            () -> String.format("%s(\"%s\").addPath(\"%s\")", baseResource.getClass().getSimpleName(), baseResource, addPath)
        );
    }

    public static Stream<Arguments> addPathUriReservedChars()
    {
        List<Arguments> cases = new ArrayList<>();
        for (Resource resource : resourceImpls("addPathBad"))
        {
            cases.add(Arguments.of(resource, "/org:name.dat", "org%3Aname.dat"));
            cases.add(Arguments.of(resource, "/what-now?.xml", "what-now%3F.xml"));
            cases.add(Arguments.of(resource, "/entry #42.json", "entry%20%2342.json"));
            cases.add(Arguments.of(resource, "/mambo[i].js", "mambo%5Bi%5D.js"));
            cases.add(Arguments.of(resource, "/emails@machine.csv", "emails%40machine.csv"));
            // URI Reserved = sub-delims  = "!" / "$" / "&" / "'" / "(" / ")" / "*" / "+" / "," / ";" / "="
            cases.add(Arguments.of(resource, "/alert!.css", "alert%21.css"));
            cases.add(Arguments.of(resource, "/beans&toast.jpeg", "beans%26toast.jpeg"));
            cases.add(Arguments.of(resource, "/a_'widget'.png", "a_%27widget%27.png"));
            cases.add(Arguments.of(resource, "/reason(alt).gz", "reason%28alt%29.gz"));
            cases.add(Arguments.of(resource, "/glob-*.bz", "glob-%2A.bz"));
            cases.add(Arguments.of(resource, "/this+that.dat", "this%2Bthat.dat"));
            cases.add(Arguments.of(resource, "/x,y,z", "x%2Cy%2Cz"));
            cases.add(Arguments.of(resource, "/a;z=100/bar;.txt", "a%3Bz=100/bar%3B.txt"));
        }
        return cases.stream();
    }

    @ParameterizedTest
    @MethodSource("addPathUriReservedChars")
    public void testAddPathUriReservedChars(Resource resource, String inputAddPath, String expectedEncodedPath)
    {

    }
}
