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

package org.eclipse.jetty.session.infinispan;

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
    @Override
    public String getProtoFileName()
    {
        return "session.proto";
    }

    @Override
    public String getProtoFile() throws UncheckedIOException
    {
        return FileDescriptorSource.getResourceAsString(getClass(), "/" + getProtoFileName());
    }

    @Override
    public void registerSchema(SerializationContext serCtx)
    {
        serCtx.registerProtoFiles(FileDescriptorSource.fromString(getProtoFileName(), getProtoFile()));
    }

    @Override
    public void registerMarshallers(SerializationContext serCtx)
    {
        serCtx.registerMarshaller(new SessionDataMarshaller());
    }
}
