package com.zongc.phone.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 极简 JSON 解析与生成（不依赖第三方库，电脑上也能直接单元测试）。
 *
 * <p>解析结果：对象 → {@code Map<String, Object>}（保持字段顺序），数组 → {@code List<Object>}，
 * 字符串 → String，数字 → Double，true/false → Boolean，null → null。</p>
 */
public final class Json {
    private Json() {
    }

    // ================================================================ 解析

    /** 解析一段 JSON 文本；格式错误时抛出 {@link IllegalArgumentException}。 */
    public static Object parse(String text) {
        Parser p = new Parser(text);
        p.skipWs();
        Object v = p.value();
        p.skipWs();
        if (p.i != text.length()) throw p.error("JSON 末尾有多余字符");
        return v;
    }

    /** 解析 JSON 对象；不是对象时返回 null。 */
    public static Map<String, Object> parseObject(String text) {
        return obj(parse(text));
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : null;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> arr(Object o) {
        return o instanceof List ? (List<Object>) o : null;
    }

    public static String str(Map<String, Object> m, String key) {
        Object v = m == null ? null : m.get(key);
        if (v == null) return null;
        if (v instanceof String) return (String) v;
        if (v instanceof Double) {
            double d = (Double) v;
            if (d == Math.rint(d) && Math.abs(d) < 1e15) return Long.toString((long) d);
        }
        return String.valueOf(v);
    }

    public static double num(Map<String, Object> m, String key, double def) {
        Object v = m == null ? null : m.get(key);
        return v instanceof Number ? ((Number) v).doubleValue() : def;
    }

    public static boolean bool(Map<String, Object> m, String key, boolean def) {
        Object v = m == null ? null : m.get(key);
        return v instanceof Boolean ? (Boolean) v : def;
    }

    /** 读取形如 [a, b, c ...] 的数字数组；字段缺失或格式不对时返回 null。 */
    public static double[] nums(Map<String, Object> m, String key) {
        List<Object> list = arr(m == null ? null : m.get(key));
        if (list == null) return null;
        double[] out = new double[list.size()];
        for (int i = 0; i < out.length; i++) {
            Object v = list.get(i);
            if (!(v instanceof Number)) return null;
            out[i] = ((Number) v).doubleValue();
        }
        return out;
    }

    private static final class Parser {
        final String s;
        int i;

        Parser(String s) {
            this.s = s;
        }

        IllegalArgumentException error(String msg) {
            return new IllegalArgumentException(msg + "（位置 " + i + "）");
        }

        void skipWs() {
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') i++;
                else break;
            }
        }

        Object value() {
            if (i >= s.length()) throw error("JSON 意外结束");
            char c = s.charAt(i);
            switch (c) {
                case '{':
                    return object();
                case '[':
                    return array();
                case '"':
                    return string();
                case 't':
                    literal("true");
                    return Boolean.TRUE;
                case 'f':
                    literal("false");
                    return Boolean.FALSE;
                case 'n':
                    literal("null");
                    return null;
                default:
                    if (c == '-' || (c >= '0' && c <= '9')) return number();
                    throw error("无法识别的字符 '" + c + "'");
            }
        }

        Map<String, Object> object() {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            i++; // {
            skipWs();
            if (peek() == '}') {
                i++;
                return m;
            }
            while (true) {
                skipWs();
                if (peek() != '"') throw error("对象的键必须是字符串");
                String k = string();
                skipWs();
                expect(':');
                skipWs();
                m.put(k, value());
                skipWs();
                char c = next();
                if (c == '}') return m;
                if (c != ',') throw error("对象中缺少 ',' 或 '}'");
            }
        }

        List<Object> array() {
            List<Object> list = new ArrayList<Object>();
            i++; // [
            skipWs();
            if (peek() == ']') {
                i++;
                return list;
            }
            while (true) {
                skipWs();
                list.add(value());
                skipWs();
                char c = next();
                if (c == ']') return list;
                if (c != ',') throw error("数组中缺少 ',' 或 ']'");
            }
        }

        String string() {
            i++; // "
            StringBuilder sb = null;
            int start = i;
            while (true) {
                if (i >= s.length()) throw error("字符串没有结束");
                char c = s.charAt(i);
                if (c == '"') {
                    String out = sb == null ? s.substring(start, i) : sb.append(s, start, i).toString();
                    i++;
                    return out;
                }
                if (c == '\\') {
                    if (sb == null) sb = new StringBuilder();
                    sb.append(s, start, i);
                    i++;
                    if (i >= s.length()) throw error("转义字符不完整");
                    char e = s.charAt(i++);
                    switch (e) {
                        case '"': sb.append('"'); break;
                        case '\\': sb.append('\\'); break;
                        case '/': sb.append('/'); break;
                        case 'b': sb.append('\b'); break;
                        case 'f': sb.append('\f'); break;
                        case 'n': sb.append('\n'); break;
                        case 'r': sb.append('\r'); break;
                        case 't': sb.append('\t'); break;
                        case 'u':
                            if (i + 4 > s.length()) throw error("\\u 转义不完整");
                            try {
                                sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                            } catch (NumberFormatException ex) {
                                throw error("\\u 转义不是十六进制");
                            }
                            i += 4;
                            break;
                        default:
                            throw error("未知的转义 \\" + e);
                    }
                    start = i;
                } else {
                    i++;
                }
            }
        }

        Double number() {
            int start = i;
            if (peek() == '-') i++;
            while (i < s.length()) {
                char c = s.charAt(i);
                if ((c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') i++;
                else break;
            }
            try {
                return Double.valueOf(s.substring(start, i));
            } catch (NumberFormatException e) {
                throw error("数字格式错误");
            }
        }

        void literal(String word) {
            if (!s.startsWith(word, i)) throw error("应为 " + word);
            i += word.length();
        }

        char peek() {
            return i < s.length() ? s.charAt(i) : '\0';
        }

        char next() {
            if (i >= s.length()) throw error("JSON 意外结束");
            return s.charAt(i++);
        }

        void expect(char c) {
            if (next() != c) throw error("应为 '" + c + "'");
        }
    }

    // ================================================================ 生成

    /** 把字符串写成带引号、已转义的 JSON 字符串。 */
    public static String quote(String v) {
        if (v == null) return "null";
        StringBuilder sb = new StringBuilder(v.length() + 2);
        sb.append('"');
        for (int k = 0; k < v.length(); k++) {
            char c = v.charAt(k);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    if (c < 0x20) {
                        String hex = Integer.toHexString(c);
                        sb.append("\\u");
                        for (int p = hex.length(); p < 4; p++) sb.append('0');
                        sb.append(hex);
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.append('"').toString();
    }

    /** 逐个字段拼一个 JSON 对象（单行，适合按行发送的协议）。 */
    public static final class Obj {
        private final StringBuilder sb = new StringBuilder(128).append('{');
        private boolean first = true;

        private StringBuilder key(String k) {
            if (!first) sb.append(',');
            first = false;
            return sb.append(quote(k)).append(':');
        }

        public Obj put(String k, String v) {
            key(k).append(quote(v));
            return this;
        }

        /** 数字保留 {@code decimals} 位小数；NaN / ∞ 写成 null。 */
        public Obj put(String k, double v, int decimals) {
            Fmt.fixed(key(k), v, decimals, "null");
            return this;
        }

        public Obj put(String k, long v) {
            key(k).append(v);
            return this;
        }

        public Obj put(String k, boolean v) {
            key(k).append(v);
            return this;
        }

        /** 直接写入一段已经是 JSON 的文本（数组、嵌套对象）。 */
        public Obj raw(String k, String json) {
            key(k).append(json);
            return this;
        }

        @Override
        public String toString() {
            return sb.toString() + '}';
        }
    }
}
