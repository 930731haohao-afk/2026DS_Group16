package edu.nccu.ai;

import edu.nccu.ai.Models.Page;
import java.io.*;
import java.net.URI;
import java.util.*;
import javax.swing.text.MutableAttributeSet;
import javax.swing.text.html.*;
import javax.swing.text.html.parser.ParserDelegator;

/** Small dependency-free HTML text extractor. It does not execute JavaScript. */
public final class HtmlParser {
    private HtmlParser() { }

    public static Page parse(String url, String html) {
        StringBuilder title = new StringBuilder(), text = new StringBuilder();
        LinkedHashSet<String> links = new LinkedHashSet<>();
        URI base = URI.create(url);
        HTMLEditorKit.ParserCallback callback = new HTMLEditorKit.ParserCallback() {
            int ignored = 0;
            boolean inTitle = false;
            private boolean ignoredTag(HTML.Tag tag) {
                return Set.of("script", "style", "nav", "noscript", "svg", "template").contains(tag.toString());
            }
            private void link(MutableAttributeSet attrs) {
                Object href = attrs.getAttribute(HTML.Attribute.HREF);
                if (href == null || links.size() >= 200) return;
                try {
                    URI resolved = base.resolve(href.toString().strip()).normalize();
                    if (("http".equalsIgnoreCase(resolved.getScheme()) || "https".equalsIgnoreCase(resolved.getScheme()))
                        && resolved.getHost() != null && resolved.getUserInfo() == null) {
                        String value = resolved.toString();
                        int fragment = value.indexOf('#');
                        links.add(fragment < 0 ? value : value.substring(0, fragment));
                    }
                } catch (IllegalArgumentException ignored) { }
            }
            @Override public void handleStartTag(HTML.Tag tag, MutableAttributeSet attrs, int pos) {
                if (ignoredTag(tag)) ignored++;
                if (tag == HTML.Tag.TITLE) inTitle = true;
                if (ignored == 0 && tag == HTML.Tag.A) link(attrs);
                if (ignored == 0 && tag.isBlock()) text.append('\n');
            }
            @Override public void handleEndTag(HTML.Tag tag, int pos) {
                if (tag == HTML.Tag.TITLE) inTitle = false;
                if (ignoredTag(tag) && ignored > 0) ignored--;
                if (ignored == 0 && tag.isBlock()) text.append('\n');
            }
            @Override public void handleSimpleTag(HTML.Tag tag, MutableAttributeSet attrs, int pos) {
                // ParserDelegator exposes HTML5 containers such as nav as unknown simple tags.
                if (ignoredTag(tag)) {
                    boolean end = attrs.getAttribute(HTML.Attribute.ENDTAG) != null;
                    if (end) ignored = Math.max(0, ignored - 1); else ignored++;
                }
                if (ignored == 0 && tag == HTML.Tag.BR) text.append('\n');
                if (ignored == 0 && tag == HTML.Tag.A) link(attrs);
            }
            @Override public void handleText(char[] data, int pos) {
                if (ignored > 0) return;
                if (inTitle) title.append(data).append(' ');
                else text.append(data).append(' ');
            }
        };
        try { new ParserDelegator().parse(new StringReader(Objects.requireNonNullElse(html, "")), callback, true); }
        catch (IOException e) { throw new IllegalArgumentException("無法解析網頁", e); }
        String pageTitle = clean(title.toString());
        if (pageTitle.isBlank()) pageTitle = base.getHost();
        return new Page(url, pageTitle, clean(text.toString()), List.copyOf(links), base.getHost(), false);
    }

    private static String clean(String value) { return value.replaceAll("[\\s\\u00a0]+", " ").strip(); }
}
