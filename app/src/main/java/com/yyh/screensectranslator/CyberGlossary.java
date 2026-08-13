package com.yyh.screensectranslator;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class CyberGlossary {
    private static final LinkedHashMap<String, String> TERMS = new LinkedHashMap<>();
    private static final LinkedHashMap<String, String> EXACT = new LinkedHashMap<>();
    private static final Pattern ENGLISH = Pattern.compile("[A-Za-z]{2,}");
    private static final Pattern PROTECTED_DATA = Pattern.compile(
            "(?i)(https?://\\S+|CVE-\\d{4}-\\d{4,8}|\\b(?:\\d{1,3}\\.){3}\\d{1,3}(?::\\d{1,5})?\\b|"
                    + "\\b[a-f0-9]{32,64}\\b|(?:[A-Za-z]:\\\\|/)[^\\s]+|\\b[A-Z][A-Z0-9_-]{1,12}\\b)");
    private static final Pattern COMMAND_PREFIX = Pattern.compile(
            "(?i)^\\s*(?:[$>#]\\s*)?(?:adb|am|apt|bash|cat|chmod|curl|docker|git|grep|java|jq|kubectl|"
                    + "netcat|nc|nmap|node|npm|pip|powershell|python|reg|sed|sh|ssh|sudo|systemctl|wget)\\b");

    static {
        term("command and control", "命令与控制（C2）");
        term("indicator of compromise", "失陷指标（IOC）");
        term("endpoint detection and response", "端点检测与响应（EDR）");
        term("multi-factor authentication", "多因素身份验证（MFA）");
        term("remote code execution", "远程代码执行（RCE）");
        term("privilege escalation", "权限提升");
        term("credential dumping", "凭据转储");
        term("initial access", "初始访问");
        term("defense evasion", "防御规避");
        term("lateral movement", "横向移动");
        term("attack surface", "攻击面");
        term("attack vector", "攻击向量");
        term("threat intelligence", "威胁情报");
        term("threat actor", "威胁行为者");
        term("security misconfiguration", "安全配置错误");
        term("proof of concept", "概念验证（PoC）");
        term("zero-day vulnerability", "零日漏洞（0-day）");
        term("zero day", "零日漏洞");
        term("exploit chain", "漏洞利用链");
        term("reverse shell", "反向 Shell");
        term("web shell", "WebShell");
        term("bind shell", "绑定 Shell");
        term("buffer overflow", "缓冲区溢出");
        term("use after free", "释放后使用（UAF）");
        term("race condition", "竞态条件");
        term("directory traversal", "目录穿越");
        term("path traversal", "路径穿越");
        term("arbitrary file read", "任意文件读取");
        term("arbitrary file write", "任意文件写入");
        term("cross-site scripting", "跨站脚本攻击（XSS）");
        term("SQL injection", "SQL 注入");
        term("server-side request forgery", "服务端请求伪造（SSRF）");
        term("local file inclusion", "本地文件包含（LFI）");
        term("remote file inclusion", "远程文件包含（RFI）");
        term("denial of service", "拒绝服务（DoS）");
        term("man in the middle", "中间人攻击（MITM）");
        term("brute force", "暴力破解");
        term("supply chain attack", "供应链攻击");
        term("persistence", "持久化");
        term("exfiltration", "数据外传");
        term("obfuscation", "混淆");
        term("deobfuscation", "去混淆");
        term("sandbox escape", "沙箱逃逸");
        term("container escape", "容器逃逸");
        term("vulnerability", "漏洞");
        term("exploit", "漏洞利用");
        term("payload", "载荷");
        term("shellcode", "Shellcode");
        term("backdoor", "后门");
        term("rootkit", "Rootkit");
        term("malware", "恶意软件");
        term("ransomware", "勒索软件");
        term("phishing", "网络钓鱼");
        term("severity", "严重性");
        term("remediation", "修复措施");
        term("mitigation", "缓解措施");
        term("false positive", "误报");
        term("false negative", "漏报");

        exact("critical", "严重");
        exact("high", "高危");
        exact("medium", "中危");
        exact("low", "低危");
        exact("informational", "信息");
        exact("scan", "扫描");
        exact("start scan", "开始扫描");
        exact("stop scan", "停止扫描");
        exact("settings", "设置");
        exact("dashboard", "仪表盘");
        exact("alerts", "告警");
        exact("investigate alert", "调查告警");
        exact("mark as safe", "标记为安全");
        exact("blocked", "已阻止");
        exact("detected", "已检测");
        exact("active", "活动中");
        exact("inactive", "未活动");
        exact("enabled", "已启用");
        exact("disabled", "已禁用");
        exact("successful", "成功");
        exact("failed", "失败");
    }

    private CyberGlossary() {
    }

    static boolean shouldTranslate(String source) {
        if (source == null) return false;
        String text = source.trim();
        if (text.length() < 2 || !ENGLISH.matcher(text).find()) return false;
        if (COMMAND_PREFIX.matcher(text).find()) return false;
        int letters = 0;
        int symbols = 0;
        for (int i = 0; i < text.length(); i++) {
            char value = text.charAt(i);
            if (Character.isLetter(value)) letters++;
            else if (!Character.isWhitespace(value) && !Character.isDigit(value)) symbols++;
        }
        return letters >= 2 && !(symbols > letters && !text.contains(" "));
    }

    static String exactTranslation(String source) {
        if (source == null) return null;
        String normalized = source.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        String exact = EXACT.get(normalized);
        if (exact != null) return exact;
        return TERMS.get(normalized);
    }

    static ProtectedText protect(String source) {
        List<Replacement> replacements = new ArrayList<>();
        String encoded = replacePattern(source, PROTECTED_DATA, replacements, false);
        for (Map.Entry<String, String> entry : TERMS.entrySet()) {
            Pattern pattern = Pattern.compile(
                    "(?i)(?<![A-Za-z0-9])" + Pattern.quote(entry.getKey()) + "(?![A-Za-z0-9])");
            encoded = replacePattern(encoded, pattern, replacements, true, entry.getValue());
        }
        return new ProtectedText(encoded, replacements);
    }

    private static String replacePattern(String input, Pattern pattern,
                                         List<Replacement> replacements, boolean fixedTarget) {
        return replacePattern(input, pattern, replacements, fixedTarget, null);
    }

    private static String replacePattern(String input, Pattern pattern,
                                         List<Replacement> replacements, boolean fixedTarget,
                                         String target) {
        Matcher matcher = pattern.matcher(input);
        StringBuffer output = new StringBuffer();
        while (matcher.find()) {
            String token = "ZZX" + String.format(Locale.ROOT, "%03d", replacements.size()) + "XZZ";
            replacements.add(new Replacement(token, fixedTarget ? target : matcher.group()));
            matcher.appendReplacement(output, Matcher.quoteReplacement(token));
        }
        matcher.appendTail(output);
        return output.toString();
    }

    private static void term(String source, String target) {
        TERMS.put(source.toLowerCase(Locale.ROOT), target);
    }

    private static void exact(String source, String target) {
        EXACT.put(source.toLowerCase(Locale.ROOT), target);
    }

    static final class ProtectedText {
        final String encoded;
        private final List<Replacement> replacements;

        ProtectedText(String encoded, List<Replacement> replacements) {
            this.encoded = encoded;
            this.replacements = replacements;
        }

        String restore(String translated) {
            String output = translated == null ? "" : translated.trim();
            for (Replacement replacement : replacements) {
                StringBuilder fuzzyToken = new StringBuilder("(?i)");
                for (int i = 0; i < replacement.token.length(); i++) {
                    if (i > 0) fuzzyToken.append("[\\s._-]*");
                    fuzzyToken.append(Pattern.quote(String.valueOf(replacement.token.charAt(i))));
                }
                output = output.replaceAll(fuzzyToken.toString(),
                        Matcher.quoteReplacement(replacement.value));
            }
            return output.replaceAll("\\s+([，。！？；：,.!?;:])", "$1").trim();
        }
    }

    private static final class Replacement {
        final String token;
        final String value;

        Replacement(String token, String value) {
            this.token = token;
            this.value = value;
        }
    }
}
