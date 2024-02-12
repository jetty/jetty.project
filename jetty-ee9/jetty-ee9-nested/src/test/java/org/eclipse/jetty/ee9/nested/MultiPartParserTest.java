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

package org.eclipse.jetty.ee9.nested;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import jakarta.servlet.MultipartConfigElement;
import jakarta.servlet.http.Part;
import org.eclipse.jetty.http.MultiPartCompliance;
import org.eclipse.jetty.tests.multipart.MultiPartExpectations;
import org.eclipse.jetty.tests.multipart.MultiPartFormArgumentsProvider;
import org.eclipse.jetty.tests.multipart.MultiPartRaw;
import org.eclipse.jetty.tests.multipart.MultiPartResults;
import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.MavenPaths;
import org.eclipse.jetty.util.IO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

public class MultiPartParserTest
{
    private static final int MAX_FILE_SIZE = 1_500_000;
    private static final int MAX_REQUEST_SIZE = 2_000_000;
    private static final int FILE_SIZE_THRESHOLD = 2_500_000;
    private static final int MAX_PARTS = 1_000;
    private static File tempDir;

    @BeforeAll
    public static void initTempDir()
    {
        Path tempPath = MavenPaths.targetTestDir(MultiPartParserTest.class.getSimpleName() + "-temp");
        FS.ensureDirExists(tempPath);
        tempDir = tempPath.toFile();
    }

    @ParameterizedTest
    @ArgumentsSource(MultiPartFormArgumentsProvider.class)
    public void testMultiPartParserRFC7578(MultiPartRaw rawForm, Charset defaultCharset, MultiPartExpectations formExpectations) throws Exception
    {
        testMultiPartParser(rawForm, defaultCharset, formExpectations, MultiPartCompliance.RFC7578);
    }

    @ParameterizedTest
    @ArgumentsSource(MultiPartFormArgumentsProvider.class)
    public void testMultiPartParserLegacy(MultiPartRaw rawForm, Charset defaultCharset, MultiPartExpectations formExpectations) throws Exception
    {
        testMultiPartParser(rawForm, defaultCharset, formExpectations, MultiPartCompliance.LEGACY);
    }

    private void testMultiPartParser(MultiPartRaw rawForm, Charset defaultCharset, MultiPartExpectations formExpectations, MultiPartCompliance multiPartCompliance) throws Exception
    {
        String contentType = formExpectations.contentType;
        MultipartConfigElement config = new MultipartConfigElement(tempDir.toString(), MAX_FILE_SIZE, MAX_REQUEST_SIZE, FILE_SIZE_THRESHOLD);
        File contextTmpDir = tempDir;
        int maxParts = MAX_PARTS;

        try (InputStream inputStream = rawForm.asInputStream())
        {
            MultiPart.Parser multipartParser = MultiPart.newParser(multiPartCompliance, inputStream, contentType, config, contextTmpDir, maxParts);
            formExpectations.assertParts(mapActualResults(multipartParser.getParts()), defaultCharset);
        }
    }

    private MultiPartResults mapActualResults(Collection<Part> parts)
    {
        return new MultiPartResults()
        {
            @Override
            public int getCount()
            {
                return parts.size();
            }

            @Override
            public List<PartResult> get(String name)
            {
                List<PartResult> namedParts = new ArrayList<>();
                for (Part part: parts)
                {
                    if (part.getName().equalsIgnoreCase(name))
                    {
                        namedParts.add(new NamedPartResult(part));
                    }
                }

                return namedParts;
            }
        };
    }

    private class NamedPartResult implements MultiPartResults.PartResult
    {
        private final Part namedPart;

        public NamedPartResult(Part part)
        {
            this.namedPart = part;
        }

        @Override
        public String getContentType()
        {
            return namedPart.getContentType();
        }

        @Override
        public ByteBuffer asByteBuffer() throws IOException
        {
            try (InputStream inputStream = namedPart.getInputStream();
                 ByteArrayOutputStream baos = new ByteArrayOutputStream())
            {
                IO.copy(inputStream, baos);
                return ByteBuffer.wrap(baos.toByteArray());
            }
        }

        @Override
        public String asString(Charset charset) throws IOException
        {
            return IO.toString(namedPart.getInputStream(), charset);
        }

        @Override
        public String getFileName()
        {
            return namedPart.getSubmittedFileName();
        }

        @Override
        public InputStream asInputStream() throws IOException
        {
            return namedPart.getInputStream();
        }
    }
}
