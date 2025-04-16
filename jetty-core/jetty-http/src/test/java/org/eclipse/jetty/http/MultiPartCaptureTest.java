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

package org.eclipse.jetty.http;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.content.ByteBufferContentSource;
import org.eclipse.jetty.tests.multipart.MultiPartExpectations;
import org.eclipse.jetty.tests.multipart.MultiPartFormArgumentsProvider;
import org.eclipse.jetty.tests.multipart.MultiPartRequest;
import org.eclipse.jetty.tests.multipart.MultiPartResults;
import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.MavenPaths;
import org.eclipse.jetty.util.BufferUtil;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

public class MultiPartCaptureTest
{
    @ParameterizedTest
    @ArgumentsSource(MultiPartFormArgumentsProvider.class)
    public void testMultipartCapture(MultiPartRequest formRequest, Charset defaultCharset, MultiPartExpectations formExpectations) throws Exception
    {
        String boundary = MultiPart.extractBoundary(formExpectations.getContentType());
        TestPartsListener listener = new TestPartsListener();
        MultiPart.Parser parser = new MultiPart.Parser(boundary, listener);
        ByteBuffer rawByteBuffer = formRequest.asByteBuffer();
        parser.parse(Content.Chunk.from(rawByteBuffer, true));
        formExpectations.assertParts(mapActualResults(listener.parts), defaultCharset);
    }

    @ParameterizedTest
    @ArgumentsSource(MultiPartFormArgumentsProvider.class)
    public void testMultiPartFormDataParse(MultiPartRequest formRequest, Charset defaultCharset, MultiPartExpectations formExpectations) throws Exception
    {
        String boundary = MultiPart.extractBoundary(formExpectations.getContentType());
        Path tempDir = MavenPaths.targetTestDir(MultiPartCaptureTest.class.getSimpleName() + "-temp");
        FS.ensureDirExists(tempDir);

        MultiPartFormData.Parser parser = new MultiPartFormData.Parser(boundary);
        parser.setUseFilesForPartsWithoutFileName(true);
        parser.setFilesDirectory(tempDir);
        ByteBufferContentSource contentSource = new ByteBufferContentSource(formRequest.asByteBuffer());
        MultiPartFormData.Parts parts = parser.parse(contentSource).get();
        formExpectations.assertParts(mapActualResults(parts), defaultCharset);
    }

    private MultiPartResults mapActualResults(final MultiPartFormData.Parts parts)
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
                List<MultiPart.Part> namedParts = parts.getAll(name);

                if (namedParts == null)
                    return null;

                List<PartResult> results = new ArrayList<>();
                for (MultiPart.Part namedPart : namedParts)
                {
                    results.add(new NamedPartResult(namedPart));
                }
                return results;
            }
        };
    }

    private MultiPartResults mapActualResults(final Map<String, List<MultiPart.Part>> parts)
    {
        return new MultiPartResults()
        {
            @Override
            public int getCount()
            {
                return parts.values().stream().mapToInt(List::size).sum();
            }

            @Override
            public List<PartResult> get(String name)
            {
                List<MultiPart.Part> namedParts = parts.get(name);

                if (namedParts == null)
                    return null;

                List<PartResult> results = new ArrayList<>();
                for (MultiPart.Part namedPart : namedParts)
                {
                    results.add(new NamedPartResult(namedPart));
                }
                return results;
            }
        };
    }

    public static class NamedPartResult implements MultiPartResults.PartResult
    {
        private final MultiPart.Part namedPart;

        public NamedPartResult(MultiPart.Part namedPart)
        {
            this.namedPart = namedPart;
        }

        @Override
        public String getContentType()
        {
            return namedPart.getHeaders().get(HttpHeader.CONTENT_TYPE);
        }

        @Override
        public ByteBuffer asByteBuffer() throws IOException
        {
            return Content.Source.asByteBuffer(namedPart.newContentSource(null, 0, -1));
        }

        @Override
        public String asString(Charset charset) throws IOException
        {
            if (charset == null)
                return Content.Source.asString(namedPart.newContentSource(null, 0, -1));
            else
                return Content.Source.asString(namedPart.newContentSource(null, 0, -1), charset);
        }

        @Override
        public String getFileName()
        {
            return namedPart.getFileName();
        }

        @Override
        public InputStream asInputStream()
        {
            return Content.Source.asInputStream(namedPart.newContentSource(null, 0, -1));
        }
    }

    private static class TestPartsListener extends MultiPart.AbstractPartsListener
    {
        // Preserve parts order.
        private final Map<String, List<MultiPart.Part>> parts = new LinkedHashMap<>();
        private final List<ByteBuffer> partByteBuffers = new ArrayList<>();

        @Override
        public void onPartContent(Content.Chunk chunk)
        {
            // Copy the part content, as we need to iterate over it multiple times.
            partByteBuffers.add(BufferUtil.copy(chunk.getByteBuffer()));
        }

        @Override
        public void onPart(String name, String fileName, HttpFields headers)
        {
            List<ByteBuffer> copyOfByteBuffers = new ArrayList<>();
            for (ByteBuffer capture: partByteBuffers)
            {
                copyOfByteBuffers.add(BufferUtil.copy(capture));
            }
            MultiPart.Part newPart = new MultiPart.ByteBufferPart(name, fileName, headers, copyOfByteBuffers);
            partByteBuffers.clear();
            parts.compute(newPart.getName(), (k, v) -> v == null ? new ArrayList<>() : v).add(newPart);
        }
    }
}
