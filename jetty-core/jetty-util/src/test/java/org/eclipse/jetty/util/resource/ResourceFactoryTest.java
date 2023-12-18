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
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ResourceFactoryTest
{
    @ParameterizedTest
    @ValueSource(strings = {
        "keystore.p12", "/keystore.p12",
        "TestData/alphabet.txt", "/TestData/alphabet.txt",
        "TestData/", "/TestData/", "TestData", "/TestData"
    })
    public void testNewClassLoaderResourceExists(String reference)
    {
        try (ResourceFactory.Closeable resourceFactory = ResourceFactory.closeable())
        {
            Resource resource = resourceFactory.newClassLoaderResource(reference);
            assertNotNull(resource, "Reference [" + reference + "] should be found");
            assertTrue(resource.exists(), "Reference [" + reference + "] -> Resource[" + resource + "] should exist");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"does-not-exist.dat", "non-existent/dir/contents.txt", "/../"})
    public void testNewClassLoaderResourceDoesNotExists(String reference)
    {
        try (ResourceFactory.Closeable resourceFactory = ResourceFactory.closeable())
        {
            Resource resource = resourceFactory.newClassLoaderResource(reference);
            assertNull(resource, "Reference [" + reference + "] should not be found");
        }
    }

    public static List<String> badReferenceNamesSource()
    {
        List<String> names = new ArrayList<>();
        names.add(null);
        names.add("");
        names.add(" ");
        names.add("\r");
        names.add("\r\n");
        names.add("  \t  ");
        return names;
    }

    @ParameterizedTest
    @MethodSource("badReferenceNamesSource")
    public void testNewClassLoaderResourceDoesBadInput(String reference)
    {
        try (ResourceFactory.Closeable resourceFactory = ResourceFactory.closeable())
        {
            assertThrows(IllegalArgumentException.class,
                () -> resourceFactory.newClassLoaderResource(reference),
                "Bad Reference [" + reference + "] should have failed");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "keystore.p12", "/keystore.p12",
        "TestData/alphabet.txt", "/TestData/alphabet.txt",
        "TestData/", "/TestData/", "TestData", "/TestData"
    })
    public void testNewClassPathResourceExists(String reference)
    {
        try (ResourceFactory.Closeable resourceFactory = ResourceFactory.closeable())
        {
            Resource resource = resourceFactory.newClassPathResource(reference);
            assertNotNull(resource, "Reference [" + reference + "] should be found");
            assertTrue(resource.exists(), "Reference [" + reference + "] -> Resource[" + resource + "] should exist");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"does-not-exist.dat", "non-existent/dir/contents.txt", "/../"})
    public void testNewClassPathResourceDoesNotExists(String reference)
    {
        try (ResourceFactory.Closeable resourceFactory = ResourceFactory.closeable())
        {
            Resource resource = resourceFactory.newClassPathResource(reference);
            assertNull(resource, "Reference [" + reference + "] should not be found");
        }
    }

    @ParameterizedTest
    @MethodSource("badReferenceNamesSource")
    public void testNewClassPathResourceDoesBadInput(String reference)
    {
        try (ResourceFactory.Closeable resourceFactory = ResourceFactory.closeable())
        {
            assertThrows(IllegalArgumentException.class,
                () -> resourceFactory.newClassPathResource(reference),
                "Bad Reference [" + reference + "] should have failed");
        }
    }

    @Test
    public void testCustomUriSchemeNotRegistered()
    {
        // Try this as a normal String input first.
        // We are subject to the URIUtil.toURI(String) behaviors here.
        // Since the `ftp` scheme is not registered, it's not recognized as a supported URI.
        // This will be treated as a relative path instead. (and the '//' will be compacted)
        Resource resource = ResourceFactory.root().newResource("ftp://webtide.com/favicon.ico");
        // Should not find this, as it doesn't exist on the filesystem.
        assertNull(resource);

        // Now try it as a formal URI object as input.
        URI uri = URI.create("ftp://webtide.com/favicon.ico");
        // This is an unsupported URI scheme
        IllegalArgumentException iae = assertThrows(IllegalArgumentException.class, () -> ResourceFactory.root().newResource(uri));
        assertThat(iae.getMessage(), containsString("URI scheme not supported"));
    }

    @Test
    public void testCustomUriSchemeRegistered()
    {
        ResourceFactory.registerResourceFactory("custom", new CustomResourceFactory());
        // Try as a normal String input
        Resource resource = ResourceFactory.root().newResource("custom://foo");
        assertThat(resource.getURI(), is(URI.create("custom://foo")));
        assertThat(resource.getName(), is("custom-impl"));

        // Try as a formal URI object as input
        URI uri = URI.create("custom://foo");
        resource = ResourceFactory.root().newResource(uri);
        assertThat(resource.getURI(), is(URI.create("custom://foo")));
        assertThat(resource.getName(), is("custom-impl"));
    }

    @Test
    public void testRegisterHttpsUrlFactory() throws MalformedURLException
    {
        URI uri = URI.create("https://webtide.com/");

        // Verify that environment can access the URI.
        URL url = uri.toURL();
        try
        {
            HttpURLConnection http = (HttpURLConnection)url.openConnection();
            Assumptions.assumeTrue(http.getResponseCode() == HttpURLConnection.HTTP_OK);
        }
        catch (IOException e)
        {
            Assumptions.abort("Unable to connect to " + uri);
        }

        ResourceFactory.registerResourceFactory("https", new URLResourceFactory());
        // Try as a normal String input
        Resource resource = ResourceFactory.root().newResource("https://webtide.com/");
        assertThat(resource.getURI(), is(URI.create("https://webtide.com/")));
        assertThat(resource.getName(), is("https://webtide.com/"));

        // Try as a formal URI object as input
        resource = ResourceFactory.root().newResource(uri);
        assertThat(resource.getURI(), is(URI.create("https://webtide.com/")));
        assertThat(resource.getName(), is("https://webtide.com/"));

        // Try a sub-resource
        Resource subResource = resource.resolve("favicon.ico");
        assertThat(subResource.getFileName(), is("favicon.ico"));
        assertThat(subResource.length(), greaterThan(0L));
    }

    public static class CustomResourceFactory implements ResourceFactory
    {
        @Override
        public Resource newResource(URI uri)
        {
            return new Resource()
            {
                @Override
                public Path getPath()
                {
                    return null;
                }

                @Override
                public boolean isContainedIn(Resource container)
                {
                    return false;
                }

                @Override
                public boolean isDirectory()
                {
                    return false;
                }

                @Override
                public boolean isReadable()
                {
                    return false;
                }

                @Override
                public URI getURI()
                {
                    return uri;
                }

                @Override
                public String getName()
                {
                    return "custom-impl";
                }

                @Override
                public String getFileName()
                {
                    return null;
                }

                @Override
                public Resource resolve(String subUriPath)
                {
                    return null;
                }
            };
        }
    }
}
