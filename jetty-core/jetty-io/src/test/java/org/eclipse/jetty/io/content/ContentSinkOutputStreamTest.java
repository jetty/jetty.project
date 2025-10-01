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

package org.eclipse.jetty.io.content;

import java.io.IOException;
import java.io.OutputStream;

import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.EofException;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

public class ContentSinkOutputStreamTest
{
    @Test
    public void testNoDuplicateExceptionThrown()
    {
        EofException eofException = new EofException();
        Content.Sink sink = (last, byteBuffer, callback) -> callback.failed(eofException);
        IOException failure = assertThrows(IOException.class, () ->
        {
            try (OutputStream body = new ContentSinkOutputStream(sink))
            {
                body.write('a');
                fail("expected IOException in write()");
            }
            // Here ContentSinkOutputStream.close() is called, which calls write() again.
            // This second write() also throws, and we must guarantee that it is not
            // the same exception instance, or otherwise the try-with-resources
            // fails with IllegalArgumentException: Self-suppression not permitted.
        });
        assertThat(failure, sameInstance(eofException));
    }
}
