package org.eclipse.jetty.websocket.core.server.internal;

import java.util.EnumSet;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Objects;
import java.util.Set;
import java.util.Spliterator;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.ToIntFunction;
import java.util.stream.Stream;

import org.eclipse.jetty.http.DateGenerator;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpHeaderValue;
import org.eclipse.jetty.http.QuotedCSV;

public class HttpFieldsWrapper implements HttpFields.Mutable
{
    private final HttpFields.Mutable _fields;

    public HttpFieldsWrapper(HttpFields.Mutable fields)
    {
        _fields = fields;
    }

    public boolean onPutField(String name, String value)
    {
        return true;
    }

    public boolean onAddField(String name, String value)
    {
        return true;
    }

    public boolean onRemoveField(String name)
    {
        return true;
    }

    @Override
    public Mutable add(String name, String value)
    {
        if (onAddField(name, value))
            return _fields.add(name, value);
        return this;
    }

    @Override
    public Mutable add(HttpHeader header, HttpHeaderValue value)
    {
        if (onAddField(header.name(), value.asString()))
            return _fields.add(header, value);
        return this;
    }

    @Override
    public Mutable add(HttpHeader header, String value)
    {
        if (onAddField(header.name(), value))
            return _fields.add(header, value);
        return this;
    }

    @Override
    public Mutable add(HttpField field)
    {
        if (onAddField(field.getName(), field.getValue()))
            return _fields.add(field);
        return this;
    }

    @Override
    public Mutable add(HttpFields fields)
    {
        for (HttpField field : fields)
        {
            add(field);
        }
        return this;
    }

    @Override
    public Mutable addCSV(HttpHeader header, String... values)
    {
        QuotedCSV existing = null;
        for (HttpField f : this)
        {
            if (f.getHeader() == header)
            {
                if (existing == null)
                    existing = new QuotedCSV(false);
                existing.addValue(f.getValue());
            }
        }
        String value = MutableHttpFields.formatCsvExcludingExisting(existing, values);
        if (value != null)
            add(header, value);
        return this;
    }

    @Override
    public Mutable addCSV(String name, String... values)
    {
        QuotedCSV existing = null;
        for (HttpField f : this)
        {
            if (f.is(name))
            {
                if (existing == null)
                    existing = new QuotedCSV(false);
                existing.addValue(f.getValue());
            }
        }
        String value = MutableHttpFields.formatCsvExcludingExisting(existing, values);
        if (value != null)
            add(name, value);
        return this;
    }

    @Override
    public Mutable addDateField(String name, long date)
    {
        add(name, DateGenerator.formatDate(date));
        return this;
    }

    @Override
    public HttpFields asImmutable()
    {
        return _fields.asImmutable();
    }

    @Override
    public HttpFields takeAsImmutable()
    {
        return _fields.takeAsImmutable();
    }

    @Override
    public Mutable clear()
    {
        return _fields.clear();
    }

    @Override
    public void ensureField(HttpField field)
    {
        // Is the field value multi valued?
        if (field.getValue().indexOf(',') < 0)
        {
            // Call Single valued computeEnsure with either String header name or enum HttpHeader
            if (field.getHeader() != null)
                computeField(field.getHeader(), (h, l) -> MutableHttpFields.computeEnsure(field, l));
            else
                computeField(field.getName(), (h, l) -> MutableHttpFields.computeEnsure(field, l));
        }
        else
        {
            // call multi valued computeEnsure with either String header name or enum HttpHeader
            if (field.getHeader() != null)
                computeField(field.getHeader(), (h, l) -> MutableHttpFields.computeEnsure(field, field.getValues(), l));
            else
                computeField(field.getName(), (h, l) -> MutableHttpFields.computeEnsure(field, field.getValues(), l));
        }
    }

    @Override
    public ListIterator<HttpField> listIterator()
    {
        return _fields.listIterator();
    }

    @Override
    public Mutable put(HttpField field)
    {
        if (onPutField(field.getName(), field.getValue()))
            return _fields.put(field);
        return this;
    }

    @Override
    public Mutable put(String name, String value)
    {
        if (onPutField(name, value))
            return _fields.put(name, value);
        return this;
    }

    @Override
    public Mutable put(HttpHeader header, HttpHeaderValue value)
    {
        if (onPutField(header.name(), value.asString()))
            return _fields.put(header, value);
        return this;
    }

    @Override
    public Mutable put(HttpHeader header, String value)
    {
        if (onPutField(header.name(), value))
            return _fields.put(header, value);
        return this;
    }

    @Override
    public Mutable put(String name, List<String> list)
    {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(list, "list must not be null");
        remove(name);
        for (String v : list)
        {
            if (v != null)
                add(name, v);
        }
        return this;
    }

    @Override
    public Mutable putDateField(HttpHeader name, long date)
    {
        return put(name, DateGenerator.formatDate(date));
    }

    @Override
    public Mutable putDateField(String name, long date)
    {
        return put(name, DateGenerator.formatDate(date));
    }

    @Override
    public Mutable putLongField(HttpHeader name, long value)
    {
        return put(name, Long.toString(value));
    }

    @Override
    public Mutable putLongField(String name, long value)
    {
        return put(name, Long.toString(value));
    }

    @Override
    public void computeField(HttpHeader header, BiFunction<HttpHeader, List<HttpField>, HttpField> computeFn)
    {
        _fields.computeField(header, computeFn);
    }

    @Override
    public void computeField(String name, BiFunction<String, List<HttpField>, HttpField> computeFn)
    {
        _fields.computeField(name, computeFn);
    }

    @Override
    public Mutable remove(HttpHeader name)
    {
        if (onRemoveField(name.name()))
            return _fields.remove(name);
        return this;
    }

    @Override
    public Mutable remove(EnumSet<HttpHeader> fields)
    {
        for (HttpHeader header : fields)
        {
            remove(header.name());
        }
        return this;
    }

    @Override
    public Mutable remove(String name)
    {
        if (onRemoveField(name))
            return _fields.remove(name);
        return this;
    }

    @Override
    public Iterator<HttpField> iterator()
    {
        return _fields.iterator();
    }

    @Override
    public void forEach(Consumer<? super HttpField> action)
    {
        _fields.forEach(action);
    }

    @Override
    public Spliterator<HttpField> spliterator()
    {
        return _fields.spliterator();
    }

    @Override
    public String asString()
    {
        return _fields.asString();
    }

    @Override
    public boolean contains(HttpField field)
    {
        return _fields.contains(field);
    }

    @Override
    public boolean contains(HttpHeader header, String value)
    {
        return _fields.contains(header, value);
    }

    @Override
    public boolean contains(String name, String value)
    {
        return _fields.contains(name, value);
    }

    @Override
    public boolean contains(HttpHeader header)
    {
        return _fields.contains(header);
    }

    @Override
    public boolean contains(EnumSet<HttpHeader> headers)
    {
        return _fields.contains(headers);
    }

    @Override
    public boolean contains(String name)
    {
        return _fields.contains(name);
    }

    @Override
    public String get(HttpHeader header)
    {
        return _fields.get(header);
    }

    @Override
    public String get(String header)
    {
        return _fields.get(header);
    }

    @Override
    public List<String> getCSV(HttpHeader header, boolean keepQuotes)
    {
        return _fields.getCSV(header, keepQuotes);
    }

    @Override
    public List<String> getCSV(String name, boolean keepQuotes)
    {
        return _fields.getCSV(name, keepQuotes);
    }

    @Override
    public long getDateField(String name)
    {
        return _fields.getDateField(name);
    }

    @Override
    public long getDateField(HttpHeader header)
    {
        return _fields.getDateField(header);
    }

    @Override
    public HttpField getField(int index)
    {
        return _fields.getField(index);
    }

    @Override
    public HttpField getField(HttpHeader header)
    {
        return _fields.getField(header);
    }

    @Override
    public HttpField getField(String name)
    {
        return _fields.getField(name);
    }

    @Override
    @Deprecated
    public Enumeration<String> getFieldNames()
    {
        return _fields.getFieldNames();
    }

    @Override
    public Set<String> getFieldNamesCollection()
    {
        return _fields.getFieldNamesCollection();
    }

    @Override
    public List<HttpField> getFields(HttpHeader header)
    {
        return _fields.getFields(header);
    }

    @Override
    public List<HttpField> getFields(String name)
    {
        return _fields.getFields(name);
    }

    @Override
    public long getLongField(String name) throws NumberFormatException
    {
        return _fields.getLongField(name);
    }

    @Override
    public long getLongField(HttpHeader header) throws NumberFormatException
    {
        return _fields.getLongField(header);
    }

    @Override
    public List<String> getQualityCSV(HttpHeader header)
    {
        return _fields.getQualityCSV(header);
    }

    @Override
    public List<String> getQualityCSV(HttpHeader header, ToIntFunction<String> secondaryOrdering)
    {
        return _fields.getQualityCSV(header, secondaryOrdering);
    }

    @Override
    public List<String> getQualityCSV(String name)
    {
        return _fields.getQualityCSV(name);
    }

    @Override
    public Enumeration<String> getValues(String name)
    {
        return _fields.getValues(name);
    }

    @Override
    public List<String> getValuesList(HttpHeader header)
    {
        return _fields.getValuesList(header);
    }

    @Override
    public List<String> getValuesList(String name)
    {
        return _fields.getValuesList(name);
    }

    @Override
    public boolean isEqualTo(HttpFields that)
    {
        return _fields.isEqualTo(that);
    }

    @Override
    public int size()
    {
        return _fields.size();
    }

    @Override
    public Stream<HttpField> stream()
    {
        return _fields.stream();
    }
}
