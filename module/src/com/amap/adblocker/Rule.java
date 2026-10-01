package com.amap.adblocker;

import android.view.View;
import android.view.ViewParent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 一条隐藏规则。
 *
 * type:
 *   id    - 匹配 view 的 resource entry name
 *   text  - 匹配 view 自身的文字（TextView 文本）
 *   desc  - 匹配 contentDescription
 *   class - 匹配 view 的类名
 *   tree  - 匹配 view 及其所有子 view 的文本拼接
 *   row   - 容器过滤：命中 anchor 后向上 climb 层找容器，只保留 keep 里的条目
 *   rect  - 屏幕区域规则：类名匹配且中心点落在区域内
 *
 * match: contains | equals | startsWith | endsWith | regex | in(逗号分隔)
 * up:    命中后向上爬几层再执行动作（0 = 自身）
 * parentClass: 向上爬时的目标父容器类名（包含匹配），配合 up 使用
 * parentCls:  要求当前 view 的父类名包含该串（过滤条件，不爬升）
 * minIndex/maxIndex: 要求 view 在父容器中的索引范围
 * action: gone | remove | click | collapse
 * pack:   row 规则隐藏后是否把剩余可见子 view 向左压实
 */
public class Rule {

    public static final String T_ID = "id";
    public static final String T_TEXT = "text";
    public static final String T_DESC = "desc";
    public static final String T_CLASS = "class";
    public static final String T_TREE = "tree";
    public static final String T_ROW = "row";
    public static final String T_RECT = "rect";
    public static final String T_SLICE = "slice";
    public static final String T_CARD = "card";

    public String name = "";
    public String type = T_ID;
    public String value = "";
    public String match = "contains";
    public int up = 0;
    public String parentClass = null;
    public String parentCls = null;
    public String ancestor = null;
    public int minIndex = -1;
    public int maxIndex = -1;
    public String action = "gone";
    public String activity = null;
    public boolean enabled = true;
    public boolean pack = false;

    // rect
    public int left, top, right, bottom;

    // row
    public String anchor = null;
    public List<String> keep = new ArrayList<String>();
    public int climb = 2;
    public int minChildren = 2;

    // slice：以 anchor 定位块，隐藏 [idx+from, idx+to] 并把后续兄弟上移补位
    public int from = 0;
    public int to = 0;
    // slice：剩余内容顶部与参照容器的间距(px)，动态保持（容器移动时跟随）
    public int gap = -1;

    // card：隐藏 anchor 行及其后 hideNext 个兄弟，行下方内容上移补位，并收缩 cardLevels 层祖先高度
    public int hideNext = 0;
    public int cardLevels = 0;

    private Pattern pattern;
    private String[] inList;
    private String lower;

    public void compile() {
        if ("regex".equals(match)) {
            try {
                pattern = Pattern.compile(value, Pattern.CASE_INSENSITIVE);
            } catch (Throwable t) {
                pattern = null;
            }
        } else if ("in".equals(match)) {
            String[] parts = value.split("[,，]");
            inList = new String[parts.length];
            for (int i = 0; i < parts.length; i++) inList[i] = parts[i].trim().toLowerCase();
        } else {
            lower = value == null ? "" : value.toLowerCase();
        }
    }

    public boolean hits(String candidate) {
        if (candidate == null) return false;
        if ("regex".equals(match)) {
            return pattern != null && pattern.matcher(candidate).find();
        }
        if ("in".equals(match)) {
            String c = candidate.toLowerCase();
            for (int i = 0; i < inList.length; i++) {
                if (c.equals(inList[i])) return true;
            }
            return false;
        }
        String c = candidate.toLowerCase();
        if ("equals".equals(match)) return c.equals(lower);
        if ("startsWith".equals(match)) return c.startsWith(lower);
        if ("endsWith".equals(match)) return c.endsWith(lower);
        return c.contains(lower);
    }

    /** 该规则是否可能作用于给定的 view（用于快速跳过） */
    public boolean interesting(View v) {
        if (T_ID.equals(type)) return v.getId() > 0;
        if (T_TEXT.equals(type) || T_TREE.equals(type)) return true;
        if (T_DESC.equals(type)) return v.getContentDescription() != null;
        if (T_SLICE.equals(type)) return v.getContentDescription() != null;
        if (T_ROW.equals(type)) return true;
        if (T_RECT.equals(type)) return true;
        return true;
    }

    public boolean inRange(View v) {
        if (!hasAncestor(v)) return false;
        if (parentCls != null) {
            android.view.ViewParent p = v.getParent();
            if (!(p instanceof View)) return false;
            if (!((View) p).getClass().getName().toLowerCase().contains(parentCls.toLowerCase())) return false;
        }
        if (minIndex >= 0 || maxIndex >= 0) {
            android.view.ViewParent p = v.getParent();
            if (!(p instanceof android.view.ViewGroup)) return false;
            int idx = ((android.view.ViewGroup) p).indexOfChild(v);
            if (minIndex >= 0 && idx < minIndex) return false;
            if (maxIndex >= 0 && idx > maxIndex) return false;
        }
        return true;
    }

    /** 纯匹配判断，不产生副作用 */
    public boolean matches(View v, String idName, String cls, String text, String tree, String desc) {
        if (!inRange(v)) return false;
        if (T_ID.equals(type)) return hits(idName);
        if (T_CLASS.equals(type)) return hits(cls);
        if (T_DESC.equals(type)) return hits(desc);
        if (T_TEXT.equals(type)) return hits(text);
        if (T_TREE.equals(type)) return hits(tree);
        if (T_RECT.equals(type)) return rectHit(v, cls);
        return false;
    }

    /** 是否存在类名包含 ancestor 的祖先 view */
    public boolean hasAncestor(View v) {
        if (ancestor == null || ancestor.length() == 0) return true;
        String a = ancestor.toLowerCase();
        ViewParent p = v.getParent();
        int guard = 0;
        while (p instanceof View && guard++ < 40) {
            if (((View) p).getClass().getName().toLowerCase().contains(a)) return true;
            p = ((View) p).getParent();
        }
        return false;
    }

    public boolean rectHit(View v, String cls) {
        if (value != null && value.length() > 0 && !cls.toLowerCase().contains(value.toLowerCase())) {
            return false;
        }
        int[] loc = new int[2];
        try {
            v.getLocationOnScreen(loc);
        } catch (Throwable t) {
            return false;
        }
        int cx = loc[0] + v.getWidth() / 2;
        int cy = loc[1] + v.getHeight() / 2;
        return cx >= left && cx <= right && cy >= top && cy <= bottom;
    }

    public static Rule from(JSONObject o) {
        Rule r = new Rule();
        r.name = o.optString("name", "");
        r.type = o.optString("type", T_ID);
        r.value = o.optString("value", "");
        r.match = o.optString("match", "contains");
        r.up = o.optInt("up", 0);
        if (o.has("parentClass")) r.parentClass = o.optString("parentClass", null);
        if (o.has("parentCls")) r.parentCls = o.optString("parentCls", null);
        if (o.has("ancestor")) r.ancestor = o.optString("ancestor", null);
        r.minIndex = o.optInt("minIndex", -1);
        r.maxIndex = o.optInt("maxIndex", -1);
        r.action = o.optString("action", "gone");
        if (o.has("activity")) r.activity = o.optString("activity", null);
        r.enabled = o.optBoolean("enabled", true);
        r.pack = o.optBoolean("pack", false);
        r.left = o.optInt("left", 0);
        r.top = o.optInt("top", 0);
        r.right = o.optInt("right", Integer.MAX_VALUE);
        r.bottom = o.optInt("bottom", Integer.MAX_VALUE);

        if (T_ROW.equals(r.type) || T_SLICE.equals(r.type) || T_CARD.equals(r.type)) {
            r.anchor = o.optString("anchor", "");
            r.climb = o.optInt("climb", 2);
            r.minChildren = o.optInt("minChildren", 2);
            JSONArray k = o.optJSONArray("keep");
            if (k != null) {
                for (int i = 0; i < k.length(); i++) r.keep.add(k.optString(i, ""));
            }
        }
        if (T_SLICE.equals(r.type)) {
            r.from = o.optInt("from", 0);
            r.to = o.optInt("to", 0);
            r.gap = o.optInt("gap", -1);
        }
        if ("card".equals(r.type)) {
            r.hideNext = o.optInt("hideNext", 0);
            r.cardLevels = o.optInt("cardLevels", 0);
        }
        r.compile();
        return r;
    }

    @Override
    public String toString() {
        return name + "[" + type + "=" + value + ",up=" + up + ",action=" + action + "]";
    }
}
