package org.eclipse.jetty.websocket.api;

import java.util.HashMap;
import java.util.Map;

import org.eclipse.jetty.util.QuotedStringTokenizer;

/**
 * Proposed interface for API (not yet settled)
 */
public class ExtensionConfig
{
    public static ExtensionConfig parse(String parameterizedName)
    {
        QuotedStringTokenizer tok = new QuotedStringTokenizer(parameterizedName,";");

        ExtensionConfig ext = new ExtensionConfig(tok.nextToken().trim());

        while (tok.hasMoreTokens())
        {
            QuotedStringTokenizer nv = new QuotedStringTokenizer(tok.nextToken().trim(),"=");
            String key = nv.nextToken().trim();
            String value = nv.hasMoreTokens()?nv.nextToken().trim():null;
            ext.setParameter(key,value);
        }

        return ext;
    }

    private final String name;
    private Map<String, String> parameters;

    public ExtensionConfig(String name)
    {
        this.name = name;
        this.parameters = new HashMap<>();
    }

    public String getName()
    {
        return name;
    }

    public int getParameter(String key, int defValue)
    {
        String val = parameters.get(key);
        if(val == null) {
            return defValue;
        }
        return Integer.valueOf(val);
    }

    public String getParameter(String key, String defValue)
    {
        String val = parameters.get(key);
        if(val == null) {
            return defValue;
        }
        return val;
    }

    public String getParameterizedName()
    {
        StringBuilder str = new StringBuilder();
        str.append(name);
        for (String param : parameters.keySet())
        {
            str.append(';').append(param).append('=').append(QuotedStringTokenizer.quoteIfNeeded(parameters.get(param),";="));
        }
        return str.toString();
    }

    /**
     * Initialize the parameters on this config from the other configuration.
     * @param other the other configuration.
     */
    public void init(ExtensionConfig other)
    {
        this.parameters.clear();
        this.parameters.putAll(other.parameters);
    }

    public void setParameter(String key, int value)
    {
        parameters.put(key,Integer.toString(value));
    }

    public void setParameter(String key, String value)
    {
        parameters.put(key,value);
    }

    @Override
    public String toString()
    {
        return getParameterizedName();
    }
}