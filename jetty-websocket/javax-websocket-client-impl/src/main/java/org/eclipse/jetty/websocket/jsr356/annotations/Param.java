package org.eclipse.jetty.websocket.jsr356.annotations;

public class Param
{
    public int index;
    public Class<?> type;
    private boolean valid = false;
    private String pathParamVariable = null;

    public Param(int idx, Class<?> type)
    {
        this.index = idx;
        this.type = type;
    }

    public String getPathParamVariable()
    {
        return this.pathParamVariable;
    }

    public boolean isValid()
    {
        return valid;
    }

    public void setPathParamVariable(String name)
    {
        this.pathParamVariable = name;
    }

    public void setValid(boolean flag)
    {
        this.valid = flag;
    }
}