/*
 * Copyright 2026 KylinSoft Co., Ltd.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.kylinos.xkernelvm.control;

import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;

/**
 * A small line-oriented terminal for the guest's serial console.
 *
 * <p>It decodes UTF-8 across pieces and understands what a shell on a serial line sends: line
 * feed, carriage return, backspace, tab, colours and bold/italic (SGR), erase in line, the cursor
 * moves a line editor uses, and clear screen. It keeps a scrollback of {@link #MAX_LINES} lines
 * instead of a fixed screen, so full-screen programs are not drawn as on a real terminal.
 *
 * <p>A cursor position report request (ESC [ 6 n) is answered through the {@link Responder}, as
 * some line editors wait for it.
 */
final class TerminalBuffer {
    static final int MAX_LINES = 1000;
    /** The screen height reported to the guest. */
    private static final int SCREEN_ROWS = 24;

    interface Responder {
        void reply(byte[] data);
    }

    // Style bits: foreground colour index + 1 (0 = default), background likewise, attributes.
    private static final int FG_SHIFT = 0;
    private static final int BG_SHIFT = 5;
    private static final int COLOUR_MASK = 0x1f;
    private static final int BOLD = 1 << 10;
    private static final int ITALIC = 1 << 11;
    private static final int INVERSE = 1 << 12;

    private static final int DEFAULT_FG = 0xffd0d0d0;
    private static final int DEFAULT_BG = 0xff101418;
    private static final int[] PALETTE = {
        0xff555753, 0xffef5350, 0xff8ae234, 0xfffce94f,
        0xff729fcf, 0xffad7fa8, 0xff34e2e2, 0xffd3d7cf,
        0xff888a85, 0xffff6e67, 0xff9ff55a, 0xfffffa74,
        0xff8cb8ff, 0xffe5a6e0, 0xff6ff3f3, 0xffffffff,
    };

    private enum Mode { NORMAL, ESC, CSI, OSC, OSC_ESC, CHARSET }

    private static final class Line {
        char[] chars = new char[80];
        int[] styles = new int[80];
        int length;

        void ensure(int size) {
            if (size > chars.length) {
                int n = Math.max(size, chars.length * 2);
                chars = Arrays.copyOf(chars, n);
                styles = Arrays.copyOf(styles, n);
            }
        }

        /** Pads with blanks up to {@code col}. */
        void padTo(int col, int style) {
            ensure(col);
            while (length < col) {
                chars[length] = ' ';
                styles[length] = style;
                length++;
            }
        }
    }

    private final Responder mResponder;
    private final ArrayList<Line> mLines = new ArrayList<>();
    private int mRow;
    private int mCol;
    private int mStyle;

    private boolean mAnswer;
    private Mode mMode = Mode.NORMAL;
    private final StringBuilder mParams = new StringBuilder();
    private int mUtf8Left;
    private int mCodePoint;

    TerminalBuffer(Responder responder) {
        mResponder = responder;
        reset();
    }

    /** Forgets everything, as for a new console. */
    void reset() {
        mLines.clear();
        mLines.add(new Line());
        mRow = 0;
        mCol = 0;
        mStyle = 0;
        mMode = Mode.NORMAL;
        mParams.setLength(0);
        mUtf8Left = 0;
    }

    /**
     * Interprets output from {@code data[from]} on. Requests in it are answered only when
     * {@code answer} is set, that is for output that is new rather than replayed.
     */
    void feed(byte[] data, int from, boolean answer) {
        mAnswer = answer;
        for (int i = from; i < data.length; i++) {
            int b = data[i] & 0xff;
            if (mUtf8Left > 0) {
                if ((b & 0xc0) == 0x80) {
                    mCodePoint = (mCodePoint << 6) | (b & 0x3f);
                    if (--mUtf8Left == 0) {
                        putCodePoint(mCodePoint);
                    }
                    continue;
                }
                mUtf8Left = 0;
                putCodePoint(0xfffd);
            }
            if (b < 0x80) {
                ascii(b);
            } else if (mMode != Mode.NORMAL) {
                mMode = Mode.NORMAL; // A sequence never contains non-ASCII bytes.
            } else if ((b & 0xe0) == 0xc0) {
                mCodePoint = b & 0x1f;
                mUtf8Left = 1;
            } else if ((b & 0xf0) == 0xe0) {
                mCodePoint = b & 0x0f;
                mUtf8Left = 2;
            } else if ((b & 0xf8) == 0xf0) {
                mCodePoint = b & 0x07;
                mUtf8Left = 3;
            } else {
                putCodePoint(0xfffd);
            }
        }
    }

    private void ascii(int b) {
        switch (mMode) {
            case ESC:
                escape(b);
                return;
            case CSI:
                if (b >= 0x40 && b <= 0x7e) {
                    mMode = Mode.NORMAL;
                    csi((char) b, mParams.toString());
                } else if (b >= 0x20) {
                    mParams.append((char) b);
                } else {
                    control(b); // Controls inside a sequence act at once.
                }
                return;
            case OSC:
                if (b == 0x07) {
                    mMode = Mode.NORMAL;
                } else if (b == 0x1b) {
                    mMode = Mode.OSC_ESC;
                }
                return;
            case OSC_ESC:
                mMode = b == '\\' ? Mode.NORMAL : Mode.OSC;
                return;
            case CHARSET:
                mMode = Mode.NORMAL;
                return;
            case NORMAL:
            default:
                if (b < 0x20 || b == 0x7f) {
                    control(b);
                } else {
                    put((char) b);
                }
        }
    }

    private void control(int b) {
        switch (b) {
            case '\n':
                newLine();
                break;
            case '\r':
                mCol = 0;
                break;
            case '\b':
                if (mCol > 0) {
                    mCol--;
                }
                break;
            case '\t':
                mCol = (mCol / 8 + 1) * 8;
                break;
            case 0x1b:
                mMode = Mode.ESC;
                break;
            default:
                break; // BEL, DEL and the rest show nothing.
        }
    }

    private void escape(int b) {
        mMode = Mode.NORMAL;
        switch (b) {
            case '[':
                mParams.setLength(0);
                mMode = Mode.CSI;
                break;
            case ']':
                mMode = Mode.OSC;
                break;
            case '(':
            case ')':
                mMode = Mode.CHARSET;
                break;
            case 'c':
                reset();
                break;
            default:
                break; // Keypad modes, save/restore cursor and the like are not kept.
        }
    }

    private void csi(char command, String params) {
        boolean privateMode = params.startsWith("?") || params.startsWith(">");
        int[] p = parse(privateMode ? params.substring(1) : params);
        int n = Math.max(1, p.length > 0 ? p[0] : 1);
        Line line = mLines.get(mRow);
        switch (command) {
            case 'm':
                if (!privateMode) {
                    sgr(p);
                }
                break;
            case 'K': {
                int mode = p.length > 0 ? p[0] : 0;
                if (mode == 0) {
                    line.length = Math.min(line.length, mCol);
                } else {
                    int end = mode == 1 ? Math.min(mCol + 1, line.length) : line.length;
                    Arrays.fill(line.chars, 0, end, ' ');
                    if (mode == 2) {
                        line.length = 0;
                    }
                }
                break;
            }
            case 'J': {
                int mode = p.length > 0 ? p[0] : 0;
                if (mode == 2 || mode == 3) {
                    reset();
                } else if (mode == 0) {
                    line.length = Math.min(line.length, mCol);
                    while (mLines.size() > mRow + 1) {
                        mLines.remove(mLines.size() - 1);
                    }
                }
                break;
            }
            case 'A':
                mRow = Math.max(0, mRow - n);
                break;
            case 'B':
                mRow = Math.min(mLines.size() - 1, mRow + n);
                break;
            case 'C':
                mCol += n;
                break;
            case 'D':
                mCol = Math.max(0, mCol - n);
                break;
            case 'G':
                mCol = n - 1;
                break;
            case 'P':
                if (mCol < line.length) {
                    int count = Math.min(n, line.length - mCol);
                    System.arraycopy(line.chars, mCol + count, line.chars, mCol,
                            line.length - mCol - count);
                    System.arraycopy(line.styles, mCol + count, line.styles, mCol,
                            line.length - mCol - count);
                    line.length -= count;
                }
                break;
            case '@':
                if (mCol < line.length) {
                    line.ensure(line.length + n);
                    System.arraycopy(line.chars, mCol, line.chars, mCol + n, line.length - mCol);
                    System.arraycopy(line.styles, mCol, line.styles, mCol + n,
                            line.length - mCol);
                    Arrays.fill(line.chars, mCol, mCol + n, ' ');
                    Arrays.fill(line.styles, mCol, mCol + n, mStyle);
                    line.length += n;
                }
                break;
            case 'X':
                if (mCol < line.length) {
                    Arrays.fill(line.chars, mCol, Math.min(line.length, mCol + n), ' ');
                }
                break;
            case 'n':
                if (mAnswer && !privateMode && p.length > 0 && p[0] == 6) {
                    int row = Math.max(1, SCREEN_ROWS - (mLines.size() - 1 - mRow));
                    String report = "\033[" + row + ";" + (mCol + 1) + "R";
                    mResponder.reply(report.getBytes(StandardCharsets.US_ASCII));
                }
                break;
            default:
                break; // Cursor addressing and modes have no meaning in a scrollback.
        }
    }

    private void sgr(int[] p) {
        if (p.length == 0) {
            mStyle = 0;
            return;
        }
        for (int i = 0; i < p.length; i++) {
            int v = p[i];
            if (v == 0) {
                mStyle = 0;
            } else if (v == 1) {
                mStyle |= BOLD;
            } else if (v == 3) {
                mStyle |= ITALIC;
            } else if (v == 7) {
                mStyle |= INVERSE;
            } else if (v == 22) {
                mStyle &= ~BOLD;
            } else if (v == 23) {
                mStyle &= ~ITALIC;
            } else if (v == 27) {
                mStyle &= ~INVERSE;
            } else if (v >= 30 && v <= 37) {
                mStyle = withColour(mStyle, FG_SHIFT, v - 30);
            } else if (v == 39) {
                mStyle &= ~(COLOUR_MASK << FG_SHIFT);
            } else if (v >= 40 && v <= 47) {
                mStyle = withColour(mStyle, BG_SHIFT, v - 40);
            } else if (v == 49) {
                mStyle &= ~(COLOUR_MASK << BG_SHIFT);
            } else if (v >= 90 && v <= 97) {
                mStyle = withColour(mStyle, FG_SHIFT, v - 90 + 8);
            } else if (v >= 100 && v <= 107) {
                mStyle = withColour(mStyle, BG_SHIFT, v - 100 + 8);
            } else if ((v == 38 || v == 48) && i + 1 < p.length) {
                // 256-colour and true-colour forms: skip their arguments, keep the colour.
                i += p[i + 1] == 5 ? 2 : p[i + 1] == 2 ? 4 : 1;
            }
        }
    }

    private static int withColour(int style, int shift, int index) {
        return (style & ~(COLOUR_MASK << shift)) | ((index + 1) << shift);
    }

    private static int[] parse(String params) {
        if (params.isEmpty()) {
            return new int[0];
        }
        String[] parts = params.split("[;:]", -1);
        int[] values = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            int v = 0;
            for (int j = 0; j < parts[i].length(); j++) {
                char c = parts[i].charAt(j);
                if (c < '0' || c > '9') {
                    break;
                }
                v = Math.min(v * 10 + (c - '0'), 9999);
            }
            values[i] = v;
        }
        return values;
    }

    private void putCodePoint(int codePoint) {
        for (char c : Character.toChars(codePoint)) {
            put(c);
        }
    }

    private void put(char c) {
        Line line = mLines.get(mRow);
        line.padTo(mCol, 0);
        line.ensure(mCol + 1);
        line.chars[mCol] = c;
        line.styles[mCol] = mStyle;
        mCol++;
        if (mCol > line.length) {
            line.length = mCol;
        }
    }

    private void newLine() {
        mCol = 0;
        mRow++;
        if (mRow == mLines.size()) {
            mLines.add(new Line());
            if (mLines.size() > MAX_LINES) {
                mLines.remove(0);
                mRow--;
            }
        }
    }

    /** The scrollback with its colours, for a TextView. */
    CharSequence render() {
        SpannableStringBuilder out = new SpannableStringBuilder();
        for (int r = 0; r < mLines.size(); r++) {
            Line line = mLines.get(r);
            int base = out.length();
            out.append(new String(line.chars, 0, line.length));
            int i = 0;
            while (i < line.length) {
                int style = line.styles[i];
                int j = i + 1;
                while (j < line.length && line.styles[j] == style) {
                    j++;
                }
                if (style != 0) {
                    span(out, style, base + i, base + j);
                }
                i = j;
            }
            if (r + 1 < mLines.size()) {
                out.append('\n');
            }
        }
        return out;
    }

    private static void span(SpannableStringBuilder out, int style, int start, int end) {
        int fgIndex = (style >> FG_SHIFT) & COLOUR_MASK;
        int bgIndex = (style >> BG_SHIFT) & COLOUR_MASK;
        int fg = fgIndex == 0 ? DEFAULT_FG : PALETTE[fgIndex - 1];
        int bg = bgIndex == 0 ? DEFAULT_BG : PALETTE[bgIndex - 1];
        if ((style & INVERSE) != 0) {
            int t = fg;
            fg = bg;
            bg = t;
        }
        if (fg != DEFAULT_FG) {
            out.setSpan(new ForegroundColorSpan(fg), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        if (bg != DEFAULT_BG) {
            out.setSpan(new BackgroundColorSpan(bg), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        int face = ((style & BOLD) != 0 ? Typeface.BOLD : 0)
                | ((style & ITALIC) != 0 ? Typeface.ITALIC : 0);
        if (face != 0) {
            out.setSpan(new StyleSpan(face), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }
}
