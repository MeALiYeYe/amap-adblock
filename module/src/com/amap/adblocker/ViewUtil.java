package com.amap.adblocker;

import android.content.res.Resources;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.TextView;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

public class ViewUtil {

    private static final Map<Integer, String> idCache = new HashMap<Integer, String>();

    /** view 的 resource entry name，无 id 返回 null */
    public static String idName(View v) {
        int id = v.getId();
        if (id <= 0 || (id & 0xFF000000) == 0) return null;
        String cached;
        synchronized (idCache) {
            cached = idCache.get(Integer.valueOf(id));
        }
        if (cached != null) return cached;
        String n = null;
        try {
            Resources r = v.getResources();
            if (r != null) n = r.getResourceEntryName(id);
        } catch (Throwable t) {
            n = null;
        }
        synchronized (idCache) {
            if (idCache.size() < 4096) idCache.put(Integer.valueOf(id), n);
        }
        return n;
    }

    public static String textOf(View v) {
        if (v instanceof TextView) {
            CharSequence cs = ((TextView) v).getText();
            if (cs != null) {
                String s = cs.toString();
                if (s.length() > 0) return s;
            }
        }
        // AJX 自绘 Label（文本不走 TextView），反射读字段
        String ajx = labelText(v);
        return ajx;
    }

    private static final Map<String, Field[]> fieldCache = new HashMap<String, Field[]>();

    /** 反射扫描 view 字段里的 String/CharSequence，拼接为可匹配文本 */
    public static String labelText(View v) {
        String cn = v.getClass().getName();
        boolean ajx = cn.startsWith("com.autonavi.minimap.ajx3") && cn.endsWith(".Label");
        if (!ajx) return null;
        Field[] fs;
        synchronized (fieldCache) {
            fs = fieldCache.get(cn);
        }
        if (fs == null) {
            ArrayList<Field> list = new ArrayList<Field>();
            Class<?> c = v.getClass();
            int guard = 0;
            while (c != null && guard++ < 6) {
                for (Field f : c.getDeclaredFields()) {
                    f.setAccessible(true);
                    Class<?> t = f.getType();
                    if (t == String.class || t == CharSequence.class) list.add(f);
                }
                c = c.getSuperclass();
            }
            fs = list.toArray(new Field[list.size()]);
            synchronized (fieldCache) {
                fieldCache.put(cn, fs);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < fs.length; i++) {
            try {
                Object o = fs[i].get(v);
                if (o == null) continue;
                String s = o.toString();
                if (s.length() == 0 || s.length() > 200) continue;
                boolean digitOnly = true;
                for (int k = 0; k < s.length(); k++) {
                    if (!Character.isDigit(s.charAt(k)) && s.charAt(k) != '.') {
                        digitOnly = false;
                        break;
                    }
                }
                if (digitOnly) continue;
                if (sb.length() > 0) sb.append('|');
                sb.append(s);
                if (sb.length() > 200) break;
            } catch (Throwable t) {
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    public static String descOf(View v) {
        CharSequence d = v.getContentDescription();
        return d == null ? null : d.toString();
    }

    /** 收集 view 自身及其子 view 的文本，depth<=4，长度上限 300 */
    public static String treeText(View v) {
        StringBuilder sb = new StringBuilder();
        collect(v, sb, 0, 4, 300);
        return sb.length() == 0 ? null : sb.toString();
    }

    private static void collect(View v, StringBuilder sb, int depth, int maxDepth, int maxLen) {
        if (v == null || depth > maxDepth || sb.length() > maxLen) return;
        if (v.getVisibility() == View.GONE) return;
        String t = textOf(v);
        if (t != null && t.length() > 0) {
            if (sb.length() > 0) sb.append('|');
            sb.append(t);
        } else {
            CharSequence d = v.getContentDescription();
            if (d != null && d.length() > 0) {
                if (sb.length() > 0) sb.append('|');
                sb.append(d.toString());
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            int n = g.getChildCount();
            if (n > 40) n = 40;
            for (int i = 0; i < n; i++) collect(g.getChildAt(i), sb, depth + 1, maxDepth, maxLen);
        }
    }

    /** 第一个可识别的条目名：优先 TextView 文本，其次 contentDescription，再向下找 */
    public static String firstText(View v) {
        if (v == null) return null;
        String t = textOf(v);
        if (t != null) return t;
        CharSequence d = v.getContentDescription();
        if (d != null && d.length() > 0) return d.toString();
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            int n = g.getChildCount();
            if (n > 20) n = 20;
            for (int i = 0; i < n; i++) {
                String s = firstText(g.getChildAt(i));
                if (s != null) return s;
            }
        }
        return null;
    }

    /** 把一行容器里剩余的可见子 view 向左压实（AJX 绝对布局隐藏后会留空洞） */
    public static void packRow(View anchor) {
        try {
            ViewParent p = anchor.getParent();
            if (!(p instanceof ViewGroup)) return;
            ViewGroup row = (ViewGroup) p;
            int n = row.getChildCount();
            if (n < 2) return;
            ArrayList<View> vis = new ArrayList<View>();
            ArrayList<Integer> xs = new ArrayList<Integer>();
            for (int i = 0; i < n; i++) {
                View c = row.getChildAt(i);
                if (c == null || c.getVisibility() != View.VISIBLE) continue;
                int[] loc = new int[2];
                c.getLocationOnScreen(loc);
                vis.add(c);
                xs.add(Integer.valueOf(loc[0]));
            }
            int m = vis.size();
            if (m < 1) return;
            int spacing = m >= 2 ? (xs.get(1).intValue() - xs.get(0).intValue()) : 0;
            int base = xs.get(0).intValue();
            for (int i = 0; i < m; i++) {
                View c = vis.get(i);
                int target = base + i * spacing;
                int dx = target - xs.get(i).intValue();
                if (dx != 0) c.setTranslationX(c.getTranslationX() + dx);
            }
        } catch (Throwable t) {
            XLog.e("packRow: " + t);
        }
    }

    /** 向上爬 n 层；若指定 parentClass 则在到达该类名的祖先时停止 */
    public static View climb(View v, int n, String parentClass) {
        View cur = v;
        for (int i = 0; i < n; i++) {
            ViewParent p = cur.getParent();
            if (!(p instanceof View)) break;
            cur = (View) p;
            if (parentClass != null && cur.getClass().getName().toLowerCase().contains(parentClass.toLowerCase())) {
                return cur;
            }
        }
        return cur;
    }

    public static void setGone(View v) {
        if (v == null) return;
        try {
            if (v.getVisibility() != View.GONE) v.setVisibility(View.GONE);
        } catch (Throwable t) {
            XLog.e("setGone: " + t);
        }
    }

    public static void remove(View v) {
        if (v == null) return;
        try {
            ViewParent p = v.getParent();
            if (p instanceof ViewGroup) ((ViewGroup) p).removeView(v);
            else setGone(v);
        } catch (Throwable t) {
            XLog.e("remove: " + t);
        }
    }

    public static void collapse(View v) {
        if (v == null) return;
        try {
            ViewGroup.LayoutParams lp = v.getLayoutParams();
            if (lp != null) {
                lp.height = 0;
                v.setLayoutParams(lp);
            }
            setGone(v);
        } catch (Throwable t) {
            setGone(v);
        }
    }

    public static void click(View v) {
        if (v == null) return;
        try {
            v.post(new Runnable() {
                public void run() {
                    // handled below
                }
            });
            v.performClick();
        } catch (Throwable t) {
            XLog.e("click: " + t);
        }
    }

    /** 打印整棵视图树，用于反查 id / 文本，供规则调优 */
    public static void dumpTree(View root, String tag) {
        dumpTree(root, tag, 12, 3000);
    }

    public static void dumpTree(View root, String tag, int maxDepth, int budget) {
        StringBuilder sb = new StringBuilder();
        sb.append("===== DUMP ").append(tag).append(" =====");
        dump(root, sb, 0, 0, maxDepth, budget);
        XLog.i(sb.toString());
    }

    /** 按 resource entry name 查找子树 */
    public static View findByIdName(View root, String name) {
        if (root == null || name == null) return null;
        String n = idName(root);
        if (n != null && n.equals(name)) return root;
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            int c = g.getChildCount();
            if (c > 80) c = 80;
            for (int i = 0; i < c; i++) {
                View r = findByIdName(g.getChildAt(i), name);
                if (r != null) return r;
            }
        }
        return null;
    }

    /** 按文本查找 */
    public static View findByText(View root, String text) {
        if (root == null || text == null) return null;
        String t = textOf(root);
        if (t != null && t.contains(text)) return root;
        CharSequence d = root.getContentDescription();
        if (d != null && d.toString().contains(text)) return root;
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            int c = g.getChildCount();
            if (c > 80) c = 80;
            for (int i = 0; i < c; i++) {
                View r = findByText(g.getChildAt(i), text);
                if (r != null) return r;
            }
        }
        return null;
    }

    private static void dump(View v, StringBuilder sb, int depth, int index, int maxDepth, int budget) {
        if (v == null || depth > maxDepth || sb.length() > budget * 12) return;
        sb.append('\n');
        for (int i = 0; i < depth; i++) sb.append("  ");
        String idn = idName(v);
        int[] loc = new int[2];
        try {
            v.getLocationOnScreen(loc);
        } catch (Throwable t) {
        }
        sb.append('[').append(depth).append(':').append(index).append("] ")
                .append(v.getClass().getName().replace("android.widget.", "w.").replace("android.view.", "v."))
                .append(idn == null ? "" : (" id=" + idn))
                .append(" vis=").append(vis(v.getVisibility()));
        String t = textOf(v);
        if (t != null) sb.append(" txt=\"").append(t.length() > 40 ? t.substring(0, 40) : t).append('"');
        CharSequence d = descOf(v);
        if (d != null) sb.append(" dsc=\"").append(d.length() > 30 ? d.subSequence(0, 30) : d).append('"');
        sb.append(" xy=").append(loc[0]).append(',').append(loc[1])
                .append(" wh=").append(v.getWidth()).append('x').append(v.getHeight());
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            int n = g.getChildCount();
            if (n > 60) n = 60;
            for (int i = 0; i < n; i++) dump(g.getChildAt(i), sb, depth + 1, i, maxDepth, budget);
        }
    }

    private static String vis(int v) {
        if (v == View.VISIBLE) return "V";
        if (v == View.INVISIBLE) return "I";
        return "G";
    }
}
