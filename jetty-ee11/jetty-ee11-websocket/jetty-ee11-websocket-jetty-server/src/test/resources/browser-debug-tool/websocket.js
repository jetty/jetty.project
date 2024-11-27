if (!window.WebSocket && window.MozWebSocket) {
    window.WebSocket = window.MozWebSocket;
}

if (!window.WebSocket) {
    alert("WebSocket not supported by this browser");
}

function $() {
    return document.getElementById(arguments[0]);
}
function $F() {
    return document.getElementById(arguments[0]).value;
}

function getKeyCode(ev) {
    if (window.event)
        return window.event.keyCode;
    return ev.keyCode;
}

var wstool = {
    connect : function() {
        var location = document.location.toString().replace('http://', 'ws://')
                .replace('https://', 'wss://');

        wstool.info("Document URI: " + document.location);
        wstool.info("WS URI: " + location);
        
        this._scount = 0;

        try {
            this._ws = new WebSocket(location, "tool");
            this._ws.onopen = this._onopen;
            this._ws.onmessage = this._onmessage;
            this._ws.onclose = this._onclose;
        } catch (exception) {
            wstool.info("Connect Error: " + exception);
        }
    },

    close : function() {
        this._ws.close();
    },
    
    _out : function(css, message) {
        var console = $('console');
        var spanText = document.createElement('span');
        spanText.className = 'text ' + css;
        spanText.innerHTML = message;
        var lineBreak = document.createElement('br');
        console.appendChild(spanText);
        console.appendChild(lineBreak);
        console.scrollTop = console.scrollHeight - console.clientHeight;
    },

    info : function(message) {
        wstool._out("info", message);
    },

    infoc : function(message) {
        if(message.length > 300) {
            wstool._out("client", "[c] [big message: " + message.length + " characters]");
        } else {
            wstool._out("client", "[c] " + message);
        }
    },
    
    infos : function(message) {
        this._scount++;
        if(message.length > 300 && !message.startsWith("Dump:")) {
            wstool._out("server", "[s" + this._scount + "] [big message: " + message.length + " characters]");
        } else {
            wstool._out("server", "[s" + this._scount + "] " + message);
        }
    },

    setState : function(enabled) {
        $('connect').disabled = enabled;
        $('close').disabled = !enabled;
        $('info').disabled = !enabled;
        $('ping').disabled = !enabled;
        $('time').disabled = !enabled;
        $('many').disabled = !enabled;
        $('manythreads').disabled = !enabled;
        $('hello').disabled = !enabled;
        $('there').disabled = !enabled;
        $('dump').disabled = !enabled;
        $('json').disabled = !enabled;
        $('send10k').disabled = !enabled;
        $('send100k').disabled = !enabled;
        $('send1000k').disabled = !enabled;
        $('send10m').disabled = !enabled;
    },
    
    _onopen : function() {
        wstool.setState(true);
        wstool.info("Websocket Connected");
    },

    _send : function(message) {
        if (this._ws) {
            this._ws.send(message);
            wstool.infoc(message);
        }
    },

    write : function(text) {
        wstool._send(text);
    },

    _onmessage : function(m) {
        if (m.data) {
            wstool.infos(m.data);
        }
    },

    _onclose : function(closeEvent) {
        this._ws = null;
        wstool.setState(false);
        wstool.info("Websocket Closed");
        wstool.info("  .wasClean = " + closeEvent.wasClean);
        
        var codeMap = {};
        codeMap[1000] = "(NORMAL)";
        codeMap[1001] = "(ENDPOINT_GOING_AWAY)";
        codeMap[1002] = "(PROTOCOL_ERROR)";
        codeMap[1003] = "(UNSUPPORTED_DATA)";
        codeMap[1004] = "(UNUSED/RESERVED)";
        codeMap[1005] = "(INTERNAL/NO_CODE_PRESENT)";
        codeMap[1006] = "(INTERNAL/ABNORMAL_CLOSE)";
        codeMap[1007] = "(BAD_DATA)";
        codeMap[1008] = "(POLICY_VIOLATION)";
        codeMap[1009] = "(MESSAGE_TOO_BIG)";
        codeMap[1010] = "(HANDSHAKE/EXT_FAILURE)";
        codeMap[1011] = "(SERVER/UNEXPECTED_CONDITION)";
        codeMap[1015] = "(INTERNAL/TLS_ERROR)";
        var codeStr = codeMap[closeEvent.code];
        wstool.info("  .code = " + closeEvent.code + "  " + codeStr);
        wstool.info("  .reason = " + closeEvent.reason);
    }
};
