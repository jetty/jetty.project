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

package org.eclipse.jetty.server;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.xml.sax.SAXException;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ResourceListingTest
{
    @Test
    public void testBasicResourceXHtmlListingRoot(WorkDir workDir) throws IOException
    {
        Path root = workDir.getEmptyPathDir();

        FS.touch(root.resolve("entry1.txt"));
        FS.touch(root.resolve("entry2.dat"));
        Files.createDirectory(root.resolve("dirFoo"));
        Files.createDirectory(root.resolve("dirBar"));
        Files.createDirectory(root.resolve("dirZed"));

        try (ResourceFactory.Closeable resourceFactory = ResourceFactory.closeable())
        {
            Resource resource = resourceFactory.newResource(root);
            String content = ResourceListing.getAsXHTML(resource, "/", false, null);
            assertTrue(isValidXHtml(content));

            assertThat(content, containsString("entry1.txt"));
            assertThat(content, containsString("<a href=\"/entry1.txt\">"));
            assertThat(content, containsString("entry2.dat"));
            assertThat(content, containsString("<a href=\"/entry2.dat\">"));
            assertThat(content, containsString("dirFoo/"));
            assertThat(content, containsString("<a href=\"/dirFoo/\">"));
            assertThat(content, containsString("dirBar/"));
            assertThat(content, containsString("<a href=\"/dirBar/\">"));
            assertThat(content, containsString("dirZed/"));
            assertThat(content, containsString("<a href=\"/dirZed/\">"));
        }
    }

    @Test
    public void testBasicResourceXHtmlListingDeep(WorkDir workDir) throws IOException
    {
        Path root = workDir.getEmptyPathDir();

        FS.touch(root.resolve("entry1.txt"));
        FS.touch(root.resolve("entry2.dat"));
        Files.createDirectory(root.resolve("dirFoo"));
        Files.createDirectory(root.resolve("dirBar"));
        Files.createDirectory(root.resolve("dirZed"));

        try (ResourceFactory.Closeable resourceFactory = ResourceFactory.closeable())
        {
            Resource resource = resourceFactory.newResource(root);
            String content = ResourceListing.getAsXHTML(resource, "/deep/", false, null);
            assertTrue(isValidXHtml(content));

            assertThat(content, containsString("entry1.txt"));
            assertThat(content, containsString("<a href=\"/deep/entry1.txt\">"));
            assertThat(content, containsString("entry2.dat"));
            assertThat(content, containsString("<a href=\"/deep/entry2.dat\">"));
            assertThat(content, containsString("dirFoo/"));
            assertThat(content, containsString("<a href=\"/deep/dirFoo/\">"));
            assertThat(content, containsString("dirBar/"));
            assertThat(content, containsString("<a href=\"/deep/dirBar/\">"));
            assertThat(content, containsString("dirZed/"));
            assertThat(content, containsString("<a href=\"/deep/dirZed/\">"));
        }
    }

    @Test
    public void testResourceCollectionXHtmlListingContext(WorkDir workDir) throws IOException
    {
        Path root = workDir.getEmptyPathDir();

        Path docrootA = root.resolve("docrootA");
        Files.createDirectory(docrootA);
        FS.touch(docrootA.resolve("entry1.txt"));
        FS.touch(docrootA.resolve("entry2.dat"));
        Files.createDirectory(docrootA.resolve("dirFoo"));
        Files.createDirectory(docrootA.resolve("dirBar"));

        Path docrootB = root.resolve("docrootB");
        Files.createDirectory(docrootB);
        FS.touch(docrootB.resolve("entry3.png"));
        FS.touch(docrootB.resolve("entry4.tar.gz"));
        Files.createDirectory(docrootB.resolve("dirCid"));
        Files.createDirectory(docrootB.resolve("dirZed"));

        try (ResourceFactory.Closeable resourceFactory = ResourceFactory.closeable())
        {
            List<URI> uriRootList = List.of(docrootA.toUri(), docrootB.toUri());
            Resource resource = resourceFactory.newResource(uriRootList);
            String content = ResourceListing.getAsXHTML(resource, "/context/", false, null);
            assertTrue(isValidXHtml(content));

            assertThat(content, containsString("entry1.txt"));
            assertThat(content, containsString("<a href=\"/context/entry1.txt\">"));
            assertThat(content, containsString("entry2.dat"));
            assertThat(content, containsString("<a href=\"/context/entry2.dat\">"));
            assertThat(content, containsString("entry3.png"));
            assertThat(content, containsString("<a href=\"/context/entry3.png\">"));
            assertThat(content, containsString("entry4.tar.gz"));
            assertThat(content, containsString("<a href=\"/context/entry4.tar.gz\">"));
            assertThat(content, containsString("dirFoo/"));
            assertThat(content, containsString("<a href=\"/context/dirFoo/\">"));
            assertThat(content, containsString("dirBar/"));
            assertThat(content, containsString("<a href=\"/context/dirBar/\">"));
            assertThat(content, containsString("dirCid/"));
            assertThat(content, containsString("<a href=\"/context/dirCid/\">"));
            assertThat(content, containsString("dirZed/"));
            assertThat(content, containsString("<a href=\"/context/dirZed/\">"));
        }
    }

    @Test
    public void testResourceCollectionMixedTypesXHtmlListingContext(WorkDir workDir) throws IOException
    {
        Path root = workDir.getEmptyPathDir();

        Path docrootA = root.resolve("docrootA");
        Files.createDirectory(docrootA);
        FS.touch(docrootA.resolve("entry1.txt"));
        FS.touch(docrootA.resolve("entry2.dat"));
        Files.createDirectory(docrootA.resolve("dirFoo"));
        Files.createDirectory(docrootA.resolve("dirBar"));

        Path docrootB = root.resolve("docrootB");
        Files.createDirectory(docrootB);
        FS.touch(docrootB.resolve("entry3.png"));
        FS.touch(docrootB.resolve("entry4.tar.gz"));
        Files.createDirectory(docrootB.resolve("dirCid"));
        Files.createDirectory(docrootB.resolve("dirZed"));

        // Introduce a non-directory entry
        Path docNonRootC = root.resolve("non-root.dat");
        FS.touch(docNonRootC);

        try (ResourceFactory.Closeable resourceFactory = ResourceFactory.closeable())
        {
            // Collection consisting of file, dir, dir
            List<URI> uriRootList = List.of(docNonRootC.toUri(), docrootA.toUri(), docrootB.toUri());
            Resource resource = resourceFactory.newResource(uriRootList);
            String content = ResourceListing.getAsXHTML(resource, "/context/", false, null);
            assertTrue(isValidXHtml(content));

            assertThat(content, containsString("entry1.txt"));
            assertThat(content, containsString("<a href=\"/context/entry1.txt\">"));
            assertThat(content, containsString("entry2.dat"));
            assertThat(content, containsString("<a href=\"/context/entry2.dat\">"));
            assertThat(content, containsString("entry3.png"));
            assertThat(content, containsString("<a href=\"/context/entry3.png\">"));
            assertThat(content, containsString("entry4.tar.gz"));
            assertThat(content, containsString("<a href=\"/context/entry4.tar.gz\">"));
            assertThat(content, containsString("dirFoo/"));
            assertThat(content, containsString("<a href=\"/context/dirFoo/\">"));
            assertThat(content, containsString("dirBar/"));
            assertThat(content, containsString("<a href=\"/context/dirBar/\">"));
            assertThat(content, containsString("dirCid/"));
            assertThat(content, containsString("<a href=\"/context/dirCid/\">"));
            assertThat(content, containsString("dirZed/"));
            assertThat(content, containsString("<a href=\"/context/dirZed/\">"));
            assertThat(content, containsString("<a href=\"/context/non-root.dat\">"));
        }
    }

    /**
     * Test a ResourceCollection that is constructed by nested ResourceCollection.
     */
    @Test
    public void testResourceCollectionNestedXHtmlListingContext(WorkDir workDir) throws IOException
    {
        Path root = workDir.getEmptyPathDir();

        Path docrootA = root.resolve("docrootA");
        Files.createDirectory(docrootA);
        FS.touch(docrootA.resolve("entry1.txt"));
        FS.touch(docrootA.resolve("entry2.dat"));
        Files.createDirectory(docrootA.resolve("dirFoo"));
        Files.createDirectory(docrootA.resolve("dirBar"));

        Path docrootB = root.resolve("docrootB");
        Files.createDirectory(docrootB);
        FS.touch(docrootB.resolve("entry3.png"));
        FS.touch(docrootB.resolve("entry4.tar.gz"));
        Files.createDirectory(docrootB.resolve("dirCid"));
        Files.createDirectory(docrootB.resolve("dirZed"));

        // Introduce a non-directory entry
        Path docNonRootC = root.resolve("non-root.dat");
        FS.touch(docNonRootC);

        try (ResourceFactory.Closeable resourceFactory = ResourceFactory.closeable())
        {
            // Collection consisting of [file, dir]
            Resource resourceCollectionA = resourceFactory.newResource(List.of(docNonRootC.toUri(), docrootB.toUri()));
            // Basic resource, just a dir
            Resource basicResource = resourceFactory.newResource(docrootA);
            // New Collection consisting of [collection, dir]
            Resource resourceCollectionB = Resource.combine(resourceCollectionA, basicResource);

            // Use collection in generating the output
            String content = ResourceListing.getAsXHTML(resourceCollectionB, "/context/", false, null);
            assertTrue(isValidXHtml(content));

            assertThat(content, containsString("entry1.txt"));
            assertThat(content, containsString("<a href=\"/context/entry1.txt\">"));
            assertThat(content, containsString("entry2.dat"));
            assertThat(content, containsString("<a href=\"/context/entry2.dat\">"));
            assertThat(content, containsString("entry3.png"));
            assertThat(content, containsString("<a href=\"/context/entry3.png\">"));
            assertThat(content, containsString("entry4.tar.gz"));
            assertThat(content, containsString("<a href=\"/context/entry4.tar.gz\">"));
            assertThat(content, containsString("dirFoo/"));
            assertThat(content, containsString("<a href=\"/context/dirFoo/\">"));
            assertThat(content, containsString("dirBar/"));
            assertThat(content, containsString("<a href=\"/context/dirBar/\">"));
            assertThat(content, containsString("dirCid/"));
            assertThat(content, containsString("<a href=\"/context/dirCid/\">"));
            assertThat(content, containsString("dirZed/"));
            assertThat(content, containsString("<a href=\"/context/dirZed/\">"));
            assertThat(content, containsString("<a href=\"/context/non-root.dat\">"));
        }
    }

    private static boolean isValidXHtml(String content)
    {
        // we expect that our generated output conforms to text/xhtml is well formed
        try (ByteArrayInputStream inputStream = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)))
        {
            DocumentBuilderFactory xmlDocumentBuilderFactory = DocumentBuilderFactory.newInstance();
            xmlDocumentBuilderFactory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            DocumentBuilder db = xmlDocumentBuilderFactory.newDocumentBuilder();
            // We consider this content to be XML well formed if these 2 lines do not throw an Exception
            Document doc = db.parse(inputStream);
            doc.getDocumentElement().normalize();
            return true; // it's well-formed
        }
        catch (IOException | ParserConfigurationException | SAXException e)
        {
            e.printStackTrace(System.err);
            return false; // XHTML has got issues
        }
    }
}
