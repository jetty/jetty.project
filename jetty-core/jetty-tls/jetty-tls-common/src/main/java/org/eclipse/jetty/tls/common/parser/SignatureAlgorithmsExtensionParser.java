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

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.tls.SignatureAlgorithm;
import org.eclipse.jetty.tls.ext.SignatureAlgorithmsExtension;
import org.eclipse.jetty.util.buffer.RetainableByteBuffer;

public class SignatureAlgorithmsExtensionParser implements ExtensionParser
{
    private final List<SignatureAlgorithm> algorithms = new ArrayList<>();
    private final ExtensionParser.Listener listener;
    private State state = State.TOTAL_LENGTH;
    private int totalLength;
    private int listLength;
    private int algorithm;
    private int cursor;

    public SignatureAlgorithmsExtensionParser(Listener listener)
    {
        this.listener = listener;
    }

    @Override
    public int type()
    {
        return SignatureAlgorithmsExtension.CODE;
    }

    @Override
    public int parse(RetainableByteBuffer buffer)
    {
        while (true)
        {
            int remaining = Math.toIntExact(buffer.remaining());
            if (remaining == 0)
                return -1;
            switch (state)
            {
                case TOTAL_LENGTH ->
                {
                    if (remaining > 1)
                    {
                        totalLength = buffer.getShortAsInt();
                        if (totalLength < 4)
                            throw new IllegalStateException("invalid signature algorithms extension length " + totalLength);
                        state = State.LIST_LENGTH;
                    }
                    else
                    {
                        cursor = 2;
                        state = State.TOTAL_LENGTH_BYTES;
                    }
                }
                case TOTAL_LENGTH_BYTES ->
                {
                    int b = buffer.getByteAsInt();
                    --cursor;
                    totalLength += b << (8 * cursor);
                    if (cursor == 0)
                    {
                        if (totalLength < 4)
                            throw new IllegalStateException("invalid signature algorithms extension length " + totalLength);
                        state = State.LIST_LENGTH;
                    }
                }
                case LIST_LENGTH ->
                {
                    if (remaining > 1)
                    {
                        listLength = buffer.getShortAsInt();
                        if (listLength == 0 || listLength % 2 != 0)
                            throw new IllegalStateException("invalid signature algorithms list length " + listLength);
                        state = State.ALGORITHM;
                    }
                    else
                    {
                        cursor = 2;
                        state = State.LIST_LENGTH_BYTES;
                    }
                }
                case LIST_LENGTH_BYTES ->
                {
                    int b = buffer.getByteAsInt();
                    --cursor;
                    listLength += b << (8 * cursor);
                    if (cursor == 0)
                    {
                        if (listLength == 0 || listLength % 2 != 0)
                            throw new IllegalStateException("invalid signature algorithms list length " + listLength);
                        state = State.ALGORITHM;
                    }
                }
                case ALGORITHM ->
                {
                    if (remaining > 1)
                    {
                        algorithm = buffer.getShortAsInt();
                        listLength -= 2;
                        int result = algorithmComplete();
                        if (result > 0)
                            return result;
                    }
                    else
                    {
                        cursor = 2;
                        state = State.ALGORITHM_BYTES;
                    }
                }
                case ALGORITHM_BYTES ->
                {
                    int b = buffer.getByteAsInt();
                    --cursor;
                    algorithm += b << (8 * cursor);
                    if (cursor == 0)
                    {
                        listLength -= 2;
                        int result = algorithmComplete();
                        if (result > 0)
                            return result;
                    }
                }
            }
        }
    }

    private int algorithmComplete()
    {
        SignatureAlgorithm signatureAlgorithm = SignatureAlgorithm.from(algorithm);
        // TODO: cannot ignore signature algorithm, or TranscriptHash won't be the same.
        //  This means SignatureAlgorithm must be a class to allow for unknown values.
        if (signatureAlgorithm != null)
            algorithms.add(signatureAlgorithm);
        algorithm = 0;
        if (listLength == 0)
        {
            int result = 2 + totalLength;
            totalLength = 0;
            List<SignatureAlgorithm> signatureAlgorithms = List.copyOf(algorithms);
            algorithms.clear();
            state = State.TOTAL_LENGTH;
            listener.onExtension(new SignatureAlgorithmsExtension(signatureAlgorithms));
            return result;
        }
        else
        {
            state = State.ALGORITHM;
            return -1;
        }
    }

    private enum State
    {
        TOTAL_LENGTH,
        TOTAL_LENGTH_BYTES,
        LIST_LENGTH,
        LIST_LENGTH_BYTES,
        ALGORITHM,
        ALGORITHM_BYTES
    }
}
