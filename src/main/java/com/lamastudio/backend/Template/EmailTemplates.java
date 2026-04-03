package com.lamastudio.backend.Template;

public class EmailTemplates {

  public static String verificationTemplate(String name, String verifyUrl, String logoUrl) {
    return "<!DOCTYPE html>" +
        "<html lang=\"en\">" +
        "<head>" +
        "  <meta charset='UTF-8'>" +
        "  <meta name='viewport' content='width=device-width, initial-scale=1.0'>" +
        "  <title>Verify Your Email - Tinnie Lamadine Studios</title>" +
        "  <style>" +
        "    @import url('https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700;800;900&display=swap');" +
        "    @keyframes fadeIn {" +
        "      from { opacity: 0; transform: translateY(20px); }" +
        "      to { opacity: 1; transform: translateY(0); }" +
        "    }" +
        "    @keyframes shimmer {" +
        "      0% { background-position: -1000px 0; }" +
        "      100% { background-position: 1000px 0; }" +
        "    }" +
        "    .fade-in { animation: fadeIn 0.6s ease-out forwards; }" +
        "    .btn-hover { transition: all 0.3s ease; }" +
        "    .btn-hover:hover { transform: translateY(-2px); box-shadow: 0 10px 20px rgba(245, 197, 24, 0.2); }" +
        "    .link-hover { transition: color 0.2s ease; }" +
        "    .link-hover:hover { color: #f5c518 !important; }" +
        "  </style>" +
        "</head>" +
        "<body style='margin:0; padding:0; background:#0d1628; font-family:\"Inter\", Arial, sans-serif; line-height:1.6;'>" +
        "  <div style='padding:2rem 1rem;'>" +
        "    <div style='max-width:560px; margin:0 auto;'>" +

        // Header with Logo and Brand
        "      <div style='text-align:center; padding:2rem 0 1.5rem;'>" +
        "        <div style='display:inline-flex; align-items:center; gap:12px;'>" +
        (logoUrl != null && !logoUrl.isEmpty() ? 
          "<img src='" + logoUrl + "' alt='Tinnie Lamadine Studios' style='height:48px; width:auto;'/>" : "") +
        "        </div>" +
        "      </div>" +

        // Main Card
        "      <div class='fade-in' style='background:#111e35; border-radius:20px; overflow:hidden; border:1px solid #1e2d4a; box-shadow:0 20px 40px rgba(0,0,0,0.3);'>" +
        "        <div style='height:4px; background:linear-gradient(90deg, #c9a010, #f5c518, #ffd94a, #f5c518, #c9a010);'></div>" +

        "        <div style='padding:2.5rem 2rem 2rem;'>" +

        // Icon
        "          <div style='text-align:center; margin-bottom:1.5rem;'>" +
        "            <div style='display:inline-flex; align-items:center; justify-content:center; width:80px; height:80px; background:rgba(245,197,24,0.08); border:1.5px solid rgba(245,197,24,0.2); border-radius:50%;'>" +
        "              <svg width=\"36\" height=\"36\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"#f5c518\" stroke-width=\"1.8\">" +
        "                <path d=\"M4 4h16c1.1 0 2 .9 2 2v12c0 1.1-.9 2-2 2H4c-1.1 0-2-.9-2-2V6c0-1.1.9-2 2-2z\"/>" +
        "                <polyline points=\"22,6 12,13 2,6\"/>" +
        "              </svg>" +
        "            </div>" +
        "          </div>" +

        "          <h1 style='text-align:center; color:#ffffff; font-size:26px; font-weight:800; margin:0 0 12px; letter-spacing:-0.5px;'>Verify your email address</h1>" +

        "          <p style='text-align:center; color:#7a8fad; font-size:15px; margin:0 0 8px;'>Welcome to Tinnie Lamadine Studios,</p>" +
        "          <p style='text-align:center; color:#f5c518; font-size:16px; font-weight:700; margin:0 0 24px;'>" + escapeHtml(name) + "</p>" +

        "          <p style='text-align:center; color:#7a8fad; font-size:14px; line-height:1.7; margin:0 0 32px;'>Please verify your email address to start streaming thousands of movies and TV shows in stunning quality.</p>" +

        // CTA Button
        "          <div style='text-align:center; margin:0 0 32px;'>" +
        "            <a href='" + verifyUrl + "' class='btn-hover' style='display:inline-block; background:#f5c518; color:#0d1628; padding:14px 48px; text-decoration:none; font-weight:800; font-size:15px; border-radius:12px; letter-spacing:0.5px;'>Verify Email Address</a>" +
        "          </div>" +

        // Alternative link
        "          <div style='background:#0d1628; border-radius:12px; padding:16px 20px; margin-bottom:24px;'>" +
        "            <p style='font-size:12px; color:#5a6f8a; margin:0 0 8px; font-weight:600;'>🔗 Having trouble with the button?</p>" +
        "            <p style='font-size:12px; color:#4a7abf; word-break:break-all; margin:0; font-family:monospace;'>" + verifyUrl + "</p>" +
        "          </div>" +

        // Info Box
        "          <div style='background:rgba(245,197,24,0.04); border-left:3px solid #f5c518; border-radius:8px; padding:14px 16px; margin-bottom:24px;'>" +
        "            <p style='font-size:12px; color:#8899bb; margin:0; line-height:1.6;'>" +
        "              <strong style='color:#f5c518;'>⏰ Time-sensitive link</strong><br>" +
        "              This verification link will expire in <strong style='color:#f5c518;'>24 hours</strong> for your security." +
        "            </p>" +
        "          </div>" +

        // Help text
        "          <p style='font-size:12px; color:#5a6f8a; text-align:center; margin:0;'>Didn't request this? You can safely ignore this email.</p>" +

        "        </div>" +

        // Footer
        "        <div style='background:#0d1628; border-top:1px solid #1e2d4a; padding:1.25rem 2rem; text-align:center;'>" +
        "          <p style='color:#3a4f6a; font-size:11px; margin:0 0 8px;'>Feel Safe. Stay Entertained. · Only <span style='color:#f5c518; font-weight:600;'>$10/month</span>, cancel anytime.</p>" +
        "          <p style='margin:0; font-size:11px;'>" +
        "            <a href='#' class='link-hover' style='color:#4a6080; text-decoration:none; margin:0 8px;'>Privacy Policy</a>" +
        "            <span style='color:#2a3a50;'>•</span>" +
        "            <a href='#' class='link-hover' style='color:#4a6080; text-decoration:none; margin:0 8px;'>Help Center</a>" +
        "            <span style='color:#2a3a50;'>•</span>" +
        "            <a href='#' class='link-hover' style='color:#4a6080; text-decoration:none; margin:0 8px;'>Contact Support</a>" +
        "          </p>" +
        "        </div>" +
        "      </div>" +

        // Copyright
        "      <p style='text-align:center; color:#1e2d4a; font-size:11px; margin:1.5rem 0 0;'>© 2026 Tinnie Lamadine Studios. All rights reserved.</p>" +
        "    </div>" +
        "  </div>" +
        "</body>" +
        "</html>";
  }

  public static String passwordResetTemplate(String name, String resetUrl, String logoUrl) {
    return "<!DOCTYPE html>" +
        "<html lang=\"en\">" +
        "<head>" +
        "  <meta charset='UTF-8'>" +
        "  <meta name='viewport' content='width=device-width, initial-scale=1.0'>" +
        "  <title>Reset Your Password - Tinnie Lamadine Studios</title>" +
        "  <style>" +
        "    @import url('https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700;800;900&display=swap');" +
        "    @keyframes fadeIn {" +
        "      from { opacity: 0; transform: translateY(20px); }" +
        "      to { opacity: 1; transform: translateY(0); }" +
        "    }" +
        "    .fade-in { animation: fadeIn 0.6s ease-out forwards; }" +
        "    .btn-hover { transition: all 0.3s ease; }" +
        "    .btn-hover:hover { transform: translateY(-2px); box-shadow: 0 10px 20px rgba(245, 197, 24, 0.2); }" +
        "    .link-hover { transition: color 0.2s ease; }" +
        "    .link-hover:hover { color: #f5c518 !important; }" +
        "  </style>" +
        "</head>" +
        "<body style='margin:0; padding:0; background:#0d1628; font-family:\"Inter\", Arial, sans-serif; line-height:1.6;'>" +
        "  <div style='padding:2rem 1rem;'>" +
        "    <div style='max-width:560px; margin:0 auto;'>" +

        // Header with Logo and Brand
        "      <div style='text-align:center; padding:2rem 0 1.5rem;'>" +
        "        <div style='display:inline-flex; align-items:center; gap:12px;'>" +
        (logoUrl != null && !logoUrl.isEmpty() ? 
          "<img src='" + logoUrl + "' alt='Tinnie Lamadine Studios' style='height:48px; width:auto;'/>" :
          "") +
        "        </div>" +
        "      </div>" +

        // Main Card
        "      <div class='fade-in' style='background:#111e35; border-radius:20px; overflow:hidden; border:1px solid #1e2d4a; box-shadow:0 20px 40px rgba(0,0,0,0.3);'>" +
        "        <div style='height:4px; background:linear-gradient(90deg, #c9a010, #f5c518, #ffd94a, #f5c518, #c9a010);'></div>" +

        "        <div style='padding:2.5rem 2rem 2rem;'>" +

        // Icon
        "          <div style='text-align:center; margin-bottom:1.5rem;'>" +
        "            <div style='display:inline-flex; align-items:center; justify-content:center; width:80px; height:80px; background:rgba(245,197,24,0.08); border:1.5px solid rgba(245,197,24,0.2); border-radius:50%;'>" +
        "              <svg width=\"36\" height=\"36\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"#f5c518\" stroke-width=\"1.8\">" +
        "                <path d=\"M12 15v2m-6 4h12a2 2 0 002-2v-6a2 2 0 00-2-2H6a2 2 0 00-2 2v6a2 2 0 002 2zm10-10V6a4 4 0 00-8 0v4h8z\"/>" +
        "              </svg>" +
        "            </div>" +
        "          </div>" +

        "          <h1 style='text-align:center; color:#ffffff; font-size:26px; font-weight:800; margin:0 0 12px; letter-spacing:-0.5px;'>Reset your password</h1>" +

        "          <p style='text-align:center; color:#7a8fad; font-size:15px; margin:0 0 8px;'>Hello,</p>" +
        "          <p style='text-align:center; color:#f5c518; font-size:16px; font-weight:700; margin:0 0 24px;'>" + escapeHtml(name) + "</p>" +

        "          <p style='text-align:center; color:#7a8fad; font-size:14px; line-height:1.7; margin:0 0 24px;'>We received a request to reset the password for your Tinnie Lamadine Studios account. Click the button below to create a new password.</p>" +

        // CTA Button
        "          <div style='text-align:center; margin:0 0 32px;'>" +
        "            <a href='" + resetUrl + "' class='btn-hover' style='display:inline-block; background:#f5c518; color:#0d1628; padding:14px 48px; text-decoration:none; font-weight:800; font-size:15px; border-radius:12px; letter-spacing:0.5px;'>Reset Password</a>" +
        "          </div>" +

        // Alternative link
        "          <div style='background:#0d1628; border-radius:12px; padding:16px 20px; margin-bottom:24px;'>" +
        "            <p style='font-size:12px; color:#5a6f8a; margin:0 0 8px; font-weight:600;'>🔗 Alternative reset link:</p>" +
        "            <p style='font-size:12px; color:#4a7abf; word-break:break-all; margin:0; font-family:monospace;'>" + resetUrl + "</p>" +
        "          </div>" +

        // Security Notice
        "          <div style='background:rgba(245,197,24,0.04); border-left:3px solid #f5c518; border-radius:8px; padding:14px 16px; margin-bottom:24px;'>" +
        "            <p style='font-size:12px; color:#8899bb; margin:0; line-height:1.6;'>" +
        "              <strong style='color:#f5c518;'>🔒 Security notice</strong><br>" +
        "              This password reset link will expire in <strong style='color:#f5c518;'>1 hour</strong>. If you didn't request this, you can safely ignore this email. Your password will remain unchanged." +
        "            </p>" +
        "          </div>" +

        "          <p style='font-size:12px; color:#5a6f8a; text-align:center; margin:0;'>For security reasons, never share this link with anyone.</p>" +

        "        </div>" +

        // Footer
        "        <div style='background:#0d1628; border-top:1px solid #1e2d4a; padding:1.25rem 2rem; text-align:center;'>" +
        "          <p style='color:#3a4f6a; font-size:11px; margin:0 0 8px;'>Feel Safe. Stay Entertained. · Only <span style='color:#f5c518; font-weight:600;'>$10/month</span>, cancel anytime.</p>" +
        "          <p style='margin:0; font-size:11px;'>" +
        "            <a href='#' class='link-hover' style='color:#4a6080; text-decoration:none; margin:0 8px;'>Privacy Policy</a>" +
        "            <span style='color:#2a3a50;'>•</span>" +
        "            <a href='#' class='link-hover' style='color:#4a6080; text-decoration:none; margin:0 8px;'>Help Center</a>" +
        "            <span style='color:#2a3a50;'>•</span>" +
        "            <a href='#' class='link-hover' style='color:#4a6080; text-decoration:none; margin:0 8px;'>Contact Support</a>" +
        "          </p>" +
        "        </div>" +
        "      </div>" +

        // Copyright
        "      <p style='text-align:center; color:#1e2d4a; font-size:11px; margin:1.5rem 0 0;'>© 2026 Tinnie Lamadine Studios. All rights reserved.</p>" +
        "    </div>" +
        "  </div>" +
        "</body>" +
        "</html>";
  }

  // Helper method to escape HTML special characters
  private static String escapeHtml(String text) {
    if (text == null) return "";
    return text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;");
  }
}