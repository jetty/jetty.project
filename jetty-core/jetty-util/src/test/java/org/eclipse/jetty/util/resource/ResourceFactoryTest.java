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

package org.eclipse.jetty.util.resource;

import java.net.URI;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class ResourceFactoryTest
{
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
    public void testRegisterHttpsUrlFactory()
    {
        ResourceFactory.registerResourceFactory("https", new URLResourceFactory());
        // Try as a normal String input
        Resource resource = ResourceFactory.root().newResource("https://webtide.com/");
        assertThat(resource.getURI(), is(URI.create("https://webtide.com/")));
        assertThat(resource.getName(), is("https://webtide.com/"));

        // Try as a formal URI object as input
        URI uri = URI.create("https://webtide.com/");
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
                public boolean isContainedIn(Resource r)
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
