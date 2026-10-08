package edu.nccu.ai;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** Settings are read once at startup; never expose the complete map via HTTP/logs. */
public final class Config {
    private final Map<String,String> values;
    public Config(Map<String,String> values){this.values=Map.copyOf(values);}
    public static Config load(Path root)throws IOException{
        Map<String,String> values=new HashMap<>();Path file=root.resolve(".env");
        if(Files.exists(file)){
            if(Files.size(file)>32768)throw new IOException(".env too large");
            for(String raw:Files.readAllLines(file,StandardCharsets.UTF_8)){
                String line=raw.replace("\uFEFF","").strip();if(line.isEmpty()||line.startsWith("#"))continue;
                int at=line.indexOf('=');if(at<1)throw new IOException("Invalid .env line");
                String key=line.substring(0,at).trim(),value=line.substring(at+1).trim();
                if(!key.matches("[A-Z][A-Z0-9_]*"))throw new IOException("Invalid setting name");
                if(value.length()>=2 && ((value.startsWith("\"")&&value.endsWith("\""))||(value.startsWith("'")&&value.endsWith("'"))))value=value.substring(1,value.length()-1);
                values.put(key,value);
            }
        }values.putAll(System.getenv());return new Config(values);
    }
    public String value(String key,String fallback){String v=values.get(key);return v==null||v.isBlank()?fallback:v;}
    public boolean flag(String key,boolean fallback){String v=values.get(key);if(v==null||v.isBlank())return fallback;if(v.equalsIgnoreCase("true"))return true;if(v.equalsIgnoreCase("false"))return false;throw new IllegalArgumentException("Invalid boolean setting: "+key);}
    public int integer(String key,int fallback,int min,int max){String v=values.get(key);int n=v==null||v.isBlank()?fallback:Integer.parseInt(v);if(n<min||n>max)throw new IllegalArgumentException("Setting out of range: "+key);return n;}
}
