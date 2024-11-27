<%@ page contentType="text/html; charset=UTF-8" %>
<%@ page import="jakarta.servlet.http.HttpSession"%>
<html>
<head>
    <title>Logout</title>
</head>

<body>
<%
    HttpSession s = request.getSession(false);
    s.invalidate();
   %>
   <h1>Logout</h1>

   <p>You are now logged out.</p> 
   <a href="/"/>Home</a>
</body>

</html>
