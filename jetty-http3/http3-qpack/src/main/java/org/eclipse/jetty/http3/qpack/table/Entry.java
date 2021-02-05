package org.eclipse.jetty.http3.qpack.table;

import org.eclipse.jetty.http.HttpField;

public class Entry
{
    final HttpField _field;
    private int _slot; // The index within it's array

    public Entry()
    {
        this(-1, null);
    }

    public Entry(HttpField field)
    {
        this(-1, field);
    }

    public Entry(int index, HttpField field)
    {
        _field = field;
        _slot = index;
    }

    public int getSize()
    {
        String value = _field.getValue();
        return 32 + _field.getName().length() + (value == null ? 0 : value.length());
    }

    public void setIndex(int index)
    {
        _slot = index;
    }

    public int getIndex()
    {
        return _slot;
    }

    public HttpField getHttpField()
    {
        return _field;
    }

    public boolean isStatic()
    {
        return false;
    }

    public byte[] getStaticHuffmanValue()
    {
        return null;
    }

    @Override
    public String toString()
    {
        return String.format("{%s,%d,%s,%x}", isStatic() ? "S" : "D", _slot, _field, hashCode());
    }
}
