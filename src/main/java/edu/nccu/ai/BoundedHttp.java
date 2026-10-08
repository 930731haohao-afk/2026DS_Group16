package edu.nccu.ai;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Applies the deadline to the entire response body, not merely response headers. */
final class BoundedHttp {
    private BoundedHttp() { }
    static HttpResponse<byte[]> send(HttpClient client,HttpRequest request,int maxBytes)throws IOException,InterruptedException{
        var pending=client.sendAsync(request,info->new Subscriber(maxBytes));
        try{return pending.get(request.timeout().orElseThrow().toMillis(),TimeUnit.MILLISECONDS);}
        catch(TimeoutException e){throw new IOException("網頁讀取逾時");}
        catch(ExecutionException e){throw new IOException("網頁連線或讀取失敗");}
        finally{if(!pending.isDone())pending.cancel(true);}
    }
    private static final class Subscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> done=new CompletableFuture<>();
        private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        private final int limit;private Flow.Subscription subscription;
        Subscriber(int limit){this.limit=limit;}
        @Override public CompletionStage<byte[]> getBody(){return done;}
        @Override public void onSubscribe(Flow.Subscription s){subscription=s;s.request(1);}
        @Override public void onNext(List<ByteBuffer> buffers){
            for(ByteBuffer buffer:buffers){int n=buffer.remaining();if(n>limit-bytes.size()){subscription.cancel();done.completeExceptionally(new IOException("網頁超過抓取上限"));return;}
                byte[]data=new byte[n];buffer.get(data);bytes.writeBytes(data);}
            subscription.request(1);
        }
        @Override public void onError(Throwable error){done.completeExceptionally(error);}
        @Override public void onComplete(){done.complete(bytes.toByteArray());}
    }
}
