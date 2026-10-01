package com.amap.adblocker;

import android.app.Activity;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import java.io.File;
import java.io.File;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.TextView;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {

    public static final String TARGET_PKG = "com.autonavi.minimap";
    public static final String ACTION_CMD = "com.amap.adblocker.CMD";

    static RuleSet rules;
    static String currentActivity = "";
    static Handler mainHandler;
    static Application app;

    private static final Map<View, Set<String>> applied = new WeakHashMap<View, Set<String>>();

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!TARGET_PKG.equals(lpparam.packageName)) return;
        // 只在主进程（UI 进程）注入
        if (!TARGET_PKG.equals(lpparam.processName)) return;

        rules = RuleSet.load();
        XLog.i("loaded into " + lpparam.processName + " rules=" + rules.rules.size() + " src=" + RuleSet.lastSource);

        hookAjxLabelSetters(lpparam.classLoader);

        try {
            XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    app = (Application) param.thisObject;
                    mainHandler = new Handler(Looper.getMainLooper());
                    try {
                        XLog.file = new File(app.getFilesDir(), "amap_adblock_dump.txt");
                    } catch (Throwable t) {
                    }
                    rules = RuleSet.load();
                    registerReceiver(app);
                    XLog.i("app ready, rules=" + rules.rules.size() + " src=" + RuleSet.lastSource);
                }
            });
        } catch (Throwable t) {
            XLog.e("hook Application.onCreate: " + t);
        }

        try {
            XposedHelpers.findAndHookMethod(Activity.class, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    currentActivity = param.thisObject.getClass().getName();
                    if (rules != null && rules.debug) XLog.i("resume " + currentActivity);
                }
            });
        } catch (Throwable t) {
            XLog.e("hook Activity.onResume: " + t);
        }

        // 1) 子 view 被加入容器时
        final XC_MethodHook addHook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                try {
                    Object[] a = param.args;
                    if (a == null || a.length == 0) return;
                    Object o = a[0];
                    if (o instanceof View) evaluate((View) o, "addView");
                } catch (Throwable t) {
                    // ignore
                }
            }
        };
        hook(ViewGroup.class, "addView", addHook, View.class);
        hook(ViewGroup.class, "addView", addHook, View.class, int.class);
        hook(ViewGroup.class, "addView", addHook, View.class, ViewGroup.LayoutParams.class);
        hook(ViewGroup.class, "addView", addHook, View.class, int.class, ViewGroup.LayoutParams.class);

        // 2) 文本被设置时（动态下发的内容大多在这里才能识别）
        final XC_MethodHook textHook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                try {
                    if (param.thisObject instanceof View) evaluate((View) param.thisObject, "setText");
                } catch (Throwable t) {
                }
            }
        };
        hook(TextView.class, "setText", textHook, CharSequence.class);
        try {
            hook(TextView.class, "setText", textHook, CharSequence.class, TextView.BufferType.class);
        } catch (Throwable t) {
        }

        // 3) 兜底：view 挂载到窗口
        try {
            XposedHelpers.findAndHookMethod(View.class, "onAttachedToWindow", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    try {
                        evaluate((View) param.thisObject, "attach");
                    } catch (Throwable t) {
                    }
                }
            });
        } catch (Throwable t) {
            XLog.e("hook onAttachedToWindow: " + t);
        }

        // 4) 应用主动把目标设为可见时，重新压下去
        try {
            XposedHelpers.findAndHookMethod(View.class, "setVisibility", int.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    try {
                        if (rules == null || rules.rules.isEmpty()) return;
                        Object v = param.thisObject;
                        if (!(v instanceof View)) return;
                        if (param.args[0] == null) return;
                        if (((Integer) param.args[0]).intValue() != View.VISIBLE) return;
                        View view = (View) v;
                        if (matchesAny(view)) param.args[0] = Integer.valueOf(View.GONE);
                    } catch (Throwable t) {
                    }
                }
            });
        } catch (Throwable t) {
            XLog.e("hook setVisibility: " + t);
        }
    }

    /**
     * AJX 自绘 Label 的文本不经过 TextView，这里枚举其"疑似设置文本"的方法并挂钩，
     * 使文本类规则能在运行时被再次评估。
     */
    private static void hookAjxLabelSetters(ClassLoader cl) {
        try {
            Class<?> label = XposedHelpers.findClass("com.autonavi.minimap.ajx3.widget.view.Label", cl);
            int hooked = 0;
            Class<?> c = label;
            while (c != null && !c.getName().startsWith("android.view") && !c.getName().startsWith("android.widget")) {
                for (final Method m : c.getDeclaredMethods()) {
                    Class<?>[] ps = m.getParameterTypes();
                    if (ps.length != 1) continue;
                    if (ps[0] != String.class && ps[0] != CharSequence.class) continue;
                    String n = m.getName().toLowerCase();
                    if (!(n.contains("text") || n.contains("title") || n.contains("label") || n.contains("content"))) {
                        continue;
                    }
                    try {
                        XposedHelpers.findAndHookMethod(label, m.getName(), ps[0], new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                                if (param.thisObject instanceof View) {
                                    evaluate((View) param.thisObject, "ajxText");
                                }
                            }
                        });
                        hooked++;
                    } catch (Throwable t) {
                    }
                }
                c = c.getSuperclass();
            }
            XLog.i("ajx label setters hooked=" + hooked);
        } catch (Throwable t) {
            XLog.e("hookAjxLabelSetters: " + t);
        }
    }

    private static void hook(Class<?> cls, String method, XC_MethodHook hook, Class<?>... params) {
        try {
            Object[] args = new Object[params.length + 1];
            System.arraycopy(params, 0, args, 0, params.length);
            args[params.length] = hook;
            XposedHelpers.findAndHookMethod(cls, method, args);
        } catch (Throwable t) {
            XLog.e("hook " + cls.getSimpleName() + "." + method + ": " + t);
        }
    }

    // ---------------- 核心匹配 ----------------

    static void evaluate(View v, String from) {
        if (v == null || rules == null || rules.rules.isEmpty()) return;
        if (v.getVisibility() == View.GONE) return;

        String idn = ViewUtil.idName(v);
        String cls = v.getClass().getName();
        String text = ViewUtil.textOf(v);
        String desc = ViewUtil.descOf(v);
        String tree = null;

        if (rules.verbose && (idn != null || text != null)) {
            ViewUtil.dumpTree(v, "V-" + from + " id=" + idn + " cls=" + cls);
        }

        for (int i = 0; i < rules.rules.size(); i++) {
            Rule r = rules.rules.get(i);
            if (Rule.T_ROW.equals(r.type)) {
                handleRow(r, v, text != null ? text : desc);
                continue;
            }
            if (Rule.T_SLICE.equals(r.type)) {
                handleSlice(r, v, text != null ? text : desc);
                continue;
            }
            if (Rule.T_RECT.equals(r.type)) {
                scheduleRect(r, v, idn, cls, text);
                continue;
            }
            if (!r.interesting(v)) continue;
            if (r.activity != null && !currentActivity.toLowerCase().contains(r.activity.toLowerCase())) {
                continue;
            }
            boolean hit;
            if (Rule.T_TREE.equals(r.type)) {
                if (tree == null) tree = ViewUtil.treeText(v);
                hit = r.hits(tree);
            } else {
                hit = r.matches(v, idn, cls, text, tree, desc);
            }
            if (hit) apply(r, v, idn, cls, text);
        }
    }

    /** 纯匹配，用于 setVisibility 拦截 */
    static boolean matchesAny(View v) {
        if (v.getVisibility() == View.GONE) return false;
        String idn = ViewUtil.idName(v);
        String cls = v.getClass().getName();
        String text = ViewUtil.textOf(v);
        String desc = ViewUtil.descOf(v);
        String tree = null;
        for (int i = 0; i < rules.rules.size(); i++) {
            Rule r = rules.rules.get(i);
            if (Rule.T_ROW.equals(r.type) || Rule.T_SLICE.equals(r.type)) continue;
            if (!r.interesting(v)) continue;
            if (Rule.T_TREE.equals(r.type)) {
                if (tree == null) tree = ViewUtil.treeText(v);
                if (r.hits(tree)) return true;
            } else if (r.matches(v, idn, cls, text, tree, desc)) {
                return true;
            }
        }
        return false;
    }

    /** 区域规则依赖布局后的坐标，延迟到 layout 完成再判断 */
    static void scheduleRect(final Rule r, final View v, final String idn, final String cls, final String text) {
        if (v.getWidth() > 0 && v.getHeight() > 0) {
            tryRect(r, v, idn, cls, text);
            return;
        }
        if (!markApplied(v, "rect-sched-" + r.name)) return;
        v.postDelayed(new Runnable() {
            public void run() {
                tryRect(r, v, idn, cls, text);
            }
        }, 500);
    }

    static void tryRect(Rule r, View v, String idn, String cls, String text) {
        if (r.rectHit(v, cls) && r.hasAncestor(v)) {
            apply(r, v, idn, cls, text);
        }
    }

    static void apply(Rule r, View v, String idn, String cls, String text) {
        if (!markApplied(v, r.name)) return;
        View target = ViewUtil.climb(v, r.up, r.parentClass);
        if (rules.debug || r.name.startsWith("!")) {
            XLog.i("HIT " + r.name + " on id=" + idn + " cls=" + cls + " txt=" + text
                    + " -> hide " + target.getClass().getName() + "/" + ViewUtil.idName(target));
        }
        if ("remove".equals(r.action)) ViewUtil.remove(target);
        else if ("collapse".equals(r.action)) ViewUtil.collapse(target);
        else if ("click".equals(r.action)) ViewUtil.click(target);
        else ViewUtil.setGone(target);
    }

    /** 容器过滤：命中 anchor 后，容器里只保留 keep 列出的条目 */
    static void handleRow(Rule r, View v, String text) {
        if (text == null || r.anchor == null) return;
        boolean hitAnchor = false;
        String[] anchors = r.anchor.split("[,，]");
        for (int i = 0; i < anchors.length; i++) {
            String a = anchors[i].trim();
            if (a.length() > 0 && text.contains(a)) {
                hitAnchor = true;
                break;
            }
        }
        if (!hitAnchor) return;
        if (r.activity != null && !currentActivity.toLowerCase().contains(r.activity.toLowerCase())) return;
        if (!r.hasAncestor(v)) return;

        View row = ViewUtil.climb(v, r.climb, r.parentClass);
        if (!(row instanceof ViewGroup)) return;
        ViewGroup g = (ViewGroup) row;
        int n = g.getChildCount();
        if (n < r.minChildren) return;

        int hidden = 0;
        for (int i = 0; i < n; i++) {
            View child = g.getChildAt(i);
            if (child == null || child.getVisibility() == View.GONE) continue;
            String t = ViewUtil.treeText(child);
            if (t == null) t = ViewUtil.firstText(child);
            if (t == null) continue;
            boolean keep = false;
            for (int k = 0; k < r.keep.size(); k++) {
                if (t.contains(r.keep.get(k))) {
                    keep = true;
                    break;
                }
            }
            if (!keep) {
                hidden++;
                if (rules.debug) XLog.i("ROW[" + r.name + "] hide child \"" + t + "\"");
                ViewUtil.setGone(child);
                if (r.pack) ViewUtil.packRow(child);
            }
        }
        if (hidden > 0 && rules.debug) {
            XLog.i("ROW[" + r.name + "] hid " + hidden + "/" + n + " children of "
                    + g.getClass().getName() + "/" + ViewUtil.idName(g));
        }
    }

    /**
     * 整段切除：以 anchor 文本/描述定位到一个块，把它所在列表里 [idx+from, idx+to] 的兄弟
     * 全部隐藏，并把后续兄弟整体上移补位，消除空洞。
     */
    static void handleSlice(final Rule r, final View v, String text) {
        if (r.anchor == null || r.anchor.length() == 0) return;
        String t = text != null ? text : ViewUtil.descOf(v);
        if (t == null) return;
        String[] anchors = r.anchor.split("[,，]");
        boolean hit = false;
        for (int i = 0; i < anchors.length; i++) {
            String a = anchors[i].trim();
            if (a.length() > 0 && t.contains(a)) {
                hit = true;
                break;
            }
        }
        if (!hit) return;
        if (!r.hasAncestor(v)) return;

        View block = ViewUtil.climb(v, 12, r.parentClass);
        ViewParent pp = block.getParent();
        if (!(pp instanceof ViewGroup)) return;
        final ViewGroup list = (ViewGroup) pp;
        final int idx = list.indexOfChild(block);
        if (idx < 0) return;
        if (!markApplied(list, "slice-" + r.name)) return;

        list.postDelayed(new Runnable() {
            public void run() {
                doSlice(r, list, idx);
            }
        }, 800);
        // 布局可能分帧完成，再兜一次
        list.postDelayed(new Runnable() {
            public void run() {
                doSlice(r, list, idx);
            }
        }, 2500);
    }

    static void doSlice(Rule r, ViewGroup list, int idx) {
        int n = list.getChildCount();
        int from = idx + r.from;
        int to = idx + r.to;
        if (from < 0) from = 0;
        if (to >= n) to = n - 1;
        if (from > to) return;

        // 先量出要抹掉的垂直跨度，再隐藏，最后把下方内容上移
        View first = null;
        for (int i = from; i <= to; i++) {
            View c = list.getChildAt(i);
            if (c != null && c.getVisibility() != View.GONE && c.getHeight() > 0) {
                first = c;
                break;
            }
        }
        if (first == null) {
            // 已经隐藏过了，只做上移补位
        }
        View after = null;
        for (int i = to + 1; i < n; i++) {
            View c = list.getChildAt(i);
            if (c != null && c.getVisibility() != View.GONE && c.getHeight() > 0) {
                after = c;
                break;
            }
        }

        int dy = 0;
        if (first != null && after != null) {
            int[] a = new int[2];
            int[] b = new int[2];
            first.getLocationOnScreen(a);
            after.getLocationOnScreen(b);
            dy = b[1] - a[1];
        }

        int hidden = 0;
        for (int i = from; i <= to; i++) {
            View c = list.getChildAt(i);
            if (c == null) continue;
            if (c.getVisibility() != View.GONE) {
                hidden++;
                ViewUtil.setGone(c);
            }
        }

        if (dy > 0 && hidden > 0) {
            for (int i = to + 1; i < n; i++) {
                View c = list.getChildAt(i);
                if (c == null) continue;
                c.setTranslationY(c.getTranslationY() - dy);
            }
            if (rules.debug) {
                XLog.i("SLICE[" + r.name + "] hid " + hidden + " items idx " + from + ".." + to + " shift=-" + dy);
            }
        }
    }

    private static boolean markApplied(View v, String ruleName) {
        synchronized (applied) {
            Set<String> s = applied.get(v);
            if (s == null) {
                s = new HashSet<String>(2);
                applied.put(v, s);
            }
            return s.add(ruleName);
        }
    }

    // ---------------- 调试命令 ----------------

    private static void registerReceiver(Application app) {
        try {
            IntentFilter f = new IntentFilter();
            f.addAction(ACTION_CMD);
            BroadcastReceiver recv = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    String cmd = intent.getStringExtra("cmd");
                    if (cmd == null) cmd = "dump";
                    XLog.i("cmd=" + cmd);
                    if ("reload".equals(cmd)) {
                        rules = RuleSet.load();
                        synchronized (applied) {
                            applied.clear();
                        }
                        XLog.i("reloaded rules=" + rules.rules.size() + " src=" + RuleSet.lastSource);
                    } else if ("roots".equals(cmd)) {
                        for (View r : getRoots()) {
                            XLog.i("ROOT " + r.getClass().getName() + " ctx="
                                    + (r.getContext() == null ? "" : r.getContext().getClass().getName()));
                        }
                    } else {
                        String arg = intent.getStringExtra("arg");
                        XLog.resetFile();
                        XLog.silent = true;
                        ArrayList<View> roots = getRoots();
                        if ("deep".equals(cmd)) {
                            for (int i = 0; i < roots.size(); i++) {
                                ViewUtil.dumpTree(roots.get(i), "root#" + i + " act=" + currentActivity, 26, 20000);
                            }
                        } else if (arg != null) {
                            for (int i = 0; i < roots.size(); i++) {
                                View hit = "text".equals(cmd)
                                        ? ViewUtil.findByText(roots.get(i), arg)
                                        : ViewUtil.findByIdName(roots.get(i), arg);
                                if (hit != null) {
                                    ViewUtil.dumpTree(hit, "found " + cmd + "=" + arg, 26, 20000);
                                }
                            }
                        } else {
                            for (int i = 0; i < roots.size(); i++) {
                                ViewUtil.dumpTree(roots.get(i), "root#" + i + " act=" + currentActivity);
                            }
                        }
                        XLog.silent = false;
                        XLog.i("dump written to " + (XLog.file == null ? "(none)" : XLog.file.getAbsolutePath()));
                    }
                }
            };
            registerCompat(app, recv, f);
            XLog.i("receiver registered: " + ACTION_CMD);
        } catch (Throwable t) {
            XLog.e("registerReceiver: " + t);
        }
    }

    /** Android 13+ 注册非系统广播必须显式声明 exported 标记 */
    private static void registerCompat(Context ctx, BroadcastReceiver recv, IntentFilter filter) {
        try {
            int flag = 0;
            try {
                Field f = Context.class.getField("RECEIVER_EXPORTED");
                flag = f.getInt(null);
            } catch (Throwable t) {
                flag = 2;
            }
            Method m = Context.class.getMethod("registerReceiver",
                    BroadcastReceiver.class, IntentFilter.class, int.class);
            m.invoke(ctx, recv, filter, Integer.valueOf(flag));
        } catch (Throwable t) {
            try {
                ctx.registerReceiver(recv, filter);
            } catch (Throwable t2) {
                XLog.e("registerReceiver fallback: " + t2);
            }
        }
    }

    @SuppressWarnings("unchecked")
    public static ArrayList<View> getRoots() {
        ArrayList<View> out = new ArrayList<View>();
        try {
            Class<?> wmg = Class.forName("android.view.WindowManagerGlobal");
            Method gm = wmg.getDeclaredMethod("getInstance");
            gm.setAccessible(true);
            Object inst = gm.invoke(null);
            Field mv = wmg.getDeclaredField("mViews");
            mv.setAccessible(true);
            Object val = mv.get(inst);
            if (val instanceof ArrayList) {
                out.addAll((ArrayList<View>) val);
            } else if (val instanceof View[]) {
                for (View v : (View[]) val) out.add(v);
            }
        } catch (Throwable t) {
            XLog.e("getRoots: " + t);
        }
        return out;
    }
}
