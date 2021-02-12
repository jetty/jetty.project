package org.eclipse.jetty.http3.qpack.generator;

import java.nio.ByteBuffer;

import org.eclipse.jetty.http3.qpack.Huffman;
import org.eclipse.jetty.http3.qpack.NBitInteger;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.BufferUtil;

public class LiteralNameEntryInstruction implements Instruction
{
    private final boolean _huffmanName;
    private final boolean _huffmanValue;
    private final String _name;
    private final String _value;

    public LiteralNameEntryInstruction(boolean huffmanName, String name, boolean huffmanValue, String value)
    {
        _huffmanName = huffmanName;
        _huffmanValue = huffmanValue;
        _name = name;
        _value = value;
    }

    @Override
    public void encode(ByteBufferPool.Lease lease)
    {
        int size = (_huffmanName ? Huffman.octetsNeeded(_name) : _name.length()) +
            (_huffmanValue ? Huffman.octetsNeeded(_value) : _value.length()) + 2;
        ByteBuffer buffer = lease.acquire(size, false);

        if (_huffmanName)
        {
            buffer.put((byte)(0x40 | 0x20));
            NBitInteger.encode(buffer, 5, Huffman.octetsNeeded(_name));
            Huffman.encode(buffer, _name);
        }
        else
        {
            buffer.put((byte)(0x40));
            NBitInteger.encode(buffer, 5, _name.length());
            buffer.put(_name.getBytes());
        }

        if (_huffmanValue)
        {
            buffer.put((byte)(0x80));
            NBitInteger.encode(buffer, 7, Huffman.octetsNeeded(_value));
            Huffman.encode(buffer, _value);
        }
        else
        {
            buffer.put((byte)(0x00));
            NBitInteger.encode(buffer, 5, _value.length());
            buffer.put(_value.getBytes());
        }

        BufferUtil.flipToFlush(buffer, 0);
        lease.append(buffer, true);
    }
}
