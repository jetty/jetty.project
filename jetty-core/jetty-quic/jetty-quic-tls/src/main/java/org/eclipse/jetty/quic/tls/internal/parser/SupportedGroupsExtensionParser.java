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

package org.eclipse.jetty.quic.tls.internal.parser;

import java.nio.ByteBuffer;
import java.util.LinkedHashSet;
import java.util.Set;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.quic.tls.message.NamedGroup;
import org.eclipse.jetty.quic.tls.message.SupportedGroupsExtension;

public class SupportedGroupsExtensionParser implements ExtensionParser {
    private final Set<NamedGroup> namedGroups = new LinkedHashSet<>();
    private final ExtensionsParser.Listener listener;
    private State state = State.TOTAL_LENGTH;
    private int totalLength;
    private int listLength;
    private int group;
    private int cursor;

    public SupportedGroupsExtensionParser(ExtensionsParser.Listener listener) {
        this.listener = listener;
    }

    @Override
    public int getType() {
        return SupportedGroupsExtension.TYPE;
    }

    @Override
    public int parse(RetainableByteBuffer buffer) {
        while (true) {
            ByteBuffer byteBuffer = buffer.getByteBuffer();
            int remaining = byteBuffer.remaining();
            if (remaining == 0) {
                return -1;
            }
            switch (state) {
                case TOTAL_LENGTH -> {
                    if (remaining > 1) {
                        totalLength = byteBuffer.getShort() & 0xFFFF;
                        if (totalLength < 4) {
                            throw new IllegalStateException("invalid supported groups extension length " + totalLength);
                        }
                        state = State.LIST_LENGTH;
                    } else {
                        cursor = 2;
                        state = State.TOTAL_LENGTH_BYTES;
                    }
                }
                case TOTAL_LENGTH_BYTES -> {
                    int b = byteBuffer.get() & 0xFF;
                    --cursor;
                    totalLength += b << (8 * cursor);
                    if (cursor == 0) {
                        if (totalLength < 4) {
                            throw new IllegalStateException("invalid supported groups extension length " + totalLength);
                        }
                        state = State.LIST_LENGTH;
                    }
                }
                case LIST_LENGTH -> {
                    if (remaining > 1) {
                        listLength = byteBuffer.getShort() & 0xFFFF;
                        if (listLength == 0 || listLength % 2 != 0) {
                            throw new IllegalStateException("invalid supported groups list length " + listLength);
                        }
                        state = State.GROUP;
                    } else {
                        cursor = 2;
                        state = State.LIST_LENGTH_BYTES;
                    }
                }
                case LIST_LENGTH_BYTES -> {
                    int b = byteBuffer.get() & 0xFF;
                    --cursor;
                    listLength += b << (8 * cursor);
                    if (cursor == 0) {
                        if (listLength == 0 || listLength % 2 != 0) {
                            throw new IllegalStateException("invalid supported groups list length " + listLength);
                        }
                        state = State.GROUP;
                    }
                }
                case GROUP -> {
                    if (remaining > 1) {
                        group = byteBuffer.getShort() & 0xFFFF;
                        listLength -= 2;
                        int result = groupComplete();
                        if (result > 0) {
                            return result;
                        }
                    } else {
                        cursor = 2;
                        state = State.GROUP_BYTES;
                    }
                }
                case GROUP_BYTES -> {
                    int b = byteBuffer.get() & 0xFF;
                    --cursor;
                    group += b << (8 * cursor);
                    if (cursor == 0) {
                        listLength -= 2;
                        int result = groupComplete();
                        if (result > 0) {
                            return result;
                        }
                    }
                }
            }
        }
    }

    private int groupComplete() {
        NamedGroup namedGroup = NamedGroup.from(group);
        if (namedGroup == null) {
            throw new IllegalArgumentException("unknown named group " + Integer.toHexString(group));
        }
        namedGroups.add(namedGroup);
        group = 0;
        if (listLength == 0) {
            int result = totalLength;
            totalLength = 0;
            LinkedHashSet<NamedGroup> groups = new LinkedHashSet<>(namedGroups);
            namedGroups.clear();
            state = State.TOTAL_LENGTH;
            listener.onExtension(new SupportedGroupsExtension(groups));
            return result;
        } else {
            state = State.GROUP;
            return -1;
        }
    }

    private enum State {
        TOTAL_LENGTH,
        TOTAL_LENGTH_BYTES,
        LIST_LENGTH,
        LIST_LENGTH_BYTES,
        GROUP,
        GROUP_BYTES
    }
}
