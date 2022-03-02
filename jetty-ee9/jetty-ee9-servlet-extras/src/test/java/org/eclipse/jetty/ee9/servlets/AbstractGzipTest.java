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

package org.eclipse.jetty.ee9.servlets;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.zip.GZIPInputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.handler.gzip.GzipHandler;
import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.IO;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.TypeUtil;

import static java.nio.charset.StandardCharsets.UTF_8;

public abstract class AbstractGzipTest
{
    protected static final int DEFAULT_OUTPUT_BUFFER_SIZE = new HttpConfiguration().getOutputBufferSize();

    protected Path workDir;

    public AbstractGzipTest()
    {
        workDir = MavenTestingUtils.getTargetTestingPath(this.getClass().getName());
        FS.ensureEmpty(workDir);
    }

    protected FilterInputStream newContentEncodingFilterInputStream(String contentEncoding, InputStream inputStream) throws IOException
    {
        if (contentEncoding == null)
        {
            return new FilterInputStream(inputStream) {};
        }
        else if (contentEncoding.contains(GzipHandler.GZIP))
        {
            return new GZIPInputStream(inputStream);
        }
        else if (contentEncoding.contains(GzipHandler.DEFLATE))
        {
            return new InflaterInputStream(inputStream, new Inflater(true));
        }
        throw new RuntimeException("Unexpected response content-encoding: " + contentEncoding);
    }

    protected UncompressedMetadata parseResponseContent(HttpTester.Response response) throws NoSuchAlgorithmException, IOException
    {
        UncompressedMetadata metadata = new UncompressedMetadata();
        metadata.contentLength = response.getContentBytes().length;

        String contentEncoding = response.get("Content-Encoding");
        MessageDigest digest = MessageDigest.getInstance("SHA1");

        try (ByteArrayInputStream bais = new ByteArrayInputStream(response.getContentBytes());
             FilterInputStream streamFilter = newContentEncodingFilterInputStream(contentEncoding, bais);
             ByteArrayOutputStream uncompressedStream = new ByteArrayOutputStream(metadata.contentLength);
             DigestOutputStream digester = new DigestOutputStream(uncompressedStream, digest))
        {
            IO.copy(streamFilter, digester);
            metadata.uncompressedContent = uncompressedStream.toByteArray();
            metadata.uncompressedSize = metadata.uncompressedContent.length;
            // Odd toUpperCase is because TypeUtil.toHexString is mixed case results!??
            metadata.uncompressedSha1Sum = TypeUtil.toHexString(digest.digest()).toUpperCase(Locale.ENGLISH);
            return metadata;
        }
    }

    protected Path createFile(Path contextDir, String fileName, int fileSize) throws IOException
    {
        Path destPath = contextDir.resolve(fileName);
        byte[] content = generateContent(fileSize);
        Files.write(destPath, content, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        return destPath;
    }

    /**
     * Generate semi-realistic text content of arbitrary length.
     * <p>
     * Note: We don't just create a single string of repeating characters
     * as that doesn't test the gzip behavior very well. (too efficient)
     * We also don't just generate a random byte array as that is the opposite
     * extreme of gzip handling (terribly inefficient).
     * </p>
     *
     * @param length the length of the content to generate.
     * @return the content.
     */
    private byte[] generateContent(int length)
    {
        StringBuilder builder = new StringBuilder();
        do
        {
            builder.append("Lorem ipsum dolor sit amet, consectetur adipiscing elit. In quis felis nunc.\n");
            builder.append("Quisque suscipit mauris et ante auctor ornare rhoncus lacus aliquet. Pellentesque\n");
            builder.append("habitant morbi tristique senectus et netus et malesuada fames ac turpis egestas.\n");
            builder.append("Vestibulum sit amet felis augue, vel convallis dolor. Cras accumsan vehicula diam\n");
            builder.append("at faucibus. Etiam in urna turpis, sed congue mi. Morbi et lorem eros. Donec vulputate\n");
            builder.append("velit in risus suscipit lobortis. Aliquam id urna orci, nec sollicitudin ipsum.\n");
            builder.append("Cras a orci turpis. Donec suscipit vulputate cursus. Mauris nunc tellus, fermentum\n");
            builder.append("eu auctor ut, mollis at diam. Quisque porttitor ultrices metus, vitae tincidunt massa\n");
            builder.append("sollicitudin a. Vivamus porttitor libero eget purus hendrerit cursus. Integer aliquam\n");
            builder.append("consequat mauris quis luctus. Cras enim nibh, dignissim eu faucibus ac, mollis nec neque.\n");
            builder.append("Aliquam purus mauris, consectetur nec convallis lacinia, porta sed ante. Suspendisse\n");
            builder.append("et cursus magna. Donec orci enim, molestie a lobortis eu, imperdiet vitae neque.\n");
        }
        while (builder.length() < length);

        // Make sure we are exactly at requested length. (truncate the extra)
        if (builder.length() > length)
        {
            builder.setLength(length);
        }

        return builder.toString().getBytes(UTF_8);
    }

    public static class UncompressedMetadata
    {
        public byte[] uncompressedContent;
        public int contentLength;
        public String uncompressedSha1Sum;
        public int uncompressedSize;

        public String getContentUTF8()
        {
            return new String(uncompressedContent, UTF_8);
        }
    }
}
