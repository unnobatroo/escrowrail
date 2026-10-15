package com.escrowrail.infrastructure.mail;

/** Branded transactional template; table layout for mail clients. */
final class BrandedEmailTemplate {

    private BrandedEmailTemplate() {}

    static String render(
            String icon, String heading, String intro, String cta,
            String link, String footnote, String logoUrl) {
        return """
                <!DOCTYPE html>
                <html><body style="margin:0;padding:0;background-color:#F4F5F0;font-family:Inter,-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;">
                <div style="display:none;max-height:0;overflow:hidden;">%s</div>
                <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" style="background-color:#F4F5F0;padding:32px 16px;">
                <tr><td align="center">
                <table role="presentation" width="480" cellpadding="0" cellspacing="0" style="max-width:480px;width:100%%;">
                  <tr><td style="padding:0 8px 16px;">
                    <img src="%s" width="36" height="36" alt="" style="display:inline-block;vertical-align:middle;border-radius:9px;">
                    <span style="display:inline-block;vertical-align:middle;margin-left:10px;font-size:18px;font-weight:700;color:#163300;letter-spacing:-0.02em;">Escrowrail</span>
                  </td></tr>
                </table>
                <table role="presentation" width="480" cellpadding="0" cellspacing="0" style="max-width:480px;width:100%%;background-color:#FFFFFF;border-radius:16px;border:1px solid #E8EAE3;padding:36px 32px;">
                  <tr><td>
                    <table role="presentation" cellpadding="0" cellspacing="0"><tr>
                      <td width="56" height="56" align="center" valign="middle" style="width:56px;height:56px;border-radius:50%%;background-color:#E9FBD7;font-size:26px;line-height:56px;">%s</td>
                    </tr></table>
                    <h1 style="margin:20px 0 8px;font-size:24px;font-weight:700;color:#163300;line-height:1.25;">%s</h1>
                    <p style="margin:0 0 28px;font-size:15px;line-height:1.6;color:#3E453C;">%s</p>
                    <table role="presentation" cellpadding="0" cellspacing="0"><tr><td style="border-radius:999px;background-color:#9FE870;">
                      <a href="%s" style="display:inline-block;padding:14px 32px;font-size:15px;font-weight:700;color:#163300;text-decoration:none;border-radius:999px;">%s</a>
                    </td></tr></table>
                    <p style="margin:24px 0 0;font-size:13px;line-height:1.5;color:#5C685B;">%s</p>
                    <hr style="margin:24px 0 0;border:none;border-top:1px solid #EEF0E8;">
                    <p style="margin:16px 0 0;font-size:12px;line-height:1.5;color:#88908A;word-break:break-all;">Button not working? Paste this into your browser:<br><a href="%s" style="color:#163300;">%s</a></p>
                  </td></tr>
                </table>
                <table role="presentation" width="480" cellpadding="0" cellspacing="0" style="max-width:480px;width:100%%;"><tr><td style="padding:20px 8px;font-size:11px;line-height:1.6;color:#88908A;text-align:center;">
                  Conditional B2B settlement at the mid-market rate — funds held in segregated EMI vaults.<br>© Escrowrail
                </td></tr></table>
                </td></tr></table>
                </body></html>
                """
                .formatted(intro, logoUrl, icon, heading, intro, link, cta, footnote, link, link);
    }
}
