package org.eclipse.jetty.http3.qpack.generator;

import java.nio.ByteBuffer;

import org.eclipse.jetty.http3.qpack.Huffman;
import org.eclipse.jetty.http3.qpack.NBitInteger;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.BufferUtil;

public class IndexedNameEntryInstruction implements Instruction
{
    private final boolean _dynamic;
    private final int _index;
    private final boolean _huffman;
    private final String _value;

    public IndexedNameEntryInstruction(boolean dynamic, int index, boolean huffman, String value)
    {
        _dynamic = dynamic;
        _index = index;
        _huffman = huffman;
        _value = value;
    }

    @Override
    public void encode(ByteBufferPool.Lease lease)
    {
        int size = NBitInteger.octectsNeeded(6, _index) + (_huffman ? Huffman.octetsNeeded(_value) : _value.length()) + 2;
        ByteBuffer buffer = lease.acquire(size, false);

        // First bit indicates the instruction, second bit is whether it is a dynamic table reference or not.
        buffer.put((byte)(0x80 | (_dynamic ? 0x00 : 0x40)));
        NBitInteger.encode(buffer, 6, _index);

        // We will not huffman encode the string.
        if (_huffman)
        {
            buffer.put((byte)(0x80));
            NBitInteger.encode(buffer, 7, Huffman.octetsNeeded(_value));
            Huffman.encode(buffer, _value);
        }
        else
        {
            buffer.put((byte)(0x00));
            NBitInteger.encode(buffer, 7, _value.length());
            buffer.put(_value.getBytes());
        }

        BufferUtil.flipToFlush(buffer, 0);
        lease.append(buffer, true);
    }
}
