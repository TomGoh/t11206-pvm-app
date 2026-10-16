package com.kylinos.xkernelvm.control;

import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

/** Host test of TerminalBuffer against the console output the x-kernel guest really sends. */
public class TerminalBufferTest {
    private static int failures;
    private static final ArrayList<String> replies = new ArrayList<>();

    private static TerminalBuffer terminal() {
        replies.clear();
        return new TerminalBuffer(d -> replies.add(new String(d, StandardCharsets.US_ASCII)));
    }

    private static void feed(TerminalBuffer t, String s) {
        t.feed(s.getBytes(StandardCharsets.UTF_8), 0, true);
    }

    private static void check(String name, Object got, Object want) {
        if (!want.equals(got)) {
            failures++;
            System.out.println("FAIL " + name + ": got [" + got + "] want [" + want + "]");
        } else {
            System.out.println("ok   " + name);
        }
    }

    public static void main(String[] args) {
        TerminalBuffer t = terminal();
        feed(t, "Welcome to \033[96m\033[1mKylin X\033[0m!\r\nSHLVL=1\r\n");
        SpannableStringBuilder r = (SpannableStringBuilder) t.render();
        check("banner text", r.toString(), "Welcome to Kylin X!\nSHLVL=1\n");
        int colour = 0, face = 0;
        String spanned = "";
        for (SpannableStringBuilder.Entry e : r.spans) {
            spanned = r.toString().substring(e.start, e.end);
            if (e.span instanceof ForegroundColorSpan) colour = ((ForegroundColorSpan) e.span).getForegroundColor();
            if (e.span instanceof StyleSpan) face = ((StyleSpan) e.span).getStyle();
        }
        check("banner span covers the name", spanned, "Kylin X");
        check("banner colour bright cyan", Integer.toHexString(colour), "ff6ff3f3");
        check("banner bold", face, 1);

        t = terminal();
        feed(t, "Use \033[1m\033[3mapt\033[0m to install packages.\n");
        r = (SpannableStringBuilder) t.render();
        int italic = 0;
        for (SpannableStringBuilder.Entry e : r.spans) if (e.span instanceof StyleSpan) italic = ((StyleSpan) e.span).getStyle();
        check("bold italic", italic, 3);

        t = terminal();
        feed(t, "abcdef\r12\033[K");
        check("carriage return + erase in line", t.render().toString(), "12");

        t = terminal();
        feed(t, "# ls -l\b\b\033[K\n");
        check("backspace + erase", t.render().toString(), "# ls \n");

        t = terminal();
        byte[] han = "中文".getBytes(StandardCharsets.UTF_8);
        t.feed(java.util.Arrays.copyOfRange(han, 0, 4), 0, true);
        t.feed(java.util.Arrays.copyOfRange(han, 4, han.length), 0, true);
        check("UTF-8 split across pieces", t.render().toString(), "中文");

        t = terminal();
        feed(t, "x\033[6n");
        check("cursor report answered", replies.toString(), "[\033[24;2R]");
        t = terminal();
        t.feed("x\033[6n".getBytes(StandardCharsets.US_ASCII), 0, false);
        check("replayed request not answered", replies.size(), 0);

        t = terminal();
        feed(t, "a\nb\n\033[H\033[2J# ");
        check("clear screen", t.render().toString(), "# ");

        t = terminal();
        for (int i = 0; i < 1500; i++) feed(t, "line " + i + "\r\n");
        String all = t.render().toString();
        String[] lines = all.split("\n", -1);
        check("scrollback bounded", lines.length, TerminalBuffer.MAX_LINES);
        check("newest line kept", lines[lines.length - 2], "line 1499");

        t = terminal();
        feed(t, "abcdef\r\033[2Pxx");
        check("delete characters", t.render().toString(), "xxef");

        t = terminal();
        feed(t, "a\tb");
        check("tab", t.render().toString(), "a       b");

        t = terminal();
        feed(t, "\033[38;5;196mred\033[0m \033]0;title\007ok");
        check("256 colours and OSC title", t.render().toString(), "red ok");

        t = terminal();
        t.feed(new byte[] {'o', 'k', 0x1b}, 0, true);
        t.feed(new byte[] {'[', '3', '1', 'm', 'r'}, 0, true);
        check("escape split across pieces", t.render().toString(), "okr");

        t = terminal();
        feed(t, "12345");
        t.feed("ab".getBytes(StandardCharsets.US_ASCII), 1, true);
        check("feed from an offset", t.render().toString(), "12345b");

        System.out.println(failures == 0 ? "ALL PASSED" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
