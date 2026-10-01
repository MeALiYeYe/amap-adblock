package com.amap.adblocker;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

public class RuleSet {

    public boolean debug = false;
    public boolean verbose = false;
    public List<Rule> rules = new ArrayList<Rule>();

    public static final String[] CONFIG_PATHS = new String[]{
            "/sdcard/Download/amap_adblock.json",
            "/sdcard/amap_adblock.json",
            "/data/local/tmp/amap_adblock.json"
    };

    public static String lastSource = "builtin";

    public static RuleSet load() {
        for (String p : CONFIG_PATHS) {
            try {
                File f = new File(p);
                if (!f.exists() || f.length() <= 0 || f.length() > 1024 * 512) continue;
                String s = readFile(f);
                RuleSet rs = parse(s);
                lastSource = p;
                return rs;
            } catch (Throwable t) {
                // ignore
            }
        }
        lastSource = "builtin";
        return parse(DEFAULT_JSON);
    }

    public static RuleSet parse(String json) {
        RuleSet rs = new RuleSet();
        try {
            JSONObject o = new JSONObject(json);
            rs.debug = o.optBoolean("debug", false);
            rs.verbose = o.optBoolean("verbose", false);
            JSONArray arr = o.optJSONArray("rules");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject jo = arr.optJSONObject(i);
                    if (jo == null) continue;
                    Rule r = Rule.from(jo);
                    if (r.enabled) rs.rules.add(r);
                }
            }
        } catch (Throwable t) {
            XLog.e("rule parse error: " + t);
        }
        return rs;
    }

    private static String readFile(File f) throws Exception {
        FileInputStream in = new FileInputStream(f);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        in.close();
        return new String(bos.toByteArray(), "UTF-8");
    }

    public static final String DEFAULT_JSON =
            "{\n" +
            "  \"debug\": false,\n" +
            "  \"verbose\": false,\n" +
            "  \"rules\": [\n" +
            "\n" +
            "    {\"name\":\"首页-搜索栏下方快捷图标整块移除\",\"type\":\"slice\",\"anchor\":\"驾车,公交地铁,租车,火车票机票\",\"parentClass\":\"AjxAbsoluteLayout\",\"ancestor\":\"QSScrollContainerV2\",\"from\":0,\"to\":6,\"gap\":100},\n" +
            "\n" +
            "    {\"name\":\"首页-回家行以下全部隐藏\",\"type\":\"class\",\"value\":\"AjxAbsoluteLayout\",\"match\":\"contains\",\"parentCls\":\"AjxList2\",\"ancestor\":\"QSScrollContainerV2\",\"minIndex\":9,\"action\":\"gone\"},\n" +
            "\n" +
            "    {\"name\":\"底栏-只留首页/我的\",\"type\":\"row\",\"anchor\":\"首页\",\"climb\":2,\"minChildren\":3,\"keep\":[\"首页\",\"我的\"],\"ancestor\":\"LiteTabBar\"},\n" +
            "\n" +
            "    {\"name\":\"我的-钱包卡券\",\"type\":\"text\",\"value\":\"钱包卡券\",\"match\":\"contains\",\"up\":1,\"ancestor\":\"MapInteractiveRelativeLayout\"},\n" +
            "    {\"name\":\"我的-借钱\",\"type\":\"text\",\"value\":\"借钱\",\"match\":\"contains\",\"up\":1,\"ancestor\":\"MapInteractiveRelativeLayout\"},\n" +
            "    {\"name\":\"我的-达人任务及以下\",\"type\":\"class\",\"value\":\"AjxAbsoluteLayout\",\"match\":\"contains\",\"parentCls\":\"AjxList2\",\"ancestor\":\"MapInteractiveRelativeLayout\",\"minIndex\":6,\"action\":\"gone\"},\n" +
            "    {\"name\":\"我的-订单收藏待评价栏移除\",\"type\":\"card\",\"anchor\":\"订单\",\"climb\":4,\"hideNext\":1,\"cardLevels\":4,\"ancestor\":\"MapInteractiveRelativeLayout\"},\n" +
            "    {\"name\":\"我的-开启通知权限弹窗\",\"type\":\"text\",\"value\":\"开启通知权限\",\"match\":\"contains\",\"up\":2,\"ancestor\":\"MapInteractiveRelativeLayout\"},\n" +
            "\n" +
            "    {\"name\":\"地图-扫街榜2026浮标\",\"type\":\"rect\",\"value\":\"AJXTemplateContainer\",\"left\":980,\"top\":440,\"right\":1280,\"bottom\":780},\n" +
            "\n" +
            "    {\"name\":\"开屏-自动跳过\",\"type\":\"text\",\"value\":\"跳过\",\"match\":\"contains\",\"action\":\"click\",\"activity\":\"SplashActivity\"},\n" +
            "    {\"name\":\"补贴弹窗-文字\",\"type\":\"text\",\"value\":\"出行补贴\",\"match\":\"contains\",\"up\":0},\n" +
            "    {\"name\":\"补贴弹窗-描述\",\"type\":\"desc\",\"value\":\"出行补贴\",\"match\":\"contains\",\"up\":0}\n" +
            "  ]\n" +
            "}\n";
}
