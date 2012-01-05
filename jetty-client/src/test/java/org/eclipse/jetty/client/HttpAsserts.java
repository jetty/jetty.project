package org.eclipse.jetty.client;

import java.util.Collections;
import java.util.List;

import org.eclipse.jetty.http.HttpFields;
import org.junit.Assert;

public final class HttpAsserts
{
    public static void assertContainsHeaderKey(String expectedKey, HttpFields headers)
    {
        if (headers.containsKey(expectedKey))
        {
            return;
        }
        List<String> names = Collections.list(headers.getFieldNames());
        StringBuilder err = new StringBuilder();
        err.append("Missing expected header key [").append(expectedKey);
        err.append("] (of ").append(names.size()).append(" header fields)");
        for (int i = 0; i < names.size(); i++)
        {
            String value = headers.getStringField(names.get(i));
            err.append("\n").append(i).append("] ").append(names.get(i));
            err.append(": ").append(value);
        }
        Assert.fail(err.toString());
    }
}
