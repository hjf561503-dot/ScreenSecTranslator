package com.yyh.screensectranslator;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class CyberGlossary {
    static final String DICTIONARY_ASSET = "cyber-security-en-zh.tsv";
    static final String DICTIONARY_CACHE = "cyber-security-en-zh-online.tsv";

    private static final LinkedHashMap<String, String> TERMS = new LinkedHashMap<>();
    private static final LinkedHashMap<String, String> EXACT = new LinkedHashMap<>();
    private static volatile Map<String, String> externalExact = Collections.emptyMap();
    private static volatile List<String> externalKeep = Collections.emptyList();
    private static volatile String dictionaryVersion = "内置";
    private static final Pattern ENGLISH = Pattern.compile("[A-Za-z]{2,}");
    private static final Pattern CAMEL_CASE = Pattern.compile(
            "(?<![A-Za-z0-9])(?:[A-Z][a-z0-9]+){2,}(?![A-Za-z0-9])");
    private static final Pattern PROTECTED_DATA = Pattern.compile(
            "(?i)(https?://\\S+|CVE-\\d{4}-\\d{4,8}|\\b(?:\\d{1,3}\\.){3}\\d{1,3}(?::\\d{1,5})?\\b|"
                    + "\\b[a-f0-9]{32,64}\\b|(?<![A-Za-z0-9])(?:[A-Za-z]:\\\\|/)[^\\s]+|"
                    + "\\b[A-Za-z][A-Za-z0-9]*(?:[-_][A-Za-z0-9]+)+\\b)");
    private static final Pattern TECH_TOKEN = Pattern.compile(
            "(?i)(?<![A-Za-z0-9])(?:API|CLI|CVE|DNS|EDR|HTTP|HTTPS|IOC|IP|MFA|RCE|SIEM|"
                    + "SOC|SQL|SSH|SSL|SSRF|TCP|TLS|UDP|URL|XSS|YARA)(?![A-Za-z0-9])");
    private static final Pattern COMMAND_PREFIX = Pattern.compile(
            "(?i)^\\s*(?:[$>#]\\s*)?(?:adb|am|apt|bash|cat|chmod|curl|docker|git|grep|java|jq|kubectl|"
                    + "dirb|ffuf|gobuster|hydra|msfconsole|netcat|nc|nikto|nmap|node|npm|nuclei|"
                    + "pip|powershell|python|reg|sed|sh|sqlmap|ssh|sudo|systemctl|wget|wfuzz)\\b");

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

    static synchronized void loadDictionaries(Context context) {
        LinkedHashMap<String, String> translated = new LinkedHashMap<>();
        Set<String> kept = new HashSet<>();
        String version = "内置";
        try (InputStream input = context.getAssets().open(DICTIONARY_ASSET)) {
            DictionaryData bundled = parseDictionary(input);
            translated.putAll(bundled.translated);
            kept.addAll(bundled.kept);
            version = bundled.version;
        } catch (Exception error) {
            AppLog.error(context, "DICTIONARY", "BUNDLED_LOAD_FAILED", "", error);
        }

        File cached = new File(context.getFilesDir(), DICTIONARY_CACHE);
        if (cached.exists()) {
            try (InputStream input = new FileInputStream(cached)) {
                DictionaryData online = parseDictionary(input);
                translated.putAll(online.translated);
                kept.addAll(online.kept);
                version = online.version;
            } catch (Exception error) {
                AppLog.error(context, "DICTIONARY", "ONLINE_CACHE_LOAD_FAILED", "", error);
            }
        }
        install(translated, kept, version);
        AppLog.info(context, "DICTIONARY", "LOADED",
                "version=" + cleanLog(version) + " translated=" + translated.size()
                        + " protected_names=" + kept.size());
    }

    static synchronized DictionarySummary installOnlineUpdate(Context context, String body)
            throws Exception {
        if (body == null || body.length() > 512 * 1024) {
            throw new IllegalArgumentException("术语库文件为空或过大");
        }
        DictionaryData update;
        try (InputStream input = new java.io.ByteArrayInputStream(
                body.getBytes(StandardCharsets.UTF_8))) {
            update = parseDictionary(input);
        }
        if (update.translated.size() + update.kept.size() < 40
                || !update.kept.contains("dirb")
                || !update.kept.contains("dirbuster")) {
            throw new IllegalArgumentException("术语库校验失败");
        }
        File target = new File(context.getFilesDir(), DICTIONARY_CACHE);
        File temporary = new File(context.getFilesDir(), DICTIONARY_CACHE + ".tmp");
        try (FileOutputStream output = new FileOutputStream(temporary, false)) {
            output.write(body.getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        }
        if (target.exists() && !target.delete()) {
            temporary.delete();
            throw new IllegalStateException("无法替换旧术语库");
        }
        if (!temporary.renameTo(target)) {
            temporary.delete();
            throw new IllegalStateException("无法保存术语库");
        }
        loadDictionaries(context);
        return new DictionarySummary(update.version,
                update.translated.size(), update.kept.size());
    }

    static String dictionaryVersion() {
        return dictionaryVersion;
    }

    static boolean shouldTranslate(String source) {
        if (source == null) return false;
        String text = source.trim();
        if (text.length() < 2 || !ENGLISH.matcher(text).find()) return false;
        if (externalKeep.contains(normalize(text))) return false;
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
        String external = externalExact.get(normalized);
        if (external != null) return external;
        String exact = EXACT.get(normalized);
        if (exact != null) return exact;
        return TERMS.get(normalized);
    }

    static String polishTranslation(String source, String raw) {
        if (raw == null) return "";
        String output = raw.replace('\n', ' ').replace('\r', ' ')
                .replaceAll("\\s+", " ").trim();
        output = replaceKnownTermsOutsideProtectedData(output, externalExact);
        output = replaceKnownTermsOutsideProtectedData(output, TERMS);
        output = output.replaceAll("\\s+([，。！？；：,.!?;:])", "$1")
                .replaceAll("([\\p{IsHan}])([A-Za-z0-9])", "$1 $2")
                .replaceAll("([A-Za-z0-9])([\\p{IsHan}])", "$1 $2")
                .replaceAll(" {2,}", " ")
                .trim();
        return output;
    }

    static boolean protectedTokensPreserved(String source, String translated) {
        if (source == null || translated == null) return false;
        List<String> required = new ArrayList<>();
        collectMatches(source, PROTECTED_DATA, required);
        collectMatches(source, CAMEL_CASE, required);
        collectMatches(source, TECH_TOKEN, required);
        for (String name : externalKeep) {
            Pattern pattern = Pattern.compile(
                    "(?i)(?<![A-Za-z0-9])" + Pattern.quote(name) + "(?![A-Za-z0-9])");
            collectMatches(source, pattern, required);
        }
        String folded = translated.toLowerCase(Locale.ROOT);
        for (String token : required) {
            if (!folded.contains(token.toLowerCase(Locale.ROOT))) return false;
        }
        return true;
    }

    private static void install(Map<String, String> translated, Set<String> kept, String version) {
        List<Map.Entry<String, String>> translatedEntries = new ArrayList<>(translated.entrySet());
        translatedEntries.sort((left, right) -> Integer.compare(
                right.getKey().length(), left.getKey().length()));
        LinkedHashMap<String, String> orderedTranslated = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : translatedEntries) {
            orderedTranslated.put(entry.getKey(), entry.getValue());
        }
        List<String> orderedKept = new ArrayList<>(kept);
        orderedKept.sort(Comparator.comparingInt(String::length).reversed());
        externalExact = Collections.unmodifiableMap(orderedTranslated);
        externalKeep = Collections.unmodifiableList(orderedKept);
        dictionaryVersion = version == null || version.trim().isEmpty() ? "未知" : version;
    }

    private static DictionaryData parseDictionary(InputStream input) throws Exception {
        LinkedHashMap<String, String> translated = new LinkedHashMap<>();
        Set<String> kept = new HashSet<>();
        String version = "未知";
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            int accepted = 0;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.startsWith("# version=")) {
                    version = trimmed.substring("# version=".length()).trim();
                    continue;
                }
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                int tab = line.indexOf('\t');
                if (tab <= 0 || tab >= line.length() - 1) continue;
                String source = normalize(line.substring(0, tab));
                String target = line.substring(tab + 1).trim();
                if (source.length() < 2 || source.length() > 100
                        || target.isEmpty() || target.length() > 160) continue;
                if ("@KEEP".equalsIgnoreCase(target)) kept.add(source);
                else translated.put(source, target);
                accepted++;
                if (accepted >= 2000) break;
            }
        }
        return new DictionaryData(translated, kept, version);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ")
                .toLowerCase(Locale.ROOT);
    }

    private static String cleanLog(String value) {
        return value == null ? "" : value.replace('\n', ' ').replace('\r', ' ');
    }

    private static String replaceKnownTermsOutsideProtectedData(
            String input, Map<String, String> translations) {
        Matcher protectedMatcher = PROTECTED_DATA.matcher(input);
        StringBuilder output = new StringBuilder();
        int cursor = 0;
        while (protectedMatcher.find()) {
            output.append(replaceKnownTerms(input.substring(cursor, protectedMatcher.start()),
                    translations));
            output.append(protectedMatcher.group());
            cursor = protectedMatcher.end();
        }
        output.append(replaceKnownTerms(input.substring(cursor), translations));
        return output.toString();
    }

    private static String replaceKnownTerms(String input, Map<String, String> translations) {
        String output = input;
        for (Map.Entry<String, String> entry : translations.entrySet()) {
            String source = entry.getKey();
            if (source.length() < 4) continue;
            Pattern pattern = Pattern.compile(
                    "(?i)(?<![A-Za-z0-9])" + Pattern.quote(source) + "(?![A-Za-z0-9])");
            output = pattern.matcher(output).replaceAll(Matcher.quoteReplacement(entry.getValue()));
        }
        return output;
    }

    private static void collectMatches(String input, Pattern pattern, List<String> output) {
        Matcher matcher = pattern.matcher(input);
        while (matcher.find()) {
            String value = matcher.group();
            if (value == null) continue;
            value = value.trim().replaceAll("[.,;:!?，。；：！？)\\]}]+$", "");
            if (!value.isEmpty()) output.add(value);
        }
    }

    private static void term(String source, String target) {
        TERMS.put(source.toLowerCase(Locale.ROOT), target);
    }

    private static void exact(String source, String target) {
        EXACT.put(source.toLowerCase(Locale.ROOT), target);
    }

    static final class DictionarySummary {
        final String version;
        final int translations;
        final int protectedNames;

        DictionarySummary(String version, int translations, int protectedNames) {
            this.version = version;
            this.translations = translations;
            this.protectedNames = protectedNames;
        }
    }

    private static final class DictionaryData {
        final LinkedHashMap<String, String> translated;
        final Set<String> kept;
        final String version;

        DictionaryData(LinkedHashMap<String, String> translated,
                       Set<String> kept, String version) {
            this.translated = translated;
            this.kept = kept;
            this.version = version;
        }
    }
}
