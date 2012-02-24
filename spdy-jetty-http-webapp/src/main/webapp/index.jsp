<!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.0 Strict//EN" "http://www.w3.org/TR/xhtml1/DTD/xhtml1-strict.dtd">
<html>
<head>
    <title>SPDY TEST PAGE</title>
    <link rel="stylesheet" href="stylesheet.css" />
    <script type="text/javascript">
        function submit()
        {
            var xhr = new XMLHttpRequest();
            xhr.open("POST", "${pageContext.request.contextPath}/form.jsp", false);
            xhr.setRequestHeader("Content-Type","application/x-www-form-urlencoded");
            xhr.send("param=1");
            window.document.getElementById("form").innerHTML = xhr.responseText;
        }
    </script>
</head>
<body>
<h2>SPDY TEST PAGE</h2>
<div>
    <p><span id="css">This paragraph should have a colored background, meaning that the CSS has been loaded.</span></p>
</div>
<div id="image">
    <p>Below there should be an image</p>
    <img src="${pageContext.request.contextPath}/logo.jpg"  alt="logo" />
</div>
<div>
    <jsp:include page="included.jsp" />
</div>
<div>
    <p>Click on the button below to perform an AJAX call</p>
    <button type="button" onclick="submit()">
        PERFORM AJAX CALL
    </button>
    <p id="form"></p>
</div>
</body>
</html>
