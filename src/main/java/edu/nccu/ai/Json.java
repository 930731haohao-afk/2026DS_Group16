package edu.nccu.ai;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Small strict JSON codec for this dependency-free teaching project. */
public final class Json {
    private Json() { }
    public static Object parse(String text) {
        Parser p = new Parser(text); Object value = p.value(0); p.ws();
        if (p.pos != text.length()) throw new IllegalArgumentException("JSON has trailing data");
        return value;
    }
    public static Map<String,Object> object(Object value) {
        if (!(value instanceof Map<?,?> map)) throw new IllegalArgumentException("Expected JSON object");
        Map<String,Object> result = new LinkedHashMap<>();
        map.forEach((key,item) -> result.put(String.valueOf(key),item)); return result;
    }
    public static List<Object> array(Object value) {
        if (!(value instanceof List<?> list)) throw new IllegalArgumentException("Expected JSON array");
        return new ArrayList<>(list);
    }
    public static String string(Object value, String fallback) { return value instanceof String s ? s : fallback; }
    public static double number(Object value, double fallback) { return value instanceof Number n ? n.doubleValue() : fallback; }
    public static boolean bool(Object value, boolean fallback) { return value instanceof Boolean b ? b : fallback; }
    public static String stringify(Object value) { StringBuilder out=new StringBuilder(); write(value,out,0); return out.toString(); }
    private static void write(Object value,StringBuilder out,int depth) {
        if(depth>64) throw new IllegalArgumentException("JSON nesting limit");
        if(value==null) out.append("null");
        else if(value instanceof String s) quote(s,out);
        else if(value instanceof Boolean) out.append(value);
        else if(value instanceof Number n) { if(!Double.isFinite(n.doubleValue())) throw new IllegalArgumentException("Non-finite JSON number"); out.append(value); }
        else if(value instanceof Map<?,?> map) {
            out.append('{'); boolean first=true;
            for(var entry:map.entrySet()){if(!first)out.append(',');first=false;quote(String.valueOf(entry.getKey()),out);out.append(':');write(entry.getValue(),out,depth+1);}out.append('}');
        } else if(value instanceof Iterable<?> values) {
            out.append('[');boolean first=true;for(Object v:values){if(!first)out.append(',');first=false;write(v,out,depth+1);}out.append(']');
        } else if(value.getClass().isRecord()) {
            Map<String,Object> fields=new LinkedHashMap<>();
            for(RecordComponent component:value.getClass().getRecordComponents()) {
                try { fields.put(component.getName(),component.getAccessor().invoke(value)); }
                catch(ReflectiveOperationException e){throw new IllegalArgumentException("Cannot encode record",e);}
            } write(fields,out,depth+1);
        } else throw new IllegalArgumentException("Unsupported JSON value");
    }
    private static void quote(String s,StringBuilder out) {
        out.append('"');for(int i=0;i<s.length();i++){char c=s.charAt(i);switch(c){
            case '"' -> out.append("\\\""); case '\\' -> out.append("\\\\"); case '\n' -> out.append("\\n");
            case '\r' -> out.append("\\r");case '\t' -> out.append("\\t");case '\b' -> out.append("\\b");case '\f' -> out.append("\\f");
            default -> { if(c<32 || Character.isSurrogate(c)) out.append(String.format("\\u%04x",(int)c)); else out.append(c); }
        }}out.append('"');
    }
    private static final class Parser {
        final String text; int pos;
        Parser(String text){this.text=text;}
        void ws(){while(pos<text.length() && " \t\r\n".indexOf(text.charAt(pos))>=0)pos++;}
        boolean take(char c){ws();if(pos<text.length()&&text.charAt(pos)==c){pos++;return true;}return false;}
        void need(char c){if(!take(c))throw error();}
        IllegalArgumentException error(){return new IllegalArgumentException("Invalid JSON at position "+pos);}
        Object value(int depth){
            if(depth>64)throw error();ws();if(pos>=text.length())throw error();char c=text.charAt(pos);
            if(c=='"')return string();
            if(c=='{'){pos++;Map<String,Object> map=new LinkedHashMap<>();if(take('}'))return map;do{ws();String key=string();need(':');Object v=value(depth+1);if(map.containsKey(key))throw error();map.put(key,v);}while(take(','));need('}');return map;}
            if(c=='['){pos++;List<Object> list=new ArrayList<>();if(take(']'))return list;do{list.add(value(depth+1));}while(take(','));need(']');return list;}
            if(text.startsWith("true",pos)){pos+=4;return true;}if(text.startsWith("false",pos)){pos+=5;return false;}if(text.startsWith("null",pos)){pos+=4;return null;}
            int begin=pos;if(c=='-')pos++;if(pos>=text.length())throw error();
            if(text.charAt(pos)=='0')pos++;else{if(text.charAt(pos)<'1'||text.charAt(pos)>'9')throw error();while(pos<text.length()&&digit(text.charAt(pos)))pos++;}
            boolean decimal=false;
            if(pos<text.length()&&text.charAt(pos)=='.'){decimal=true;pos++;int start=pos;while(pos<text.length()&&digit(text.charAt(pos)))pos++;if(start==pos)throw error();}
            if(pos<text.length()&&(text.charAt(pos)=='e'||text.charAt(pos)=='E')){decimal=true;pos++;if(pos<text.length()&&(text.charAt(pos)=='+'||text.charAt(pos)=='-'))pos++;int start=pos;while(pos<text.length()&&digit(text.charAt(pos)))pos++;if(start==pos)throw error();}
            try{String raw=text.substring(begin,pos);if(!decimal)return Long.valueOf(raw);double n=Double.parseDouble(raw);if(!Double.isFinite(n))throw error();return n;}catch(NumberFormatException e){throw error();}
        }
        String string(){
            if(pos>=text.length()||text.charAt(pos++)!='"')throw error();StringBuilder s=new StringBuilder();
            while(pos<text.length()){char c=text.charAt(pos++);if(c=='"')return s.toString();if(c<32)throw error();
                if(c=='\\'){if(pos>=text.length())throw error();char escape=text.charAt(pos++);switch(escape){
                    case '"','\\','/' -> s.append(escape);case 'b' -> s.append('\b');case 'f' -> s.append('\f');case 'n' -> s.append('\n');case 'r' -> s.append('\r');case 't' -> s.append('\t');
                    case 'u' -> {if(pos+4>text.length())throw error();String hex=text.substring(pos,pos+4);if(!hex.matches("[0-9a-fA-F]{4}"))throw error();s.append((char)Integer.parseInt(hex,16));pos+=4;}
                    default -> throw error();
                }}else s.append(c);
            }throw error();
        }
        private static boolean digit(char c){return c>='0'&&c<='9';}
    }
}
