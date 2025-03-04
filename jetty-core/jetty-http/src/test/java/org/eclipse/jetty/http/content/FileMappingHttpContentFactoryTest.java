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

package org.eclipse.jetty.http.content;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.eclipse.jetty.util.Blocker;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

@ExtendWith(WorkDirExtension.class)
public class FileMappingHttpContentFactoryTest
{
    public WorkDir workDir;

    @Test
    public void testMultiBufferFileMappedOffsetAndLength() throws Exception
    {
        Path file = Files.writeString(workDir.getEmptyPathDir().resolve("file.txt"), "0123456789abcdefghijABCDEFGHIJ");
        FileMappingHttpContentFactory fileMappingHttpContentFactory = new FileMappingHttpContentFactory(
            new ResourceHttpContentFactory(ResourceFactory.root().newResource(file.getParent()), MimeTypes.DEFAULTS, ByteBufferPool.SIZED_NON_POOLING),
            0, 10);

        HttpContent content = fileMappingHttpContentFactory.getContent("file.txt");

        assertThrows(IllegalArgumentException.class, () -> writeToString(content, 0, 31));
        assertThrows(IllegalArgumentException.class, () -> writeToString(content, 30, 1));
        assertThrows(IllegalArgumentException.class, () -> writeToString(content, 31, 0));

        assertThat(writeToString(content, 0, 30), is("0123456789abcdefghijABCDEFGHIJ"));
        assertThat(writeToString(content, 29, 1), is("J"));
        assertThat(writeToString(content, 0, 0), is(""));
        assertThat(writeToString(content, 10, 0), is(""));
        assertThat(writeToString(content, 15, 0), is(""));
        assertThat(writeToString(content, 20, 0), is(""));
        assertThat(writeToString(content, 30, 0), is(""));
        assertThat(writeToString(content, 1, 28), is("123456789abcdefghijABCDEFGHI"));

        assertThat(writeToString(content, 0, 10), is("0123456789"));
        assertThat(writeToString(content, 10, 10), is("abcdefghij"));
        assertThat(writeToString(content, 20, 10), is("ABCDEFGHIJ"));
        assertThat(writeToString(content, 5, 10), is("56789abcde"));
        assertThat(writeToString(content, 15, 10), is("fghijABCDE"));
        assertThat(writeToString(content, 25, 5), is("FGHIJ"));

        assertThat(writeToString(content, 0, -1), is("0123456789abcdefghijABCDEFGHIJ"));
        assertThat(writeToString(content, 5, -1), is("56789abcdefghijABCDEFGHIJ"));
        assertThat(writeToString(content, 10, -1), is("abcdefghijABCDEFGHIJ"));
        assertThat(writeToString(content, 15, -1), is("fghijABCDEFGHIJ"));
        assertThat(writeToString(content, 20, -1), is("ABCDEFGHIJ"));
        assertThat(writeToString(content, 25, -1), is("FGHIJ"));
    }

    @ParameterizedTest
    @ValueSource(ints = {8, 10})
    public void testMultiBufferFileMappedMaxBufferSizeRounding(int maxBufferSize) throws Exception
    {
        Path file = Files.writeString(workDir.getEmptyPathDir().resolve("file.txt"), "0123456789abcdefghijABCDEFGHIJ");
        FileMappingHttpContentFactory fileMappingHttpContentFactory = new FileMappingHttpContentFactory(
            new ResourceHttpContentFactory(ResourceFactory.root().newResource(file.getParent()), MimeTypes.DEFAULTS, ByteBufferPool.SIZED_NON_POOLING),
            0, maxBufferSize);

        HttpContent content = fileMappingHttpContentFactory.getContent("file.txt");

        assertThat(content.getContentLength().getValue(), is("30"));
        assertThat(content.getContentLengthValue(), is(30L));
        assertThat(writeToString(content, 0, -1), is("0123456789abcdefghijABCDEFGHIJ"));
    }

    private static String writeToString(HttpContent content, long offset, long length) throws IOException
    {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (Blocker.Callback cb = Blocker.callback())
        {
            content.writeTo(Content.Sink.from(baos), offset, length, cb);
            cb.block();
        }
        return baos.toString(StandardCharsets.UTF_8);
    }
}
