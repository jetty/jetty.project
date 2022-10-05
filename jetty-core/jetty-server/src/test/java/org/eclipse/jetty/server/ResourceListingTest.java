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
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import javax.xml.catalog.Catalog;
import javax.xml.catalog.CatalogManager;
import javax.xml.catalog.CatalogResolver;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.xhtml.CatalogXHTML;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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
        FS.touch(docrootA.resolve("similar.txt"));
        Files.createDirectory(docrootA.resolve("dirSame"));
        Files.createDirectory(docrootA.resolve("dirFoo"));
        Files.createDirectory(docrootA.resolve("dirBar"));

        Path docrootB = root.resolve("docrootB");
        Files.createDirectory(docrootB);
        FS.touch(docrootB.resolve("entry3.png"));
        FS.touch(docrootB.resolve("entry4.tar.gz"));
        FS.touch(docrootB.resolve("similar.txt")); // same filename as in docrootA
        Files.createDirectory(docrootB.resolve("dirSame")); // same directory name as in docrootA
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

            int count;

            // how many dirSame links do we have?
            count = content.split(Pattern.quote("<a href=\"/context/dirSame/\">"), -1).length - 1;
            assertThat(count, is(1));

            // how many similar.txt do we have?
            count = content.split(Pattern.quote("<a href=\"/context/similar.txt\">"), -1).length - 1;
            assertThat(count, is(1));
        }
    }

    /**
     * A regression on Windows allowed the directory listing show
     * the fully qualified paths within the directory listing.
     * This test ensures that this behavior will not arise again.
     */
    @Test
    public void testListingFilenamesOnly(WorkDir workDir) throws Exception
    {
        Path docRoot = workDir.getEmptyPathDir();

        /* create some content in the docroot */
        FS.ensureDirExists(docRoot);
        Path one = docRoot.resolve("one");
        FS.ensureDirExists(one);
        Path deep = one.resolve("deep");
        FS.ensureDirExists(deep);
        FS.touch(deep.resolve("foo"));
        FS.ensureDirExists(docRoot.resolve("two"));
        FS.ensureDirExists(docRoot.resolve("three"));

        try (ResourceFactory.Closeable resourceFactory = ResourceFactory.closeable())
        {
            Resource resourceBase = resourceFactory.newResource(docRoot);
            Resource resource = resourceBase.resolve("one/deep/");

            String content = ResourceListing.getAsXHTML(resource, "/context/", false, null);
            assertTrue(isValidXHtml(content));

            assertThat(content, containsString("/foo"));

            String resBasePath = docRoot.toAbsolutePath().toString();
            assertThat(content, not(containsString(resBasePath)));
        }
    }

    @Test
    public void testListingProperUrlEncoding(WorkDir workDir) throws Exception
    {
        Path docRoot = workDir.getEmptyPathDir();
        /* create some content in the docroot */

        Path wackyDir = docRoot.resolve("dir;"); // this should not be double-encoded.
        FS.ensureDirExists(wackyDir);

        FS.ensureDirExists(wackyDir.resolve("four"));
        FS.ensureDirExists(wackyDir.resolve("five"));
        FS.ensureDirExists(wackyDir.resolve("six"));

        /* At this point we have the following
         * testListingProperUrlEncoding/
         * `-- docroot
         *     `-- dir;
         *         |-- five
         *         |-- four
         *         `-- six
         */

        try (ResourceFactory.Closeable resourceFactory = ResourceFactory.closeable())
        {
            Resource resourceBase = resourceFactory.newResource(docRoot);

            // Resolve directory
            Resource resource = resourceBase.resolve("dir%3B");

            // Context
            String content = ResourceListing.getAsXHTML(resource, "/context/dir%3B/", false, null);
            assertTrue(isValidXHtml(content));

            // Should not see double-encoded ";"
            // First encoding: ";" -> "%3B"
            // Second encoding: "%3B" -> "%253B" (BAD!)
            assertThat(content, not(containsString("%253B")));

            assertThat(content, containsString("/dir%3B/"));
            assertThat(content, containsString("/dir%3B/four/"));
            assertThat(content, containsString("/dir%3B/five/"));
            assertThat(content, containsString("/dir%3B/six/"));
        }
    }

    @Test
    public void testListingWithQuestionMarks(WorkDir workDir) throws Exception
    {
        Path docRoot = workDir.getEmptyPathDir();

        /* create some content in the docroot */
        FS.ensureDirExists(docRoot.resolve("one"));
        FS.ensureDirExists(docRoot.resolve("two"));
        FS.ensureDirExists(docRoot.resolve("three"));

        // Creating dir 'f??r' (Might not work in Windows)
        assumeMkDirSupported(docRoot, "f??r");

        try (ResourceFactory.Closeable resourceFactory = ResourceFactory.closeable())
        {
            Resource resource = resourceFactory.newResource(docRoot);

            String content = ResourceListing.getAsXHTML(resource, "/context/", false, null);
            assertTrue(isValidXHtml(content));

            assertThat(content, containsString("f??r"));
        }
    }

    @Test
    public void testListingEncoding(WorkDir workDir) throws Exception
    {
        Path docRoot = workDir.getEmptyPathDir();

        /* create some content in the docroot */
        Path one = docRoot.resolve("one");
        FS.ensureDirExists(one);

        // example of content on disk that could cause problems when taken to the HTML space.
        Path alert = one.resolve("onmouseclick='alert(oops)'");
        FS.touch(alert);

        try (ResourceFactory.Closeable resourceFactory = ResourceFactory.closeable())
        {
            Resource resourceBase = resourceFactory.newResource(docRoot);
            Resource resource = resourceBase.resolve("one");

            String content = ResourceListing.getAsXHTML(resource, "/context/one", false, null);
            assertTrue(isValidXHtml(content));

            // Entry should be properly encoded
            assertThat(content, containsString("<a href=\"/context/one/onmouseclick=%27alert(oops)%27\">"));
        }
    }

    private static boolean isValidXHtml(String content)
    {
        // we expect that our generated output conforms to text/xhtml is well-formed
        try (ByteArrayInputStream inputStream = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)))
        {
            Catalog catalog = CatalogXHTML.getCatalog();
            CatalogResolver resolver = CatalogManager.catalogResolver(catalog);

            DocumentBuilderFactory xmlDocumentBuilderFactory = DocumentBuilderFactory.newInstance();
            xmlDocumentBuilderFactory.setValidating(true);
            DocumentBuilder db = xmlDocumentBuilderFactory.newDocumentBuilder();
            db.setEntityResolver(resolver);
            List<SAXParseException> errors = new ArrayList<>();
            db.setErrorHandler(new ErrorHandler()
            {
                @Override
                public void warning(SAXParseException exception)
                {
                    exception.printStackTrace();
                }

                @Override
                public void error(SAXParseException exception)
                {
                    errors.add(exception);
                }

                @Override
                public void fatalError(SAXParseException exception)
                {
                    errors.add(exception);
                }
            });

            // We consider this content to be XML well-formed if these 2 lines do not throw an Exception
            Document doc = db.parse(inputStream);
            doc.getDocumentElement().normalize();

            if (errors.size() > 0)
            {
                IOException ioException = new IOException("Failed to validate XHTML");
                for (SAXException saxException : errors)
                {
                    ioException.addSuppressed(saxException);
                }
                fail(ioException);
            }

            return true; // it's well-formed
        }
        catch (IOException | ParserConfigurationException | SAXException e)
        {
            e.printStackTrace(System.err);
            return false; // XHTML has got issues
        }
    }

    /**
     * Attempt to create the directory, skip testcase if not supported on OS.
     */
    private static Path assumeMkDirSupported(Path path, String subpath)
    {
        Path ret = null;

        try
        {
            ret = path.resolve(subpath);

            if (Files.exists(ret))
                return ret;

            Files.createDirectories(ret);
        }
        catch (InvalidPathException | IOException ignore)
        {
            // ignore
        }

        assumeTrue(ret != null, "Directory creation not supported on OS: " + path + File.separator + subpath);
        assumeTrue(Files.exists(ret), "Directory creation not supported on OS: " + ret);

        return ret;
    }
}
