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
import org.eclipse.jetty.tls.ext.ClientPreSharedKeyExtension;
import org.eclipse.jetty.tls.ext.Extension;
import org.eclipse.jetty.tls.ext.PreSharedKeyIdentity;

public class ClientPreSharedKeyExtensionGenerator implements ExtensionGenerator
{
    @Override
    public int type()
    {
        return ClientPreSharedKeyExtension.CODE;
    }

    @Override
    public int generate(RetainableByteBuffer.Mutable accumulator, Extension extension)
    {
        return generate(accumulator, (ClientPreSharedKeyExtension)extension);
    }

    private int generate(RetainableByteBuffer.Mutable accumulator, ClientPreSharedKeyExtension extension)
    {
        accumulator.putShort((short)extension.code());
        List<PreSharedKeyIdentity> identities = extension.identities();
        List<byte[]> binders = extension.binders();
        // Identities length + binders length.
        int totalLength = 2 + 2;
        int identitiesLength = 0;
        int bindersLength = 0;
        for (int i = 0; i < identities.size(); ++i)
        {
            PreSharedKeyIdentity identity = identities.get(i);
            int identityLength = 2 + identity.identity().length + 4;
            identitiesLength += identityLength;
            int binderLength = 1 + binders.get(i).length;
            bindersLength += binderLength;
            totalLength += identityLength + binderLength;
        }
        if (totalLength > 0xFFFF)
            throw new IllegalStateException("invalid pre-shared key extension length " + totalLength);
        accumulator.putShort((short)totalLength);
        accumulator.putShort((short)identitiesLength);
        for (PreSharedKeyIdentity identity : identities)
        {
            accumulator.putShort((short)identity.identity().length);
            accumulator.put(identity.identity());
            accumulator.putInt(identity.obfuscatedTicketAge());
        }
        accumulator.putShort((short)bindersLength);
        for (byte[] binder : binders)
        {
            accumulator.put((byte)binder.length);
            accumulator.put(binder);
        }
        return 2 + 2 + totalLength;
    }
}
