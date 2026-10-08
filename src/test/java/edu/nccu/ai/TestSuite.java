package edu.nccu.ai;

import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Run with java -ea; no network keys and no external service calls. */
public final class TestSuite {
    private static int checks;
    private TestSuite() { }
    public static void main(String[]args)throws Exception{
        checks+=CoreTests.run();checks+=ProvidersTest.run();checks+=PublicSearchTests.run();checks+=EvaluationRunnerTests.run();json();security();evaluation();api();
        System.out.println("PASS: "+checks+" checks (core, providers, security, metrics, HTTP integration)");
    }
    private static void check(boolean condition,String message){checks++;if(!condition)throw new AssertionError(message);}
    @FunctionalInterface private interface Action{void run()throws Exception;}
    private static void rejects(Action action,String message)throws Exception{checks++;try{action.run();}catch(IllegalArgumentException|java.io.IOException expected){return;}throw new AssertionError(message);}
    private static void json()throws Exception{
        Map<String,Object>source=Map.of("text","繁體中文\n\"hello\" 🤖","items",List.of(1,true,3.5));String encoded=Json.stringify(source);
        Map<String,Object>parsed=Json.object(Json.parse(encoded));check(parsed.get("text").equals(source.get("text")),"Unicode JSON roundtrip");
        for(String invalid:List.of("{\"x\":1,\"x\":2}","[1,]","{x:1}","01","NaN","1e999","\"a\nb\"","{} true","[","{\"x\":}"))rejects(()->Json.parse(invalid),"invalid JSON accepted");
        rejects(()->Json.parse("[".repeat(100)+"0"+"]".repeat(100)),"deep JSON");
        rejects(()->Json.stringify(Double.NaN),"NaN serialize");
        rejects(()->Json.parse("1\u0661"),"non-ASCII digit");
        rejects(()->Json.parse("\"\\u+123\""),"signed Unicode escape");
    }
    private static void security()throws Exception{
        for(String ip:List.of("127.0.0.1","10.0.0.1","172.16.0.1","192.168.1.1","169.254.169.254","100.64.0.1","0.0.0.0","224.0.0.1","::1","fc00::1","fe80::1","2001:db8::1","192.0.2.1"))check(!SafeFetcher.isPublicAddress(InetAddress.getByName(ip)),"blocked IP "+ip);
        for(String ip:List.of("8.8.8.8","1.1.1.1","2606:4700:4700::1111"))check(SafeFetcher.isPublicAddress(InetAddress.getByName(ip)),"public IP "+ip);
        SafeFetcher fetcher=new SafeFetcher(new Config(Map.of("FETCH_ALLOWED_HOSTS","127.0.0.1,localhost,example.com")));
        for(String url:List.of("file:///etc/passwd","http://example.com","https://127.0.0.1/","https://localhost/","https://example.com:8000/","https://user:pass@example.com/","https://evil.example/"))rejects(()->fetcher.validate(url),"unsafe URL "+url);
        String rules="User-agent: *\nDisallow: /private\nAllow: /private/public\nDisallow: /*.pdf$";
        check(!SafeFetcher.robotsAllows(rules,"/private/a"),"robots disallow");check(SafeFetcher.robotsAllows(rules,"/private/public/a"),"robots longest allow");check(!SafeFetcher.robotsAllows(rules,"/x.pdf"),"robots wildcard");check(SafeFetcher.robotsAllows(rules,"/x.pdf.html"),"robots end anchor");
        check(!SafeFetcher.robotsAllows("User-agent: AIKnowledgeSearch\nDisallow: /blocked\nUser-agent: *\nAllow: /","/blocked"),"specific bot robots");
        check(!SafeFetcher.robotsAllows("User-agent: *\nDisallow: /private","/%70rivate/x"),"robots encoded unreserved");
        check(!SafeFetcher.robotsAllows("User-agent: *\nDisallow: /private","/public/%2e%2e/private/x"),"robots encoded dot traversal");
        check(SafeFetcher.robotsAllows("User-agent: AIKnowledgeSearch\nDisallow:\nUser-agent: *\nDisallow: /","/anything"),"empty specific group");
        check(!SafeFetcher.robotsAllows("User-agent: *\nDisallow: /資料","/%E8%B3%87%E6%96%99/x"),"robots UTF8 equivalence");
        check(!SafeFetcher.robotsAllows("User-agent: *\nDisallow: /",""),"robots empty root path");
        check(!SafeFetcher.robotsAllows("User-agent: *\nDisallow: /","?q=1"),"robots query-only root path");
        check(!KeywordCounter.webQuery("AI 地緣政治").contains("site:"),"no technical-domain search restriction");
        String url="https://example.com/source";
        var snippetTree=new Models.TreeNode(url,"Title",0,Map.of(),0,List.of(),"snippet","");
        var snippetResult=new Models.SearchResult(url,"Title","Provider snippet","Index",1,Map.of(),0,0,0,null,snippetTree);
        check(App.contentStatus(snippetResult,"live",java.util.Set.of(url)).equals("snippet"),"fallback remains snippet even if earlier fulltext fetched");
        check(App.contentStatus(snippetResult,"demo",java.util.Set.of()).equals("demo"),"demo remains explicit");
    }
    private static void evaluation()throws Exception{
        List<Object>rows=new ArrayList<>();for(int rank=1;rank<=5;rank++)rows.add(Map.of("queryId","q1","system","rules","rank",rank,"relevant",rank==2||rank==4,"seconds",10));
        Map<String,Object>result=Evaluation.evaluate(rows),metric=Json.object(Json.array(result.get("systems")).getFirst());
        check(Math.abs(Json.number(metric.get("precisionAt5"),0)-.4)<1e-9,"P@5");check(Json.number(metric.get("mrrAt5"),0)==.5,"MRR");check(Json.number(metric.get("meanSeconds"),0)==10,"seconds per query");
        List<Object>duplicate=new ArrayList<>(rows);duplicate.add(rows.getFirst());rejects(()->Evaluation.evaluate(duplicate),"duplicate judgments");
        rejects(()->Evaluation.evaluate(List.of(Map.of("queryId","q","system","s","rank",1.5,"relevant",true))),"fractional rank");
        check(Json.array(Evaluation.evaluate(List.of()).get("systems")).isEmpty(),"no fabricated results");
    }
    private static void api()throws Exception{
        HttpClient client=HttpClient.newHttpClient();
        Config localConfig=new Config(Map.of("APP_PORT","0","APP_HOST","127.0.0.1"));
        PublicSearch fixturePublic=new PublicSearch(localConfig,request->{
            if(request.uri().getHost().endsWith("wikipedia.org"))return new Providers.Reply(200,Json.stringify(Map.of("batchcomplete",true,"query",Map.of("pages",List.of(Map.of("title","Recursive self-improvement","index",1,"fullurl","https://en.wikipedia.org/wiki/Recursive_self-improvement","extract","Recursive self-improvement is an AI concept."))))));
            return new Providers.Reply(200,Json.stringify(Map.of("status","ok","message",Map.of("items",List.of()))));
        });
        try(App app=new App(Path.of("."),localConfig,fixturePublic)){
            app.start();String base="http://127.0.0.1:"+app.port();
            var health=client.send(HttpRequest.newBuilder(URI.create(base+"/api/health")).build(),HttpResponse.BodyHandlers.ofString());check(health.statusCode()==200,"health");
            var status=client.send(HttpRequest.newBuilder(URI.create(base+"/api/status")).build(),HttpResponse.BodyHandlers.ofString());
            check(status.body().contains("fixtureNotice"),"fixture disclosure");check(!status.body().contains("API_KEY"),"no secret names in capabilities");
            var search=post(client,base,"/api/search",Map.of("query","RAG Python 範例","intent","build","mode","demo","expand",true),null);
            check(search.statusCode()==200,"search HTTP");Map<String,Object>r=Json.object(Json.parse(search.body()));check(!Json.array(r.get("results")).isEmpty(),"search results");check(r.get("mode").equals("demo"),"mode provenance");
            check(search.headers().firstValue("Content-Security-Policy").isPresent(),"CSP");
            check(Json.object(Json.parse(status.body())).get("modeDefault").equals("live"),"live is default");
            var live=post(client,base,"/api/search",Map.of("query","RSI","expand",false),null);
            check(live.statusCode()==200,"no-key live search");Map<String,Object> liveReport=Json.object(Json.parse(live.body()));
            Map<String,Object> hit=Json.object(Json.array(liveReport.get("results")).getFirst());
            check(hit.get("url").equals("https://en.wikipedia.org/wiki/Recursive_self-improvement"),"real external source link retained");
            check(hit.get("contentStatus").equals("snippet"),"snippet provenance");
            check(Json.object(hit.get("tree")).get("status").equals("snippet"),"summary counting not fulltext");
            check(Json.number(liveReport.get("pagesFetched"),-1)==0,"no invented fulltext fetch count");
            var unmatched=post(client,base,"/api/search",Map.of("query","未知名詞xyz","mode","live","expand",false),null);
            check(!Json.array(Json.object(Json.parse(unmatched.body())).get("results")).isEmpty(),"provider hits not dropped by unknown local keywords");
            check(post(client,base,"/api/search",Map.of("query","RAG","maxPages",200),null).statusCode()==400,"crawl budget validation");
            check(post(client,base,"/api/search",Map.of("query","RAG"),"https://evil.example").statusCode()==403,"cross origin denied");
            check(post(client,base,"/api/search",Map.of("query",""),null).statusCode()==400,"empty query");
            String url=DemoCorpus.load(Path.of("data/demo-corpus.json")).pages().getFirst().url();
            var count=post(client,base,"/api/count",Map.of("url",url,"keywords","RAG","mode","demo"),null);check(count.statusCode()==200,"counter");check(count.body().contains("Boyer-Moore"),"counter algorithm");
            var invalid=client.send(HttpRequest.newBuilder(URI.create(base+"/.env")).build(),HttpResponse.BodyHandlers.ofString());check(invalid.statusCode()==404,"no file exposure");
            var page=client.send(HttpRequest.newBuilder(URI.create(base+"/")).build(),HttpResponse.BodyHandlers.ofString());check(page.statusCode()==200&&page.body().contains("<html"),"static UI");
        }
        try(App app=new App(Path.of("."),new Config(Map.of("APP_PORT","0","APP_ACCESS_TOKEN","not-a-key-local-test")))){
            app.start();var r=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+app.port()+"/api/status")).build(),HttpResponse.BodyHandlers.ofString());check(r.statusCode()==401,"token protects API");
        }
        rejects(()->new App(Path.of("."),new Config(Map.of("APP_HOST","0.0.0.0","APP_PORT","0"))),"public bind needs protection");
    }
    private static HttpResponse<String> post(HttpClient client,String base,String path,Object body,String origin)throws Exception{
        var builder=HttpRequest.newBuilder(URI.create(base+path)).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(Json.stringify(body)));
        if(origin!=null)builder.header("Origin",origin);return client.send(builder.build(),HttpResponse.BodyHandlers.ofString());
    }
}
