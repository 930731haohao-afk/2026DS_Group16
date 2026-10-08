package edu.nccu.ai;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Metrics are computed only from submitted human judgments, never invented. */
public final class Evaluation {
    private Evaluation() { }
    public static Map<String,Object> evaluate(List<Object> rows){
        if(rows.size()>5000)throw new IllegalArgumentException("最多可計算 5000 筆評分");
        Map<String,Map<String,List<Map<String,Object>>>> groups=new LinkedHashMap<>();Set<String> unique=new HashSet<>();
        for(Object raw:rows){Map<String,Object> row=Json.object(raw);String system=Json.string(row.get("system"),"").strip(),id=Json.string(row.get("queryId"),"").strip();
            double rankValue=Json.number(row.get("rank"),0);int rank=(int)rankValue;
            if(system.isEmpty()||system.length()>80||id.isEmpty()||id.length()>80||rank<1||rank>5||rank!=rankValue)throw new IllegalArgumentException("評分需包含 queryId、system 與 1–5 的整數排名");
            if(!(row.get("relevant") instanceof Boolean))throw new IllegalArgumentException("relevant 必須是 true 或 false");
            if(!unique.add(system+"\u0000"+id+"\u0000"+rank))throw new IllegalArgumentException("同題、同系統、同名次不可重複評分");
            if(row.containsKey("seconds")&&row.get("seconds")!=null){double seconds=Json.number(row.get("seconds"),-1);if(seconds<0||seconds>86400||!Double.isFinite(seconds))throw new IllegalArgumentException("作答秒數不合法");}
            groups.computeIfAbsent(system,k->new LinkedHashMap<>()).computeIfAbsent(id,k->new ArrayList<>()).add(row);
        }
        List<Map<String,Object>> systems=new ArrayList<>();List<String>warnings=new ArrayList<>();
        for(var system:groups.entrySet()){
            double precision=0,mrr=0,seconds=0;int timed=0,judged=0;
            for(var query:system.getValue().values()){
                int matches=0,first=6;Double time=null;
                for(var row:query){judged++;if(Json.bool(row.get("relevant"),false)){matches++;first=Math.min(first,(int)Json.number(row.get("rank"),6));}
                    if(row.get("seconds") instanceof Number n){if(time!=null&&Double.compare(time,n.doubleValue())!=0)throw new IllegalArgumentException("同一題同系統的完成時間需一致");time=n.doubleValue();}}
                precision+=matches/5.0;mrr+=first<=5?1.0/first:0;
                if(time!=null){seconds+=time;timed++;}if(query.size()<5)warnings.add(system.getKey()+" 的部分題目少於 5 筆，缺漏名次按未命中計算。");
            }
            int count=system.getValue().size();Map<String,Object> metric=new LinkedHashMap<>();metric.put("system",system.getKey());metric.put("queries",count);metric.put("precisionAt5",precision/count);metric.put("mrrAt5",mrr/count);metric.put("meanSeconds",timed==0?null:seconds/timed);metric.put("timedQueries",timed);metric.put("judgedRows",judged);systems.add(metric);
        }
        return Map.of("systems",systems,"warnings",warnings.stream().distinct().toList(),"note","僅統計已評分題目；各系統需使用相同題目與設定才能比較。");
    }
}
