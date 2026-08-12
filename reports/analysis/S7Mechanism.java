import com.fasterxml.jackson.databind.*;
import java.net.http.*;
import java.net.URI;
import java.time.*;
import java.util.*;

/**
 * Doc 48 §3: resolve S7 mechanism at MINUTE resolution — cascade (forced liquidation) vs selloff (reversion).
 * Events found on hourly (X=8%/10%); for each, fetch 1m klines for the 24h drop window and build fall profile.
 * Classify (threshold fixed BEFORE run): cascade if >=50% of peak->trough drop in worst 15-min span.
 * Main test: bounce premium cascade vs selloff, separately BTC/ETH. Expectation: premium higher for cascades.
 */
public class S7Mechanism {
    static final HttpClient HC = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    static final ObjectMapper M = new ObjectMapper();
    static final double CASCADE_SHARE = 0.50; // >=50% of drop in worst 15 min => cascade (fixed)

    static JsonNode get(String url) throws Exception {
        for(int at=0;at<4;at++){ HttpRequest req=HttpRequest.newBuilder(URI.create(url)).header("User-Agent","curl/8.0").timeout(Duration.ofSeconds(40)).GET().build();
            HttpResponse<String> r=HC.send(req,HttpResponse.BodyHandlers.ofString());
            if(r.statusCode()==200) return M.readTree(r.body());
            if(r.statusCode()==429||r.statusCode()==418){ Thread.sleep(1500L*(at+1)); continue; }
            throw new RuntimeException("HTTP "+r.statusCode()); }
        throw new RuntimeException("retries"); }

    static long hourStart;
    static double[] fetchHourly(String sym) throws Exception {
        long fromSec=Instant.parse("2022-01-01T00:00:00Z").getEpochSecond(); TreeMap<Long,Double> m=new TreeMap<>(); long from=fromSec;
        for(int pg=0;pg<40;pg++){ JsonNode j=get("https://futures.kraken.com/api/charts/v1/trade/"+sym+"/1h?from="+from);
            JsonNode c=j.get("candles"); if(c==null||c.size()==0) break; long last=from;
            for(JsonNode k:c){ m.put(k.get("time").asLong()/3600000L, Double.parseDouble(k.get("close").asText())); last=k.get("time").asLong()/1000; }
            if(c.size()<2000) break; from=last+3600; Thread.sleep(60); }
        long lo=m.firstKey(),hi=m.lastKey(); int n=(int)(hi-lo+1); double[] px=new double[n]; double prev=m.firstEntry().getValue();
        for(int i=0;i<n;i++){ Double v=m.get(lo+i); if(v!=null){px[i]=v;prev=v;} else px[i]=prev; } hourStart=lo; return px; }

    // 1m closes for [fromSec, fromSec+dur]; index0=fromSec minute
    static double[] fetchMinute(long fromSec, int minutes) throws Exception {
        JsonNode j=get("https://futures.kraken.com/api/charts/v1/trade/"+curSym+"/1m?from="+fromSec);
        JsonNode c=j.get("candles"); if(c==null||c.size()==0) return null;
        TreeMap<Long,Double> m=new TreeMap<>();
        for(JsonNode k:c){ long t=k.get("time").asLong()/60000L; m.put(t, Double.parseDouble(k.get("close").asText())); }
        long lo=fromSec/60; double[] px=new double[minutes]; double prev=m.ceilingEntry(lo)!=null?m.ceilingEntry(lo).getValue():0;
        for(int i=0;i<minutes;i++){ Double v=m.get(lo+i); if(v!=null){px[i]=v;prev=v;} else px[i]=prev; } return px; }
    static String curSym;

    public static void main(String[] a) throws Exception {
        String[][] assets={{"BTC","PF_XBTUSD"},{"ETH","PF_ETHUSD"}};
        System.out.println("===== S7 МЕХАНИЗМ: минутный профиль падения (doc 48 §3) =====");
        System.out.println("Каскад = >=50% падения (пик->дно) в худшие 15 минут (порог фикс. до прогона)\n");
        for(String[] as:assets){
            curSym=as[1]; double[] hr=fetchHourly(as[1]); double base=uncond(hr,24);
            for(double X:new double[]{0.10,0.08}){
                List<double[]> ev=new ArrayList<>(); long lastTrig=-10000;
                for(int t=24;t+25<hr.length;t++){ if(hr[t-24]<=0) continue; double ret24=hr[t]/hr[t-24]-1;
                    if(ret24<=-X && t-lastTrig>=24){ double entry=hr[t+1],exit=hr[t+25]; if(entry>0){ ev.add(new double[]{t, exit/entry-1}); lastTrig=t; } } }
                // minute classification per event
                List<Double> casc=new ArrayList<>(), sell=new ArrayList<>(); List<Double> t50c=new ArrayList<>(),t50s=new ArrayList<>();
                int nCasc=0,nSell=0,nSkip=0;
                for(double[] e:ev){
                    int t=(int)e[0]; long trigSec=(hourStart+t)*3600L; long winStart=trigSec-24*3600L;
                    double[] mp; try{ mp=fetchMinute(winStart, 24*60); Thread.sleep(120);}catch(Exception ex){ mp=null; }
                    if(mp==null){ nSkip++; continue; }
                    double[] prof=profile(mp);
                    if(prof==null){ nSkip++; continue; }
                    double share15=prof[0], t50=prof[1];
                    if(share15>=CASCADE_SHARE){ casc.add(e[1]); t50c.add(t50); nCasc++; }
                    else { sell.add(e[1]); t50s.add(t50); nSell++; }
                }
                System.out.printf("%s X=%.0f%%: событий=%d (пропущено %d)  КАСКАД n=%d prem=%+.2f%% (t50 мед=%.0fмин) | РАСПРОДАЖА n=%d prem=%+.2f%% (t50 мед=%.0fмин)%n",
                    as[0], X*100, ev.size(), nSkip,
                    nCasc, nCasc>0?(mean(casc)-base)*100:0, median(t50c),
                    nSell, nSell>0?(mean(sell)-base)*100:0, median(t50s));
            }
        }
        System.out.println("\nКритерий §3.3: премия СУЩЕСТВЕННО выше у каскадов => вынужд. продажи (строить детектор);");
        System.out.println("одинакова => реверсия (переименовать, ужесточить); выше у распродаж => искать ошибку.");
    }

    // returns [share of peak->trough drop in worst 15-min span, t50 minutes peak->50% drop]
    static double[] profile(double[] mp){
        int n=mp.length; if(n<60) return null;
        // trough = global min; peak = max before trough
        int iTrough=0; for(int i=1;i<n;i++) if(mp[i]<mp[iTrough]) iTrough=i;
        int iPeak=0; for(int i=1;i<=iTrough;i++) if(mp[i]>mp[iPeak]) iPeak=i;
        double peak=mp[iPeak], trough=mp[iTrough], drop=peak-trough;
        if(drop<=0) return null;
        // worst 15-min decline anywhere
        double worst15=0; for(int i=0;i+15<n;i++){ double d=mp[i]-min(mp,i,i+15); if(d>worst15) worst15=d; }
        double share15=worst15/drop;
        // t50: minutes from peak to first point at >=50% of drop
        double half=peak-0.5*drop; int t50=iTrough-iPeak;
        for(int i=iPeak;i<=iTrough;i++){ if(mp[i]<=half){ t50=i-iPeak; break; } }
        return new double[]{share15, t50};
    }
    static double min(double[] a,int lo,int hi){ double m=a[lo]; for(int i=lo+1;i<=hi&&i<a.length;i++) m=Math.min(m,a[i]); return m; }
    static double uncond(double[] px,int Mh){ List<Double> u=new ArrayList<>(); for(int t=0;t+Mh<px.length;t++) if(px[t]>0) u.add(px[t+Mh]/px[t]-1); return mean(u); }
    static double mean(List<Double> v){ if(v.isEmpty())return 0; double s=0; for(double x:v)s+=x; return s/v.size(); }
    static double median(List<Double> v){ if(v.isEmpty())return 0; List<Double> s=new ArrayList<>(v); Collections.sort(s); int n=s.size(); return n%2==1?s.get(n/2):(s.get(n/2-1)+s.get(n/2))/2; }
}
