import com.fasterxml.jackson.databind.*;
import java.net.http.*;
import java.net.URI;
import java.time.*;
import java.util.*;

/**
 * Doc 46 §7 / doc 29 §3: S7 trivial benchmark BEFORE any cascade detector.
 * "Buy N hours after an X% drop, hold M hours." Params fixed in advance: X=-10%/24h, N=1h, M=24h.
 * Universe BTC, ETH (doc 29 §2.6). Kraken 1h klines (perp history from ~2022-03).
 * Question: does a post-panic bounce premium exist over the unconditional M-hour return?
 * Robustness table (X x M) reported WHOLE afterwards, not cherry-picked (doc rule).
 */
public class S7TrivialBenchmark {
    static final HttpClient HC = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    static final ObjectMapper M = new ObjectMapper();

    static JsonNode get(String url) throws Exception {
        HttpRequest req=HttpRequest.newBuilder(URI.create(url)).header("User-Agent","curl/8.0").timeout(Duration.ofSeconds(40)).GET().build();
        HttpResponse<String> r=HC.send(req,HttpResponse.BodyHandlers.ofString());
        if(r.statusCode()!=200) throw new RuntimeException("HTTP "+r.statusCode()); return M.readTree(r.body()); }

    // hourly close series (index = hour bar), ascending
    static double[] fetch(String sym) throws Exception {
        long fromSec = Instant.parse("2022-01-01T00:00:00Z").getEpochSecond();
        TreeMap<Long,Double> m=new TreeMap<>();
        long from=fromSec;
        for(int pg=0; pg<40; pg++){
            JsonNode j=get("https://futures.kraken.com/api/charts/v1/trade/"+sym+"/1h?from="+from);
            JsonNode c=j.get("candles"); if(c==null||c.size()==0) break; long last=from;
            for(JsonNode k:c){ long t=k.get("time").asLong()/3600000L; m.put(t, Double.parseDouble(k.get("close").asText())); last=k.get("time").asLong()/1000; }
            if(c.size()<2000) break; from=last+3600; Thread.sleep(60);
        }
        // dense array over contiguous hours
        long lo=m.firstKey(), hi=m.lastKey(); int n=(int)(hi-lo+1);
        double[] px=new double[n]; double prev=m.firstEntry().getValue();
        for(int i=0;i<n;i++){ Double v=m.get(lo+i); if(v!=null){ px[i]=v; prev=v; } else px[i]=prev; } // ffill gaps
        System.out.println("  "+sym+": "+m.size()+" hourly bars, "+Instant.ofEpochMilli(lo*3600000L)+" .. "+Instant.ofEpochMilli(hi*3600000L));
        return px;
    }

    // event study: trigger when 24h return <= -X; enter at +N h; exit at +N+M h; cooldown M h
    static double[] study(double[] px, double X, int N, int Mh){
        int n=px.length; List<Double> trades=new ArrayList<>(); long lastTrig=-1000;
        for(int t=24; t+N+Mh<n; t++){
            if(px[t-24]<=0) continue;
            double ret24=px[t]/px[t-24]-1;
            if(ret24<=-X && t-lastTrig>=Mh){
                double entry=px[t+N], exit=px[t+N+Mh];
                if(entry>0){ trades.add(exit/entry-1); lastTrig=t; }
            }
        }
        // unconditional M-hour return (all bars) for baseline
        List<Double> uncond=new ArrayList<>();
        for(int t=0;t+Mh<n;t++){ if(px[t]>0) uncond.add(px[t+Mh]/px[t]-1); }
        double mTr=mean(trades), medTr=median(trades), mU=mean(uncond);
        return new double[]{trades.size(), mTr, medTr, fracPos(trades), min(trades), max(trades), mU};
    }

    public static void main(String[] a) throws Exception {
        System.out.println("Fetching Kraken 1h...");
        Map<String,double[]> px=new LinkedHashMap<>();
        px.put("BTC", fetch("PF_XBTUSD"));
        px.put("ETH", fetch("PF_ETHUSD"));

        System.out.println("\n===== S7 TRIVIAL BENCHMARK (fade panic) =====");
        System.out.println("Предрег. параметры: X=-10%/24ч, N=1ч вход, M=24ч холд. Вселенная BTC/ETH.");
        for(var e:px.entrySet()){
            double[] r=study(e.getValue(), 0.10, 1, 24);
            System.out.printf("%n%s: событий=%d  сделка mean=%+.2f%% median=%+.2f%%  %%>0=%.0f%%  худш=%+.1f%% лучш=%+.1f%%%n",
                e.getKey(), (int)r[0], r[1]*100, r[2]*100, r[3]*100, r[4]*100, r[5]*100);
            System.out.printf("   безусловная 24ч-доходность (база) = %+.3f%%  => premium сделки над базой = %+.2f%%%n",
                r[6]*100, (r[1]-r[6])*100);
        }

        System.out.println("\n===== УСТОЙЧИВОСТЬ (вся таблица, не лучшая строка) =====");
        System.out.printf("%-4s %-6s %-5s %8s %10s %8s %10s%n","sym","X","M","событ","mean%","%>0","premium%");
        for(String s:px.keySet()) for(double X:new double[]{0.08,0.10,0.15,0.20}) for(int Mh:new int[]{12,24,48}){
            double[] r=study(px.get(s), X, 1, Mh);
            System.out.printf("%-4s %-6.0f %-5d %8d %10.2f %8.0f %10.2f%n", s, X*100, Mh, (int)r[0], r[1]*100, r[3]*100, (r[1]-r[6])*100);
        }
        System.out.println("\nПорог: premium сделки над безусловной доходностью устойчиво >0 и материален => есть отскок-эффект (строить детектор); ~0/шум => S7-фейд на дневном триггере пуст");
    }

    static double mean(List<Double> v){ if(v.isEmpty())return 0; double s=0; for(double x:v)s+=x; return s/v.size(); }
    static double median(List<Double> v){ if(v.isEmpty())return 0; List<Double> s=new ArrayList<>(v); Collections.sort(s); int n=s.size(); return n%2==1?s.get(n/2):(s.get(n/2-1)+s.get(n/2))/2; }
    static double fracPos(List<Double> v){ if(v.isEmpty())return 0; int c=0; for(double x:v) if(x>0)c++; return (double)c/v.size(); }
    static double min(List<Double> v){ double m=0; for(double x:v) m=Math.min(m,x); return m; }
    static double max(List<Double> v){ double m=0; for(double x:v) m=Math.max(m,x); return m; }
}
