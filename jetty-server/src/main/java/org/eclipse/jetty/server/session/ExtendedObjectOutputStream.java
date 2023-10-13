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

package org.eclipse.jetty.server.session;

import java.io.IOException;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.io.Serializable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ExtendedObjectOutputStream extends java.io.ObjectOutputStream
{

    private static final Logger LOG = LoggerFactory.getLogger(ExtendedObjectOutputStream.class);

    private NullOutputStream nos = new NullOutputStream();
    private ObjectOutputStream oos = new ObjectOutputStream(nos);

    private boolean _serializationLogSkipped;

    public ExtendedObjectOutputStream(OutputStream out, boolean serializationLogSkipped) throws IOException
    {
        super(out);
        enableReplaceObject(true);
        this._serializationLogSkipped = serializationLogSkipped;
    }

    @Override
    protected Object replaceObject(Object obj) throws IOException
    {
        try
        {
            oos.writeObject(obj);

            return obj;
        }
        catch (Throwable ex)
        {
            if (obj instanceof Serializable)
            {
                return obj;
            }

            if (_serializationLogSkipped)
            {
                LOG.warn("Skipping object <{}> serialization, class <{}>  ", obj, obj.getClass());
            }
        }

        return null;
    }

    public class NullOutputStream extends OutputStream
    {
        @Override
        public void write(int b) throws IOException {}
    }

}
