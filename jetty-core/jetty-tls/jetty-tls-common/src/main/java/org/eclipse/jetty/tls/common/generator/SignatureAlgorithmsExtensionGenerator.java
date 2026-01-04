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

package org.eclipse.jetty.tls.common.generator;

import java.util.List;

import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.tls.SignatureAlgorithm;
import org.eclipse.jetty.tls.ext.Extension;
import org.eclipse.jetty.tls.ext.SignatureAlgorithmsExtension;

public class SignatureAlgorithmsExtensionGenerator implements ExtensionGenerator
{
    @Override
    public int type()
    {
        return SignatureAlgorithmsExtension.CODE;
    }

    @Override
    public int generate(RetainableByteBuffer.Mutable accumulator, Extension extension)
    {
        return generate(accumulator, (SignatureAlgorithmsExtension)extension);
    }

    private int generate(RetainableByteBuffer.Mutable accumulator, SignatureAlgorithmsExtension extension)
    {
        accumulator.putShort((short)extension.code());
        List<SignatureAlgorithm> algorithms = extension.signatureAlgorithms();
        int listLength = 2 * algorithms.size();
        int totalLength = 2 + listLength;
        accumulator.putShort((short)totalLength);
        accumulator.putShort((short)listLength);
        for (SignatureAlgorithm algorithm : algorithms)
        {
            accumulator.putShort((short)algorithm.code());
        }
        return 2 + 2 + totalLength;
    }
}
