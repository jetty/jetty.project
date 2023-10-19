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
import java.util.Objects;

import org.eclipse.jetty.util.ClassLoadingObjectInputStream;

public abstract class ObjectStreamSessionDataStore extends AbstractSessionDataStore
{
    /**
     * Get an ObjectOutputStream suitable to serialize SessionData objects
     * into the provided OutputStream.
     * <br/>
     * By default, an ObjectObjectStream is returned.
     * <br/>
     * Override this method to provide a custom ObjectOutputStream, and/or to
     * chain other OutputStreams to perform such tasks as compressing the serialized
     * data, for example:
     * <br/>
     * <code>
     * GZIPOutputStream gos = new GZIPOutputStream(os);
     * return new ObjectOutputStream(gos);
     * </code>
     * @param os an output stream to which to serialize the session data
     * @return an ObjectOutputStream wrapping the OutputStream
     * @throws IOException if the stream cannot be created
     */
    public ObjectOutputStream newObjectOutputStream(OutputStream os) throws IOException
    {
        return new ObjectOutputStream(os);
    }

    /**
     * Get an ObjectInputStream that is capable of deserializing the session data
     * present in the provided InputStream.
     * <br/>
     * By default, a Classloader-aware ObjectInputStream is used, however, you
     * can return your own specialized ObjectInputStream, or chain other InputStreams
     * together to perform such tasks as data decompression, for example:
     * <br/>
     * <code>
     * GZIPInputStream gis = new GZIPInputStream(is);
     * return new ClassLoadingObjectInputStream(is)
     * </code>
     * @param is an input stream for accessing the session data to be deserialized
     * @return an ObjectInputStream that can deserialize the session data
     * @throws IOException if the stream cannot be created
     */
    protected ObjectInputStream newObjectInputStream(InputStream is) throws IOException
    {
        return new ClassLoadingObjectInputStream(is);
    }

    /**
     * Serialize the attribute map of the SessionData into the OutputStream provided.
     * @param data the SessionData whose attributes are to be serialized
     * @param os the OutputStream to receive the serialized attributes
     * @throws Exception if the attributes cannot be serialized
     */
    protected void serializeAttributes(SessionData data, OutputStream os) throws Exception
    {
        Objects.requireNonNull(data);
        Objects.requireNonNull(os);
        try (ObjectOutputStream oos = newObjectOutputStream(os))
        {
            SessionData.serializeAttributes(data, oos);
        }
    }

    /**
     * Deserialize the attribute map from the InputStream provided and store into the SessionData.
     * @param data the SessionData into which to deserialize the attributes
     * @param is the InputStream for reading the serialized attributes
     * @throws Exception if the attributes cannot be deserialized
     */
    protected void deserializeAttributes(SessionData data, InputStream is) throws Exception
    {
        Objects.requireNonNull(data);
        Objects.requireNonNull(is);
        try (ObjectInputStream ois = newObjectInputStream(is))
        {
            SessionData.deserializeAttributes(data, ois);
        }
    }
}
