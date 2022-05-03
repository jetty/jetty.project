<%@ page contentType="text/html; charset=UTF-8" %>
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
   <a href="auth.html"/>Login</a>
</body>

</html>
