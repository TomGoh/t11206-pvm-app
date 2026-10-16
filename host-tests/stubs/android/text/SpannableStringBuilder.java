package android.text;
import java.util.ArrayList;
/** Test stub: text plus a list of (span, start, end). */
public class SpannableStringBuilder implements Spanned {
    public static final class Entry { public final Object span; public final int start, end;
        Entry(Object s, int a, int b) { span = s; start = a; end = b; } }
    private final StringBuilder mText = new StringBuilder();
    public final ArrayList<Entry> spans = new ArrayList<>();
    public SpannableStringBuilder append(CharSequence s) { mText.append(s); return this; }
    public SpannableStringBuilder append(char c) { mText.append(c); return this; }
    public void setSpan(Object what, int start, int end, int flags) { spans.add(new Entry(what, start, end)); }
    public int length() { return mText.length(); }
    public char charAt(int i) { return mText.charAt(i); }
    public CharSequence subSequence(int a, int b) { return mText.subSequence(a, b); }
    public String toString() { return mText.toString(); }
}
