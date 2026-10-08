package edu.nccu.ai;

import static edu.nccu.ai.Models.*;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Local-first HTTP application. Browser never receives credentials or arbitrary file access. */
public final class App implements AutoCloseable {
    private final Path root;
    private final Config config;
    private final DemoCorpus corpus;
    private final Providers providers;
    private final PublicSearch publicSearch;
    private final HttpServer server;
    private final ExecutorService executor=Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore searches;
    private final Set<String> hosts;
    private final String accessToken;
    private final String startedAt=Instant.now().toString();
    public App(Path root,Config config)throws IOException{
        this(root,config,new PublicSearch(config));
    }
    App(Path root,Config config,PublicSearch publicSearch)throws IOException{
        this.root=root.toAbsolutePath().normalize();this.config=config;
        corpus=DemoCorpus.load(root.resolve("data/demo-corpus.json"));providers=new Providers(config);this.publicSearch=publicSearch;
        searches=new Semaphore(config.integer("MAX_CONCURRENT_SEARCHES",2,1,8));
        hosts=Set.of(config.value("APP_ALLOWED_HOSTS","localhost,127.0.0.1").split(","));
        String bind=config.value("APP_HOST","127.0.0.1");accessToken=config.value("APP_ACCESS_TOKEN","");
        if(!Set.of("localhost","127.0.0.1","::1").contains(bind)&&accessToken.length()<16)throw new IOException("非本機服務需設定至少 16 字元的 APP_ACCESS_TOKEN，並使用 HTTPS 反向代理");
        server=HttpServer.create(new InetSocketAddress(bind,config.integer("APP_PORT",8080,0,65535)),32);
        server.createContext("/",this::handle);server.setExecutor(executor);
    }
    public int port(){return server.getAddress().getPort();}
    public void start(){server.start();}
    @Override public void close(){server.stop(0);executor.shutdownNow();}
    public static void main(String[] args)throws Exception{
        Path root=Path.of(System.getenv().getOrDefault("APP_ROOT","."));Config config=Config.load(root);
        App app=new App(root,config);Runtime.getRuntime().addShutdownHook(new Thread(app::close));app.start();
        System.out.println("AI Knowledge Search ready: http://"+config.value("APP_HOST","127.0.0.1")+":"+app.port());
        System.out.println("Public encyclopedia/research search is available without keys. Demo is optional. Press Ctrl+C to stop.");
    }
    private void handle(HttpExchange exchange)throws IOException{
        try{
            securityHeaders(exchange);
            String hostHeader=exchange.getRequestHeaders().getFirst("Host");
            if(hostHeader==null||!validHost(hostHeader)){send(exchange,403,Map.of("error","host","message","不允許的 Host"));return;}
            if(!authorized(exchange)){exchange.getResponseHeaders().set("WWW-Authenticate","Basic realm=\"AI Knowledge Search\", charset=\"UTF-8\"");send(exchange,401,Map.of("error","auth","message","請輸入專案存取密碼"));return;}
            String path=exchange.getRequestURI().getPath(),method=exchange.getRequestMethod();
            if(method.equals("GET")){
                switch(path){
                    case "/api/health" -> send(exchange,200,Map.of("status","ok","app","ai-knowledge-search","startedAt",startedAt));
                    case "/api/status" -> send(exchange,200,status());
                    case "/api/demo-pages" -> send(exchange,200,Map.of("pages",corpus.pages().stream().map(p->Map.of("url",p.url(),"title",p.title(),"source",p.source())).toList()));
                    case "/api/evaluation" -> send(exchange,200,Json.parse(Files.readString(root.resolve("data/evaluation.json"),StandardCharsets.UTF_8)));
                    default -> staticFile(exchange,path);
                }return;
            }
            if(!method.equals("POST")){send(exchange,405,Map.of("error","method","message","只接受 GET 或 POST"));return;}
            String origin=exchange.getRequestHeaders().getFirst("Origin");
            if(origin!=null&&!origin.equals("http://"+hostHeader)&&!origin.equals("https://"+hostHeader)){send(exchange,403,Map.of("error","origin","message","拒絕跨站請求"));return;}
            String contentType=exchange.getRequestHeaders().getFirst("Content-Type");
            if(contentType==null||!contentType.toLowerCase(java.util.Locale.ROOT).startsWith("application/json")){send(exchange,415,Map.of("error","content_type","message","請使用 application/json"));return;}
            int max=path.equals("/api/evaluate")?600000:32000;byte[] body=exchange.getRequestBody().readNBytes(max+1);
            if(body.length>max){send(exchange,413,Map.of("error","size","message","請求內容超過上限"));return;}
            Map<String,Object> request=Json.object(Json.parse(new String(body,StandardCharsets.UTF_8)));
            switch(path){
                case "/api/search" -> send(exchange,200,bounded(()->search(request)));
                case "/api/count" -> send(exchange,200,bounded(()->count(request)));
                case "/api/evaluate" -> send(exchange,200,Evaluation.evaluate(Json.array(request.get("rows"))));
                default -> send(exchange,404,Map.of("error","not_found","message","找不到此 API"));
            }
        }catch(BusyException e){send(exchange,429,Map.of("error","busy","message","目前正在處理其他搜尋，請稍後再試"));}
        catch(TimeoutException e){send(exchange,504,Map.of("error","timeout","message","搜尋超過時間上限，請減少頁數或關閉 AI 後重試"));}
        catch(IllegalArgumentException e){send(exchange,400,Map.of("error","validation","message",safeMessage(e,"輸入格式不正確")));}
        catch(InterruptedException e){Thread.currentThread().interrupt();send(exchange,503,Map.of("error","interrupted","message","搜尋已中止"));}
        catch(Exception e){send(exchange,502,Map.of("error","operation","message",safeMessage(e,"外部服務或網頁讀取失敗，請稍後重試")));}
        finally{exchange.close();}
    }
    private Object bounded(Callable<Object> operation)throws Exception{
        if(!searches.tryAcquire())throw new BusyException();
        var future=executor.submit(()->{try{return operation.call();}finally{searches.release();}});
        try{return future.get(config.integer("SEARCH_TIMEOUT_SECONDS",45,5,120),TimeUnit.SECONDS);}
        catch(TimeoutException e){future.cancel(true);throw e;}
        catch(ExecutionException e){if(e.getCause() instanceof Exception cause)throw cause;throw new IOException("搜尋處理失敗");}
    }
    private Map<String,Object> status(){
        Map<String,Object> map=new LinkedHashMap<>();map.put("app","AI 知識搜尋");map.put("modeDefault","live");
        map.put("capabilities",Map.of("liveSearch",true,"webSearch",providers.searchAvailable(),"jev",providers.jevAvailable(),"answer",providers.answerAvailable()));
        map.put("liveProvider",providers.searchAvailable()?"Brave Search":"Wikipedia + Crossref");
        map.put("liveNotice",providers.searchAvailable()?"搜尋真實公開網頁，不限制 AI 議題或技術網域。未取得全文時保留來源摘要與原文連結，並標示資料範圍。":"免金鑰模式即時查詢 Wikipedia 百科與 Crossref 學術索引，涵蓋 AI 技術、政治、哲學、經濟等議題；這不是整個網際網路的索引。結果可開啟真實來源，全文未讀取時會標示。");
        map.put("limits",Map.of("maxDepth",2,"maxPages",40,"maxResults",10));
        map.put("fixtureNotice","示範模式使用專案自編教材，沒有即時讀取 Google 或原始網站，不能當作搜尋勝出證據。");
        map.put("topics",List.of("AI 概念","遞迴自我改進 RSI","地緣政治","哲學與意識","經濟與就業","政策與社會","技術與應用"));map.put("demoPages",corpus.pages().size());
        map.put("allowedFetchHosts",new SafeFetcher(config).allowedHosts().stream().sorted().toList());return map;
    }
    private Map<String,Object> search(Map<String,Object> request)throws Exception{
        long start=System.nanoTime();String query=required(request,"query",240),intent=choice(request,"intent","concept",Set.of("concept","compare","build"));
        String mode=choice(request,"mode","live",Set.of("demo","live","urls"));
        SearchOptions options=new SearchOptions(query,intent,integer(request,"maxDepth",1,0,2),integer(request,"maxPages",20,1,40),integer(request,"limit",5,1,10),Json.bool(request.get("expand"),true));
        SearchBackend backend;PageSource source;LiveSources live=mode.equals("live")?new LiveSources():null;
        if(mode.equals("demo")){backend=corpus::candidates;source=corpus::fetch;}
        else{
            SafeFetcher fetcher=new SafeFetcher(config);source=fetcher;
            if(mode.equals("live")){
                backend=live::candidates;source=live::fetch;
            }else{
                List<String> urls=strings(request.get("urls"),10,2048);if(urls.isEmpty())throw new IllegalArgumentException("請輸入至少一個網址");
                for(String url:urls)fetcher.validate(url);backend=(q,limit)->urls.stream().limit(limit).toList();
            }
        }
        SearchReport report=new SearchEngine(backend,source).search(options);List<SearchResult> results=new ArrayList<>(report.results());List<String>warnings=new ArrayList<>(report.warnings());
        if(live!=null){
            // A search-index hit remains useful even if its text uses different words or full-page crawling fails.
            java.util.Set<String> seen=new java.util.HashSet<>();results.forEach(r->seen.add(r.url()));int rank=0;
            for(WebHit hit:live.hits.values()){
                rank++;if(results.size()>=options.limit())break;if(!seen.add(hit.url()))continue;
                Map<String,Integer> counts=KeywordCounter.counts(hit.title()+" "+hit.snippet(),report.keywords());
                TreeNode tree=new TreeNode(hit.url(),hit.title(),0,counts,0,List.of(),"snippet","僅取得搜尋來源摘要，未讀取原網頁全文；排名依搜尋來源保留。");
                results.add(new SearchResult(hit.url(),hit.title(),hit.snippet(),hit.provider()+" 搜尋摘要",rank,counts,0,0,0,null,tree));
            }
            if(results.isEmpty()&&!live.warnings.isEmpty())warnings.add("公開搜尋來源未傳回可用資料；請查看來源狀態或使用下方外部搜尋連結。");
        }
        boolean wantJev=Json.bool(request.get("useJev"),false),applied=false;
        if(wantJev){
            if(!providers.jevAvailable())warnings.add("Jev 尚未設定，保留規則排名。");
            else if(!results.isEmpty()){
                try{results=providers.rerank(query,intent,results);applied=results.stream().anyMatch(r->r.jevScore()!=null);if(!applied)warnings.add("Jev 未提供足夠可信的評分，保留規則排名。");}
                catch(InterruptedException e){throw e;}
                catch(Exception e){warnings.add("Jev 呼叫失敗，已保留規則排名。請檢查額度、模型或連線。");}
            }
        }
        Map<String,Object> answer=Map.of("mode","none","text","","citations",List.of());
        if(Json.bool(request.get("answer"),false)){
            if(results.isEmpty())answer=Map.of("mode","extractive","text","目前沒有足夠檢索證據，無法整理答案。","citations",List.of());
            else if(providers.answerAvailable()){
                try{answer=providers.answer(query,results);}
                catch(InterruptedException e){throw e;}
                catch(Exception e){warnings.add("文字生成服務失敗，改列原文摘錄；這不是模型生成答案。");answer=extractive(results);}
            }else{warnings.add("未設定文字生成服務，僅列出原文摘錄。");answer=extractive(results);}
        }
        Map<String,Object> out=Json.object(Json.parse(Json.stringify(report)));
        List<Object> displayed=new ArrayList<>();for(SearchResult result:results){Map<String,Object> row=Json.object(Json.parse(Json.stringify(result)));
            row.put("contentStatus",contentStatus(result,mode,live==null?Set.of():live.fullText));
            if(live!=null)markSnippetNodes(Json.object(row.get("tree")),row,live);
            displayed.add(row);
        }
        out.put("results",displayed);out.put("warnings",warnings);out.put("mode",mode);out.put("sourceWarnings",live==null?List.of():List.copyOf(live.warnings));
        out.put("searchQuery",live==null?query:String.join("；",live.queryHistory));
        out.put("pagesProcessed",report.pagesFetched());if(live!=null)out.put("pagesFetched",live.fullText.size());
        out.put("elapsedMs",(System.nanoTime()-start)/1_000_000);out.put("jev",Map.of("requested",wantJev,"applied",applied));out.put("answer",answer);
        String comparisonQuery=mode.equals("live")?KeywordCounter.webQuery(query):query;
        out.put("baseline",Map.of("provider",mode.equals("demo")?"Demo corpus":mode.equals("live")?String.join(" + ",live.providers):"Provided URLs","googleComparisonUrl","https://www.google.com/search?q="+URLEncoder.encode(comparisonQuery,StandardCharsets.UTF_8),"domainScope",List.of()));
        out.put("runId",UUID.randomUUID().toString());out.put("generatedAt",Instant.now().toString());return out;
    }
    private Map<String,Object> count(Map<String,Object> request)throws Exception{
        String url=required(request,"url",2048),query=required(request,"keywords",200);String mode=choice(request,"mode","demo",Set.of("demo","live"));
        Page page=mode.equals("demo")?corpus.fetch(url):new SafeFetcher(config).fetch(url);
        List<String>keywords=KeywordCounter.literalKeywords(query);if(keywords.isEmpty()||keywords.size()>30)throw new IllegalArgumentException("請提供 1 至 30 個關鍵字，以逗號、分號或換行分隔");
        return Map.of("url",page.url(),"title",page.title(),"counts",KeywordCounter.counts(page.text(),keywords),"totalCharacters",page.text().length(),"algorithm","Boyer-Moore","synthetic",page.synthetic());
    }
    private static Map<String,Object> extractive(List<SearchResult> results){
        List<Map<String,Object>> citations=new ArrayList<>();StringBuilder text=new StringBuilder();int i=0;
        for(SearchResult result:results.stream().limit(3).toList()){String id="S"+(++i);citations.add(Map.of("id",id,"url",result.url(),"title",result.title()));text.append('[').append(id).append("] ").append(result.snippet()).append("\n\n");}
        return Map.of("mode","extractive","text",text.toString().strip(),"citations",citations);
    }
    private boolean validHost(String value){try{URI uri=URI.create("http://"+value);return uri.getHost()!=null&&hosts.stream().map(String::strip).anyMatch(h->h.equalsIgnoreCase(uri.getHost()))&&uri.getRawUserInfo()==null&&(uri.getRawPath()==null||uri.getRawPath().isEmpty());}catch(IllegalArgumentException e){return false;}}
    private boolean authorized(HttpExchange exchange){
        if(accessToken.isEmpty())return true;String value=exchange.getRequestHeaders().getFirst("Authorization");if(value==null||!value.startsWith("Basic "))return false;
        try{String decoded=new String(Base64.getDecoder().decode(value.substring(6)),StandardCharsets.UTF_8);int colon=decoded.indexOf(':');return colon>=0&&MessageDigest.isEqual(accessToken.getBytes(StandardCharsets.UTF_8),decoded.substring(colon+1).getBytes(StandardCharsets.UTF_8));}catch(IllegalArgumentException e){return false;}
    }
    private void staticFile(HttpExchange exchange,String path)throws IOException{
        String file=switch(path){case "/","/index.html"->"index.html";case "/app.js"->"app.js";case "/styles.css"->"styles.css";default->null;};
        if(file==null){send(exchange,404,Map.of("error","not_found","message","找不到頁面"));return;}
        byte[] bytes=Files.readAllBytes(root.resolve("web").resolve(file));String type=file.endsWith("js")?"text/javascript":file.endsWith("css")?"text/css":"text/html";
        exchange.getResponseHeaders().set("Content-Type",type+"; charset=utf-8");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
    }
    private static void securityHeaders(HttpExchange exchange){var h=exchange.getResponseHeaders();h.set("X-Content-Type-Options","nosniff");h.set("Referrer-Policy","no-referrer");h.set("Cache-Control","no-store");h.set("Content-Security-Policy","default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'");h.set("Permissions-Policy","camera=(), microphone=(), geolocation=()");}
    private static void send(HttpExchange exchange,int status,Object value)throws IOException{byte[]bytes=Json.stringify(value).getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);}
    private static String required(Map<String,Object>request,String key,int max){Object raw=request.get(key);if(!(raw instanceof String s)||s.isBlank()||s.length()>max)throw new IllegalArgumentException(key+" 必須是 1 至 "+max+" 字元的文字");return s.strip();}
    private static String choice(Map<String,Object> request,String key,String fallback,Set<String> allowed){String value=Json.string(request.get(key),fallback);if(!allowed.contains(value))throw new IllegalArgumentException(key+" 選項不合法");return value;}
    private static int integer(Map<String,Object>request,String key,int fallback,int min,int max){if(!request.containsKey(key))return fallback;double n=Json.number(request.get(key),Double.NaN);if(!Double.isFinite(n)||n!=(int)n||n<min||n>max)throw new IllegalArgumentException(key+" 超出範圍");return (int)n;}
    private static List<String> strings(Object raw,int max,int length){if(raw==null)return List.of();List<Object>values=Json.array(raw);if(values.size()>max)throw new IllegalArgumentException("網址數量超過上限");List<String>out=new ArrayList<>();for(Object value:values){if(!(value instanceof String s)||s.isBlank()||s.length()>length)throw new IllegalArgumentException("網址格式不正確");out.add(s.strip());}return out;}
    private static void markSnippetNodes(Map<String,Object> node,Map<String,Object> row,LiveSources live){
        markNode(node,live);row.put("tree",node);
    }
    static String contentStatus(SearchResult result,String mode,Set<String> fullText){
        if(mode.equals("demo"))return "demo";
        if(result.tree()!=null&&result.tree().status().equals("snippet"))return "snippet";
        return mode.equals("live")&&!fullText.contains(result.url())?"snippet":"fetched";
    }
    private static void markNode(Map<String,Object> node,LiveSources live){
        String url=Json.string(node.get("url"),"");
        if(live.hits.containsKey(url)&&!live.fullText.contains(url)){
            node.put("status","snippet");node.put("warning","這是搜尋來源摘要的計數，未讀取原網頁全文或子頁面。");
        }
        List<Object> children=new ArrayList<>();for(Object raw:Json.array(node.get("children"))){Map<String,Object> child=Json.object(raw);markNode(child,live);children.add(child);}node.put("children",children);
    }
    private final class LiveSources {
        final Map<String,WebHit> hits=new LinkedHashMap<>();
        final java.util.Set<String> fullText=new java.util.HashSet<>();
        final java.util.Set<String> providers=new java.util.LinkedHashSet<>();
        final java.util.Set<String> warnings=new java.util.LinkedHashSet<>();
        final java.util.Set<String> queryHistory=new java.util.LinkedHashSet<>();
        final SafeFetcher fetcher=new SafeFetcher(config);
        List<String> candidates(String query,int limit)throws Exception{
            List<WebHit> found=null;
            if(App.this.providers.searchAvailable()){
                try{queryHistory.add("Brave: "+KeywordCounter.webQuery(query));found=App.this.providers.searchHits(KeywordCounter.webQuery(query),limit);providers.add("Brave Search");}
                catch(InterruptedException e){throw e;}
                catch(Exception e){warnings.add("Brave 搜尋失敗，改查公開百科與學術索引。");}
            }
            if(found==null){
                queryHistory.add("Wikipedia: "+PublicSearch.wikiQuery(query));queryHistory.add("Crossref: "+PublicSearch.paperQuery(query));
                try{PublicSearch.Batch batch=publicSearch.search(query,limit);found=batch.hits();providers.add(batch.provider());warnings.addAll(batch.warnings());}
                catch(InterruptedException e){throw e;}
                catch(Exception e){warnings.add("公開百科與學術索引暫時無法連線，請稍後重試或開啟外部搜尋連結。");throw e;}
            }
            List<String> urls=new ArrayList<>();for(WebHit hit:found){String url=SearchEngine.canonical(hit.url());if(url==null)continue;hits.putIfAbsent(url,new WebHit(url,hit.title(),hit.snippet(),hit.provider()));urls.add(url);}return urls;
        }
        Page fetch(String url)throws Exception{
            WebHit hit=hits.get(url);
            // Existing allowlist governs optional full-page crawling, never which web search hits may be shown.
            if(fetcher.allowedHosts().contains(URI.create(url).getHost())){
                try{Page page=fetcher.fetch(url);fullText.add(url);return page;}
                catch(InterruptedException e){throw e;}
                catch(Exception e){if(hit==null)throw e;warnings.add("部分來源全文無法讀取，已保留摘要與可開啟的原文連結。");}
            }
            if(hit==null)throw new IOException("此子頁不在本次搜尋摘要或全文允許範圍");
            return new Page(url,hit.title(),hit.snippet(),List.of(),hit.provider()+" 搜尋摘要（未讀取原網頁全文）",false);
        }
    }
    private static String safeMessage(Exception e,String fallback){String message=e.getMessage();return message!=null&&message.length()<=200&&!message.contains("Bearer")&&!message.contains("sk-")&&!message.contains("api_key")?message:fallback;}
    private static final class BusyException extends Exception {private static final long serialVersionUID=1L;}
}
