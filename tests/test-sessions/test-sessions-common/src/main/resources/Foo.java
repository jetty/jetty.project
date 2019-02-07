
public class Foo implements java.io.Serializable
{
    int myI = 0;

    public Foo()
    {
    }

    public void setI(int i)
    {
      myI = i;
    }

    public int getI()
    {
        return myI;
    }

    public boolean equals(Object o)
    {
        return ((Foo)o).getI() == myI;
    }
}
