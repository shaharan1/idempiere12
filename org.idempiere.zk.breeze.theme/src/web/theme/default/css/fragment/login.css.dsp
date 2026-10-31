<%@ page contentType="text/css;charset=UTF-8" %>
<%@ taglib uri="http://www.zkoss.org/dsp/web/core" prefix="c" %>

/* 1. Full page background - light geometric hexagon pattern */
body:has(.login-window),
.login-window {
    background: #F3F5F7 url(${c:encodeURL('~./theme/default/images/login-bg.png')}) center center / cover no-repeat fixed,
                #F3F5F7 url(../images/login-bg.png) center center / cover no-repeat fixed !important;
}

/* 2. Completely transparent container - NO white card, borderless, like 1st image */
.login-window .z-window.z-window-embedded,
.login-window .z-window-embedded-cnt,
.login-window .z-window-content,
.login-window .z-borderlayout,
.login-window .z-center,
.login-window .z-center-body,
.login-window .z-vbox,
.login-box-body,
.login-box-header,
.login-box-footer {
    background: transparent !important;
    background-color: transparent !important;
    background-image: none !important;
    box-shadow: none !important;
    border: none !important;
}

.login-box-body {
    width: 580px;
    padding: 0px 5px;
    margin: 0 auto;
    text-align: center;
}

.login-box-header {
    width: 580px;
    padding: 0px;
    margin: 0 auto;
    box-sizing: border-box;
}

/* 3. Hide 'Login' text header */
.login-box-header-txt {
    display: none !important;
}

/* 4. Primitek Logo */
.login-box-header-logo {
    padding-bottom: 25px !important;
    text-align: center;
}

.login-box-header-logo img {
    max-width: 480px !important;
    width: auto !important;
    height: auto !important;
    display: block !important;
    margin: 0 auto !important;
}

/* 5. Labels - Dark and legible on light background */
.login-label,
.login-box-body .z-label,
.login-box-body .z-checkbox-content {
    color: #222222 !important;
    font-weight: 600 !important;
    font-size: 13px !important;
}

.login-label {
    text-align: right;
    width: 40%;
    padding: 5px !important;
}

.login-field {
    text-align: left;
    width: 55%;
    padding: 5px !important;
}

/* 6. Clean white input fields */
.login-field input:not([type="checkbox"]) {
    width: 220px !important;
    background-color: #ffffff !important;
    border: 1px solid #aaa !important;
    border-radius: 4px !important;
    color: #111111 !important;
    padding: 4px 6px !important;
    box-shadow: 0 1px 3px rgba(0,0,0,0.1);
}

.login-field input:not([type="checkbox"]):focus {
    border-color: #007bff !important;
    outline: none !important;
    box-shadow: 0 0 5px rgba(0, 123, 255, 0.4) !important;
}

/* 7. Footer buttons */
.login-box-footer {
    height: 80px;
    width: 580px;
    padding: 0px;
    margin: 0 auto;
    box-sizing: border-box;
}

.login-box-footer .confirm-panel {
    width: 400px !important;
    margin: 0 auto !important;
}

.login-box-footer-pnl {
    width: 400px;
    margin: 0 auto;
    padding-top: 15px !important;
}

.login-btn {
    padding: 6px 24px !important;
    font-weight: 600 !important;
}

/* 8. Hide east/west sidebars */
.login-east-panel,
.login-west-panel {
    display: none !important;
}

@media screen and (max-width: 620px) {
    .login-box-body, .login-box-header, .login-box-footer {
        width: 95% !important;
    }
    .login-box-header-logo img {
        max-width: 90% !important;
    }
}

