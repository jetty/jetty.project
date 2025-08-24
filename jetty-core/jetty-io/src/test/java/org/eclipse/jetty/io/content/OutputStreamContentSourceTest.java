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

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;

import org.eclipse.jetty.io.EofException;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

public class OutputStreamContentSourceTest
{
    @Test
    public void testNoDuplicateExceptionThrown() throws Exception
    {
        EofException cause = new EofException();
        IOException failure = assertThrows(IOException.class, () ->
        {
            try (OutputStreamContentSource source = new OutputStreamContentSource())
            {
                source.fail(cause);

                try (BufferedOutputStream buffered = new BufferedOutputStream(source.getOutputStream()))
                {
                    buffered.write(new byte[16]);
                    // Flushing the first write causes the failure to be thrown.
                    buffered.flush();
                    fail("expecting IOException to be thrown");
                }
                // Here BufferedOutputStream.close() is called, which calls flush() again.
                // This second flush also throws, and we must guarantee that it is not
                // the same exception instance, or otherwise the try-with-resources
                // fails with IllegalArgumentException: Self-suppression not permitted.
            }
        });

        assertThat(failure, not(sameInstance(cause)));

        StringWriter stringWriter = new StringWriter();
        failure.printStackTrace(new PrintWriter(stringWriter));
        assertThat(stringWriter.toString(), not(containsString("CIRCULAR REFERENCE")));
    }
}
