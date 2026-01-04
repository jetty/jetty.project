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

package org.eclipse.jetty.tls.common.parser;

import java.nio.ByteBuffer;
import java.util.List;

import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.tls.EncryptedExtensionsMessage;
import org.eclipse.jetty.tls.Message;
import org.eclipse.jetty.tls.ext.Extension;

public class EncryptedExtensionsMessageParser implements MessageParser
{
    private final ExtensionsParser extensionsParser;

    public EncryptedExtensionsMessageParser(ExtensionsParser extensionsParser)
    {
        this.extensionsParser = extensionsParser;
    }

    @Override
    public Message parse(RetainableByteBuffer buffer)
    {
        ByteBuffer byteBuffer = buffer.getByteBuffer();
        int remaining = byteBuffer.remaining();
        if (remaining == 0)
            return null;
        List<Extension> extensions = extensionsParser.parse(buffer);
        if (extensions == null)
            return null;
        return new EncryptedExtensionsMessage(extensions);
    }
}
