package edu.nccu.ai;

import static edu.nccu.ai.Models.Page;
import java.io.IOException;
import java.net.IDN;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Only fetch allowlisted public HTTPS origins. Every redirect is independently checked. */
public final class SafeFetcher implements Models.PageSource {
    static final int MAX_BYTES=1_000_000;
    private final Set<String> allowed;
    private final Duration timeout;
    private final HttpClient client;
    private final Map<String,String> robots=new HashMap<>();
    private final Map<String,Long> lastFetch=new HashMap<>();
    public SafeFetcher(Config config){
        allowed=new HashSet<>();for(String host:config.value("FETCH_ALLOWED_HOSTS","huggingface.co,docs.pytorch.org,pytorch.org,arxiv.org,docs.langchain.com,python.langchain.com,developers.openai.com,platform.openai.com,docs.typesafe.ai").split(","))if(!host.isBlank())allowed.add(host.strip().toLowerCase(Locale.ROOT));
        timeout=Duration.ofSeconds(config.integer("FETCH_TIMEOUT_SECONDS",8,1,15));
        client=HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build();
    }
    public Set<String> allowedHosts(){return Set.copyOf(allowed);}
    public URI validate(String raw)throws IOException{
        final URI uri;
        try{uri=URI.create(raw);}
        catch(IllegalArgumentException e){throw new IOException("網址格式不正確");}
        if(raw.length()>2048||!"https".equalsIgnoreCase(uri.getScheme())||uri.getRawUserInfo()!=null||uri.getHost()==null||(uri.getPort()!=-1&&uri.getPort()!=443))throw new IOException("僅允許沒有帳密的 HTTPS 網址及標準連接埠");
        String host=IDN.toASCII(uri.getHost()).toLowerCase(Locale.ROOT);
        if(!allowed.contains(host))throw new IOException("此網域不在允許抓取的來源清單："+host);
        for(InetAddress address:InetAddress.getAllByName(host))if(!isPublicAddress(address))throw new IOException("禁止讀取本機、私人或保留網路位址");
        String normalized=uri.normalize().toASCIIString();int fragment=normalized.indexOf('#');
        return URI.create(fragment<0?normalized:normalized.substring(0,fragment));
    }
    public static boolean isPublicAddress(InetAddress address){
        if(address.isAnyLocalAddress()||address.isLoopbackAddress()||address.isLinkLocalAddress()||address.isSiteLocalAddress()||address.isMulticastAddress())return false;
        byte[] b=address.getAddress();
        if(b.length==4){int a=b[0]&255,c=b[1]&255,d=b[2]&255;
            return !(a==0||a==10||a==127||a>=224||a==169&&c==254||a==172&&c>=16&&c<=31||a==192&&c==168||a==100&&c>=64&&c<=127||a==192&&c==0||a==198&&(c==18||c==19||c==51&&d==100)||a==203&&c==0&&d==113);
        }
        // Allow global unicast only; reject unique-local, mapped IPv4, documentation and tunnels.
        int first=b[0]&255,second=b[1]&255;
        if((first&0xe0)!=0x20)return false;
        if(first==0x20&&second==0x01 && ((b[2]&255)==0x0d&&(b[3]&255)==0xb8 || b[2]==0))return false;
        return !(first==0x20&&second==0x02);
    }
    @Override public Page fetch(String raw)throws IOException,InterruptedException{
        URI uri=validate(raw);
        for(int hop=0;hop<=3;hop++){
            if(!permittedByRobots(uri))throw new IOException("網站 robots.txt 不允許此路徑或無法確認抓取規則");
            HttpResponse<byte[]> response=request(uri,MAX_BYTES);
            {
                if(response.statusCode()>=300&&response.statusCode()<400){
                    String target=response.headers().firstValue("location").orElseThrow(()->new IOException("轉址缺少目的地"));
                    uri=validate(uri.resolve(target).toString());continue;
                }
                if(response.statusCode()!=200)throw new IOException("網頁回應 HTTP "+response.statusCode());
                String type=response.headers().firstValue("content-type").orElse("").toLowerCase(Locale.ROOT);
                if(!type.startsWith("text/html")&&!type.startsWith("application/xhtml+xml")&&!type.startsWith("text/plain"))throw new IOException("只支援公開 HTML 或純文字網頁");
                byte[] bytes=response.body();
                Charset charset=StandardCharsets.UTF_8;
                var matcher=Pattern.compile("charset=[\"']?([a-z0-9_-]+)").matcher(type);
                if(matcher.find()){try{charset=Charset.forName(matcher.group(1));}catch(IllegalArgumentException ignored){ /* default UTF-8 */ }}
                String text=new String(bytes,charset);
                if(type.startsWith("text/plain"))return new Page(uri.toString(),uri.getHost(),text,List.of(),uri.getHost(),false);
                return HtmlParser.parse(uri.toString(),text);
            }
        }throw new IOException("網頁轉址次數超過上限");
    }
    private HttpResponse<byte[]> request(URI uri,int maximum)throws IOException,InterruptedException{
        long remaining=500-(System.currentTimeMillis()-lastFetch.getOrDefault(uri.getHost(),0L));if(remaining>0)Thread.sleep(remaining);
        lastFetch.put(uri.getHost(),System.currentTimeMillis());
        HttpRequest req=HttpRequest.newBuilder(uri).timeout(timeout).header("User-Agent","AIKnowledgeSearch-StudentProject/1.0").header("Accept","text/html,text/plain;q=0.9").GET().build();
        return BoundedHttp.send(client,req,maximum);
    }
    private boolean permittedByRobots(URI uri)throws IOException,InterruptedException{
        String host=uri.getHost();String rules=robots.get(host);
        if(rules==null){
            URI robotsUri=validate("https://"+host+"/robots.txt");
            try{
                HttpResponse<byte[]> response=request(robotsUri,64000);
                {
                    if(response.statusCode()==404||response.statusCode()==410)rules="";
                    else if(response.statusCode()==200){rules=new String(response.body(),StandardCharsets.UTF_8);}
                    else rules="User-agent: *\nDisallow: /";
                }
            }catch(IOException e){rules="User-agent: *\nDisallow: /";}
            robots.put(host,rules);
        }
        return robotsAllows(rules,uri.getRawPath()+(uri.getRawQuery()==null?"":"?"+uri.getRawQuery()));
    }
    public static boolean robotsAllows(String text,String path){
        if(path.isEmpty()||path.startsWith("?"))path="/"+path;
        record Rule(String path,boolean allow) { }
        List<Rule> wildcard=new ArrayList<>(),specific=new ArrayList<>();List<String> agents=new ArrayList<>();boolean hasRules=false,hasSpecific=false;
        for(String raw:text.split("\\R")){
            String line=raw.split("#",2)[0].strip();int colon=line.indexOf(':');if(colon<0)continue;
            String name=line.substring(0,colon).strip().toLowerCase(Locale.ROOT),value=line.substring(colon+1).strip();
            if(name.equals("user-agent")){if(hasRules){agents.clear();hasRules=false;}String agent=value.toLowerCase(Locale.ROOT);agents.add(agent);if(!agent.isEmpty()&&!agent.equals("*")&&"aiknowledgesearch-studentproject".contains(agent))hasSpecific=true;}
            else if(name.equals("allow")||name.equals("disallow")){
                hasRules=true;if(value.isEmpty())continue;Rule rule=new Rule(normalizeOctets(value),name.equals("allow"));
                if(agents.contains("*"))wildcard.add(rule);
                if(agents.stream().anyMatch(a->!a.isEmpty()&&!a.equals("*")&&"aiknowledgesearch-studentproject".contains(a)))specific.add(rule);
            }
        }
        path=normalizeOctets(path);
        try{path=URI.create(path).normalize().toString();}catch(IllegalArgumentException ignored){return false;}
        List<Rule> rules=hasSpecific?specific:wildcard;int length=-1;boolean allow=true;
        for(Rule rule:rules){String pattern=rule.path();boolean end=pattern.endsWith("$");if(end)pattern=pattern.substring(0,pattern.length()-1);
            String regex=Arrays.stream(pattern.split("\\*",-1)).map(Pattern::quote).collect(java.util.stream.Collectors.joining(".*"));
            if(Pattern.compile("^"+regex+(end?"$":"")).matcher(path).find()){
                int n=pattern.replace("*","").length();if(n>length||n==length&&rule.allow()){length=n;allow=rule.allow();}
            }
        }return allow;
    }
    private static String normalizeOctets(String raw){
        StringBuilder out=new StringBuilder();
        for(int i=0;i<raw.length();){
            char c=raw.charAt(i);
            if(c=='%'&&i+2<raw.length()&&raw.substring(i+1,i+3).matches("[0-9a-fA-F]{2}")){
                int b=Integer.parseInt(raw.substring(i+1,i+3),16);
                if(b>='A'&&b<='Z'||b>='a'&&b<='z'||b>='0'&&b<='9'||"-._~".indexOf(b)>=0)out.append((char)b);
                else out.append(String.format("%%%02X",b));i+=3;
            }else if(c>127){int cp=raw.codePointAt(i);for(byte b:new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8))out.append(String.format("%%%02X",b&255));i+=Character.charCount(cp);}
            else{out.append(c);i++;}
        }return out.toString();
    }
}
