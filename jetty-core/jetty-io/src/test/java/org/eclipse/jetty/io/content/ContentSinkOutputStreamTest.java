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
import java.io.PrintWriter;
import java.io.StringWriter;

import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.EofException;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.fail;

public class ContentSinkOutputStreamTest
{
    @Test
    public void testNoDuplicateExceptionThrown()
    {
        EofException eofException = new EofException();
        Content.Sink sink = (last, byteBuffer, callback) -> callback.failed(eofException);

        StringWriter stringWriter = new StringWriter();
        try (OutputStream body = new ContentSinkOutputStream(sink))
        {
            try
            {
                body.write('a');
                fail("expected IOException in write()");
            }
            catch (IOException e)
            {
                body.flush();
                fail("expected IOException in flush()");
            }
        }
        catch (IOException ex)
        {
            ex.printStackTrace(new PrintWriter(stringWriter));
        }
        assertThat(stringWriter.toString(), not(containsString("CIRCULAR REFERENCE")));
    }
}
