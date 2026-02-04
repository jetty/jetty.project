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

package org.eclipse.jetty.session.infinispan;

import java.io.IOException;
import java.io.UncheckedIOException;

import org.infinispan.protostream.FileDescriptorSource;
import org.infinispan.protostream.SerializationContext;
import org.infinispan.protostream.SerializationContextInitializer;

/**
 * Set up the marshaller for InfinispanSessionData serialization
 *
 */
public class InfinispanSerializationContextInitializer implements SerializationContextInitializer
{
    public String getProtoFileName()
    {
        return "session.proto";
    }

    @Override
    public void register(SerializationContext serCtx)
    {
        try
        {
            serCtx.registerProtoFiles(FileDescriptorSource.fromResources(getProtoFileName()));
        }
        catch (IOException e)
        {
            throw new UncheckedIOException(e);
        }
        SerializationContextInitializer.super.register(serCtx);
    }

    @Override
    public void registerSchema(SerializationContext serCtx)
    {
        try
        {
            serCtx.registerProtoFiles(FileDescriptorSource.fromResources(getProtoFileName()));
        }
        catch (IOException e)
        {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void registerMarshallers(SerializationContext serCtx)
    {
        serCtx.registerMarshaller(new SessionDataMarshaller());
    }
}
