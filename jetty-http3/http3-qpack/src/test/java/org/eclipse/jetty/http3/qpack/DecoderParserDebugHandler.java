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

import java.util.LinkedList;
import java.util.Queue;

import org.eclipse.jetty.http3.qpack.internal.parser.DecoderInstructionParser;

public class DecoderParserDebugHandler implements DecoderInstructionParser.Handler
{
    public Queue<Integer> setCapacities = new LinkedList<>();
    public Queue<Integer> duplicates = new LinkedList<>();
    public Queue<Entry> literalNameEntries = new LinkedList<>();
    public Queue<ReferencedEntry> referencedNameEntries = new LinkedList<>();

    private final QpackDecoder _decoder;

    public DecoderParserDebugHandler()
    {
        this(null);
    }

    public DecoderParserDebugHandler(QpackDecoder decoder)
    {
        _decoder = decoder;
    }

    public static class Entry
    {
        public Entry(String name, String value)
        {
            this.value = value;
            this.name = name;
        }

        String name;
        String value;
    }

    public static class ReferencedEntry
    {
        public ReferencedEntry(int index, boolean dynamic, String value)
        {
            this.index = index;
            this.dynamic = dynamic;
            this.value = value;
        }

        int index;
        boolean dynamic;
        String value;
    }

    @Override
    public void onSetDynamicTableCapacity(int capacity)
    {
        setCapacities.add(capacity);
        if (_decoder != null)
            _decoder.setCapacity(capacity);
    }

    @Override
    public void onDuplicate(int index) throws QpackException
    {
        duplicates.add(index);
        if (_decoder != null)
            _decoder.insert(index);
    }

    @Override
    public void onInsertNameWithReference(int nameIndex, boolean isDynamicTableIndex, String value) throws QpackException
    {
        referencedNameEntries.add(new ReferencedEntry(nameIndex, isDynamicTableIndex, value));
        if (_decoder != null)
            _decoder.insert(nameIndex, isDynamicTableIndex, value);
    }

    @Override
    public void onInsertWithLiteralName(String name, String value) throws QpackException
    {
        literalNameEntries.add(new Entry(name, value));
        if (_decoder != null)
            _decoder.insert(name, value);
    }

    public boolean isEmpty()
    {
        return setCapacities.isEmpty() && duplicates.isEmpty() && literalNameEntries.isEmpty() && referencedNameEntries.isEmpty();
    }
}
