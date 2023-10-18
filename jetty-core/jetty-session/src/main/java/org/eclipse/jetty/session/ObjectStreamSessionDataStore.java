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

package org.eclipse.jetty.session;

import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;

import org.eclipse.jetty.util.ClassLoadingObjectInputStream;

public abstract class ObjectStreamSessionDataStore extends AbstractSessionDataStore
{
    /**
     * Get an ObjectOutputStream suitable to serialize SessionData objects
     * into the provided OutputStream.
     *
     * By default, an ObjectObjectStream is returned.
     *
     * Override this method to provide a custom ObjectOutputStream, and/or to
     * chain other OutputStreams to perform such tasks as compressing the serialized
     * data, for example:
     *
     * GZIPOutputStream gos = new GZIPOutputStream(os);
     * return new ObjectOutputStream(gos);
     *
     * @param os
     * @return
     * @throws IOException
     */
    public ObjectOutputStream newObjectOutputStream(OutputStream os) throws IOException
    {
        return new ObjectOutputStream(os);
    }

    /**
     * Get an ObjectInputStream that is capable of deserializing the session data
     * present in the provided InputStream.
     *
     * By default, a Classloader-aware ObjectInputStream is used, however, you
     * can return your own specialized ObjectInputStream, or chain other InputStreams
     * together to perform such tasks as data decompression, for example:
     *
     * GZIPInputStream gis = new GZIPInputStream(is);
     * return new ClassLoadingObjectInputStream(is)
     *
     * @param is an input stream for accessing the session data to be deserialized
     * @return an ObjectInputStream that can deserialize the session data
     * @throws IOException
     */
    protected ObjectInputStream newObjectInputStream(InputStream is) throws IOException
    {
        return new ClassLoadingObjectInputStream(is);
    }

    protected void serializeAttributes(SessionData data, OutputStream os) throws Exception
    {
        try (ObjectOutputStream oos = newObjectOutputStream(os))
        {
            SessionData.serializeAttributes(data, oos);
        }
    }

    protected void deserializeAttributes(SessionData data, InputStream is) throws Exception
    {
        try (ObjectInputStream ois = newObjectInputStream(is))
        {
            SessionData.deserializeAttributes(data, ois);
        }
    }
}
