import com.fasterxml.jackson.databind.*;
import java.net.http.*;
import java.net.URI;
import java.time.*;
import java.util.*;

/**
 * Doc 49 §3: speed-based trigger — the gap the depth trigger (>=8-10%/24h) missed.
 * Cascade = SPEED event (>=5% drop in 15 min), invisible on daily scale. Params fixed BEFORE run.
 * Trigger: drop >=5% in 15 min (1m klines); entry N=60min after; hold M=24h; cooldown M. BTC/ETH, 2022-03..now.
 * Compare speed-event premium vs slow (depth) premium; year split; overlap with depth sample.
 * Criterion §3.3: speed premium HIGHER => cascade real; ~ => same reversion; LOWER at n>=20 => cascade truly refuted.
 */
public class S7SpeedTrigger {
    static final HttpClient HC = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    static final ObjectMapper M = new ObjectMapper();

    static JsonNode get(String url) throws Exception {
        for(int at=0;at<5;at++){ HttpRequest req=HttpRequest.newBuilder(URI.create(url)).header("User-Agent","curl/8.0").timeout(Duration.ofSeconds(40)).GET().build();
            HttpResponse<String> r=HC.send(req,HttpResponse.BodyHandlers.ofString());
            if(r.statusCode()==200) return M.readTree(r.body());
            if(r.statusCode()==429||r.statusCode()==418){ Thread.sleep(1500L*(at+1)); continue; }
            throw new RuntimeException("HTTP "+r.statusCode()); }
        throw new RuntimeException("retries"); }

    static long minStart; // first minute index (epoch-minutes)
    static double[] fetchAllMinutes(String sym) throws Exception {
        long fromSec=Instant.parse("2022-01-01T00:00:00Z").getEpochSecond();
        TreeMap<Long,Double> m=new TreeMap<>(); long from=fromSec; int pages=0;
        for(int pg=0;pg<1400;pg++){ JsonNode j=get("https://futures.kraken.com/api/charts/v1/trade/"+sym+"/1m?from="+from);
            JsonNode c=j.get("candles"); if(c==null||c.size()==0) break; long last=from;
            for(JsonNode k:c){ m.put(k.get("time").asLong()/60000L, Double.parseDouble(k.get("close").asText())); last=k.get("time").asLong()/1000; }
            pages++; if(c.size()<2000) break; from=last+60; Thread.sleep(45);
            if(pg%200==0) System.out.println("  "+sym+" page "+pg+" ("+m.size()+" min)"); }
        long lo=m.firstKey(),hi=m.lastKey(); int n=(int)(hi-lo+1); double[] px=new double[n]; double prev=m.firstEntry().getValue();
        for(int i=0;i<n;i++){ Double v=m.get(lo+i); if(v!=null){px[i]=v;prev=v;} else px[i]=prev; }
        minStart=lo; System.out.println("  "+sym+": "+m.size()+" minutes in "+pages+" pages, "+n+" dense; "+Instant.ofEpochMilli(lo*60000L)+" .. "+Instant.ofEpochMilli(hi*60000L));
        return px; }

    static double uncond(double[] px,int Mmin){ List<Double> u=new ArrayList<>(); for(int t=0;t+Mmin<px.length;t+=15) if(px[t]>0) u.add(px[t+Mmin]/px[t]-1); return mean(u); }

    // returns list of [triggerMinuteIdx, tradeRet]
    static List<double[]> speedEvents(double[] px,double X,int win,int N,int Mmin){
        List<double[]> ev=new ArrayList<>(); long last=-100000;
        for(int t=win;t+N+Mmin<px.length;t++){ if(px[t-win]<=0) continue; double r=px[t]/px[t-win]-1;
            if(r<=-X && t-last>=Mmin){ double entry=px[t+N],exit=px[t+N+Mmin]; if(entry>0){ ev.add(new double[]{t, exit/entry-1}); last=t; } } }
        return ev; }

    public static void main(String[] a) throws Exception {
        String[][] assets={{"BTC","PF_XBTUSD"},{"ETH","PF_ETHUSD"}};
        Map<String,double[]> px=new LinkedHashMap<>();
        System.out.println("Fetching full 1m history (это ~1150 страниц/актив)...");
        for(String[] as:assets) px.put(as[0], fetchAllMinutes(as[1]));

        System.out.println("\n===== S7 ТРИГГЕР ПО СКОРОСТИ (doc 49 §3) =====");
        System.out.println("Триггер: падение >=5% за 15мин; вход +60мин; холд 24ч; кулдаун 24ч\n");
        int Mmin=24*60;
        for(var e:px.entrySet()){ double[] p=e.getValue(); double base=uncond(p,Mmin);
            List<double[]> ev=speedEvents(p,0.05,15,60,Mmin);
            List<Double> rets=new ArrayList<>(); int[] yc=new int[3]; double[] ys=new double[3]; int[] yp=new int[3]; int overlap=0;
            for(double[] x:ev){ int t=(int)x[0]; rets.add(x[1]);
                int yr=Instant.ofEpochMilli((minStart+t)*60000L).atZone(ZoneOffset.UTC).getYear(); int b=yr<=2022?0:(yr<=2024?1:2);
                yc[b]++; ys[b]+=x[1]; if(x[1]>0) yp[b]++;
                if(t>=1440 && p[t-1440]>0 && p[t]/p[t-1440]-1<=-0.08) overlap++; } // also depth-trigger?
            Collections.sort(rets);
            System.out.printf("%s: событий=%d  премия над базой=%+.2f%%  медиана=%+.2f%%  %%>0=%.0f%%  худшая=%+.1f%%%n",
                e.getKey(), ev.size(), (mean(rets)-base)*100, median(rets)*100, 100.0*fracPos(rets), rets.isEmpty()?0:rets.get(0)*100);
            System.out.printf("   по годам: 2022 n=%d prem=%+.2f%% | 2023-24 n=%d prem=%+.2f%% | 2025-26 n=%d prem=%+.2f%%%n",
                yc[0], yc[0]>0?(ys[0]/yc[0]-base)*100:0, yc[1], yc[1]>0?(ys[1]/yc[1]-base)*100:0, yc[2], yc[2]>0?(ys[2]/yc[2]-base)*100:0);
            System.out.printf("   пересечение с триггером по ГЛУБИНЕ (>=8%%/24ч): %d/%d (%.0f%%)%n", overlap, ev.size(), ev.isEmpty()?0:100.0*overlap/ev.size());
            // slow (depth) premium on SAME series for comparison
            List<double[]> dep=depthEvents(p,0.08,N(60),Mmin); double depPrem=0; if(!dep.isEmpty()){ double s=0; for(double[] d:dep) s+=d[1]; depPrem=(s/dep.size()-base)*100; }
            System.out.printf("   [сравнение] медленный триггер по глубине >=8%%/24ч: n=%d премия=%+.2f%%%n", dep.size(), depPrem);
        }

        System.out.println("\n===== УСТОЙЧИВОСТЬ (вся таблица) =====");
        System.out.printf("%-4s %-6s %-5s %8s %10s %8s%n","sym","X/15м","M(ч)","событ","прем%","%>0");
        for(String s:px.keySet()){ double[] p=px.get(s);
            for(double X:new double[]{0.03,0.05,0.08}) for(int Mh:new int[]{4,24,48}){ int mm=Mh*60; double base=uncond(p,mm);
                List<double[]> ev=speedEvents(p,X,15,60,mm); List<Double> r=new ArrayList<>(); for(double[] x:ev) r.add(x[1]);
                System.out.printf("%-4s %-6.0f %-5d %8d %10.2f %8.0f%n", s, X*100, Mh, ev.size(), (mean(r)-base)*100, 100.0*fracPos(r)); } }
        System.out.println("\nКритерий §3.3: скоростная премия ВЫШЕ медленной => каскад реален (t03-v3 §5.2); ~ => та же реверсия; НИЖЕ при n>=20 => каскад опровергнут по-настоящему");
    }
    static int N(int n){ return n; }
    static List<double[]> depthEvents(double[] px,double X,int N,int Mmin){ List<double[]> ev=new ArrayList<>(); long last=-100000; int win=1440;
        for(int t=win;t+N+Mmin<px.length;t+=15){ if(px[t-win]<=0) continue; double r=px[t]/px[t-win]-1;
            if(r<=-X && t-last>=Mmin){ double entry=px[t+N],exit=px[t+N+Mmin]; if(entry>0){ ev.add(new double[]{t,exit/entry-1}); last=t; } } }
        return ev; }
    static double mean(List<Double> v){ if(v.isEmpty())return 0; double s=0; for(double x:v)s+=x; return s/v.size(); }
    static double median(List<Double> v){ if(v.isEmpty())return 0; List<Double> s=new ArrayList<>(v); Collections.sort(s); int n=s.size(); return n%2==1?s.get(n/2):(s.get(n/2-1)+s.get(n/2))/2; }
    static double fracPos(List<Double> v){ if(v.isEmpty())return 0; int c=0; for(double x:v) if(x>0)c++; return (double)c/v.size(); }
}
