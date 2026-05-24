package com.monosun.monitor.trace;

import javax.servlet.*;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpServletResponseWrapper;
import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * HTML 응답을 버퍼링하고 {@code </head>} 태그 직전에 traceId 스니펫을 주입합니다.
 *
 * 주입 결과:
 *   &lt;meta name="trace-id" content="{traceId}"&gt;
 *   &lt;script&gt;window.__APM_TRACE_ID='{traceId}';&lt;/script&gt;
 *
 * 프론트엔드에서의 사용:
 *   const traceId = window.__APM_TRACE_ID
 *                || document.querySelector('meta[name="trace-id"]')?.content;
 */
public final class HtmlInjectionWrapper extends HttpServletResponseWrapper {

    private final String traceId;
    private final ByteArrayOutputStream buf = new ByteArrayOutputStream(8192);
    private PrintWriter        pw;
    private ServletOutputStream sos;
    private boolean finished;

    public HtmlInjectionWrapper(HttpServletResponse resp, String traceId) {
        super(resp);
        this.traceId = sanitize(traceId);
    }

    @Override
    public PrintWriter getWriter() throws IOException {
        if (sos != null) throw new IllegalStateException("getOutputStream() already called");
        if (pw == null) pw = new PrintWriter(new OutputStreamWriter(buf, charset()));
        return pw;
    }

    @Override
    public ServletOutputStream getOutputStream() {
        if (pw != null) throw new IllegalStateException("getWriter() already called");
        if (sos == null) sos = new BufStream(buf);
        return sos;
    }

    /** TraceIdFilter 가 chain.doFilter() 완료 후 반드시 호출 */
    public void finish() throws IOException {
        if (finished) return;
        finished = true;
        if (pw != null) pw.flush();

        String html    = buf.toString(charset().name());
        String patched = inject(html);
        byte[] bytes   = patched.getBytes(charset());

        HttpServletResponse real = (HttpServletResponse) getResponse();
        real.setContentLength(bytes.length);
        real.getOutputStream().write(bytes);
        real.getOutputStream().flush();
    }

    // ── internal ──────────────────────────────────────────────────────────────

    private String inject(String html) {
        String snippet = "\n<meta name=\"trace-id\" content=\"" + traceId + "\">" +
                         "\n<script>window.__APM_TRACE_ID='" + traceId + "';</script>\n";
        int idx = indexOfCI(html, "</head>");
        if (idx >= 0) return html.substring(0, idx) + snippet + html.substring(idx);
        idx = indexOfCI(html, "</body>");
        if (idx >= 0) return html.substring(0, idx) + snippet + html.substring(idx);
        return html + snippet;
    }

    private static int indexOfCI(String src, String target) {
        return src.toLowerCase().indexOf(target.toLowerCase());
    }

    private static String sanitize(String id) {
        return id == null ? "" : id.replaceAll("[^a-zA-Z0-9\\-_.]", "");
    }

    private Charset charset() {
        String enc = getCharacterEncoding();
        try { return enc != null ? Charset.forName(enc) : StandardCharsets.UTF_8; }
        catch (Exception e) { return StandardCharsets.UTF_8; }
    }

    private static final class BufStream extends ServletOutputStream {
        private final OutputStream out;
        BufStream(OutputStream out) { this.out = out; }
        @Override public void write(int b) throws IOException { out.write(b); }
        @Override public void write(byte[] b, int off, int len) throws IOException { out.write(b, off, len); }
        @Override public boolean isReady() { return true; }
        @Override public void setWriteListener(WriteListener wl) {}
    }
}
