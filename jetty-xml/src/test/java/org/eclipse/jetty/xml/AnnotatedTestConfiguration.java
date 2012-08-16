package org.eclipse.jetty.xml;

import org.eclipse.jetty.util.annotation.Name;

public class AnnotatedTestConfiguration
{
    private String first;
    private String second;
    private String third;
    
    AnnotatedTestConfiguration nested;
    
    public AnnotatedTestConfiguration(@Name("first") String first, @Name("second") String second, @Name("third") String third)
    {
        this.first = first;
        this.second = second;
        this.third = third;
    }

    public String getFirst()
    {
        return first;
    }

    public void setFirst(String first)
    {
        this.first = first;
    }

    public String getSecond()
    {
        return second;
    }

    public void setSecond(String second)
    {
        this.second = second;
    }

    public String getThird()
    {
        return third;
    }

    public void setThird(String third)
    {
        this.third = third;
    }

    public AnnotatedTestConfiguration getNested()
    {
        return nested;
    }

    public void setNested(AnnotatedTestConfiguration nested)
    {
        this.nested = nested;
    }
    
}
