//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.http3.qpack;

import java.io.ByteArrayOutputStream;

import org.eclipse.jetty.util.TypeUtil;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalToIgnoringCase;

public class DecoderStreamTest
{
    private final ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
    private final DecoderStream decoderStream = new DecoderStream(outputStream);

    @Test
    public void test() throws Exception
    {
        decoderStream.sendSectionAcknowledgment(4);
        assertThat(TypeUtil.toHexString(outputStream.toByteArray()), equalToIgnoringCase("84"));
        outputStream.reset();

        decoderStream.sendSectionAcknowledgment(1337);
        assertThat(TypeUtil.toHexString(outputStream.toByteArray()), equalToIgnoringCase("FFBA09"));
        outputStream.reset();
    }
}
