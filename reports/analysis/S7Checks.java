import com.fasterxml.jackson.databind.*;
import java.net.http.*;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

/**
 * Doc 47: four checks before building the S7 detector.
 * A (§3, blocking): premium by year (2022 / 2023-24 / 2025-26) — must stay positive OUTSIDE 2022.
 * C (§5, blocking): tail/distribution + stop-loss -5%/-10% (sold-option profile check, like S3).
 * D (§6, blocking): mechanism — split by fall SPEED (cascade vs slow selloff) as liquidation proxy.
 * B (§4, non-blocking): other markets (S&P/gold/WTI daily, per-market X) — independent validation.
 * Params fixed: X=-10%/24h, N=1h, M=24h (crypto); year-check may lower X to 8% (sample-expansion, flagged).
 */
public class S7Checks {
    static final HttpClient HC = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    static final ObjectMapper M = new ObjectMapper();

    static JsonNode get(String url,String ua) throws Exception {
        HttpRequest req=HttpRequest.newBuilder(URI.create(url)).header("User-Agent",ua).timeout(Duration.ofSeconds(40)).GET().build();
        HttpResponse<String> r=HC.send(req,HttpResponse.BodyHandlers.ofString());
        if(r.statusCode()!=200) throw new RuntimeException("HTTP "+r.statusCode()); return M.readTree(r.body()); }

    static double[] fetchKraken(String sym) throws Exception {
        long fromSec=Instant.parse("2022-01-01T00:00:00Z").getEpochSecond(); TreeMap<Long,Double> m=new TreeMap<>(); long from=fromSec;
        for(int pg=0;pg<40;pg++){ JsonNode j=get("https://futures.kraken.com/api/charts/v1/trade/"+sym+"/1h?from="+from,"curl/8.0");
            JsonNode c=j.get("candles"); if(c==null||c.size()==0) break; long last=from;
            for(JsonNode k:c){ m.put(k.get("time").asLong()/3600000L, Double.parseDouble(k.get("close").asText())); last=k.get("time").asLong()/1000; }
            if(c.size()<2000) break; from=last+3600; Thread.sleep(60); }
        long lo=m.firstKey(),hi=m.lastKey(); int n=(int)(hi-lo+1); double[] px=new double[n]; double prev=m.firstEntry().getValue();
        for(int i=0;i<n;i++){ Double v=m.get(lo+i); if(v!=null){px[i]=v;prev=v;} else px[i]=prev; }
        S7hourStart=lo; return px; }
    static long S7hourStart;

    // event = [entryIdx, tradeRet, yearBucket(0=2022,1=2023-24,2=2025-26), fallConc]
    static List<double[]> events(double[] px,double X,int N,int Mh){
        List<double[]> ev=new ArrayList<>(); long lastTrig=-10000;
        for(int t=24; t+N+Mh<px.length; t++){ if(px[t-24]<=0) continue; double ret24=px[t]/px[t-24]-1;
            if(ret24<=-X && t-lastTrig>=Mh){ double entry=px[t+N], exit=px[t+N+Mh]; if(entry<=0) continue;
                double worstHr=0; for(int j=t-23;j<=t;j++){ if(px[j-1]>0){ double hr=px[j]/px[j-1]-1; worstHr=Math.min(worstHr,hr);} }
                double conc = ret24<0? worstHr/ret24 : 0; // share of drop in worst single hour
                int yr=Instant.ofEpochMilli((S7hourStart+t)*3600000L).atZone(ZoneOffset.UTC).getYear();
                int bucket = yr<=2022?0:(yr<=2024?1:2);
                ev.add(new double[]{t, exit/entry-1, bucket, conc}); lastTrig=t; } }
        return ev;
    }
    static double uncond(double[] px,int Mh){ List<Double> u=new ArrayList<>(); for(int t=0;t+Mh<px.length;t++) if(px[t]>0) u.add(px[t+Mh]/px[t]-1); return mean(u); }

    public static void main(String[] a) throws Exception {
        Map<String,double[]> px=new LinkedHashMap<>();
        System.out.println("Fetching Kraken 1h BTC/ETH...");
        px.put("BTC",fetchKraken("PF_XBTUSD")); px.put("ETH",fetchKraken("PF_ETHUSD"));

        // ---- Check A: by year ----
        System.out.println("\n===== ПРОВЕРКА A: разбиение по годам (§3, блокирующая) =====");
        for(var e:px.entrySet()){ double[] p=e.getValue(); double base=uncond(p,24);
            for(double X:new double[]{0.10,0.08}){ List<double[]> ev=events(p,X,1,24);
                int[] cnt=new int[3]; double[] sum=new double[3]; int[] pos=new int[3];
                for(double[] x:ev){ int b=(int)x[2]; cnt[b]++; sum[b]+=x[1]; if(x[1]>0) pos[b]++; }
                System.out.printf("%s X=%.0f%%: 2022 n=%d prem=%+.2f%% (%.0f%%+) | 2023-24 n=%d prem=%+.2f%% (%.0f%%+) | 2025-26 n=%d prem=%+.2f%% (%.0f%%+)%n",
                    e.getKey(),X*100,
                    cnt[0],cnt[0]>0?(sum[0]/cnt[0]-base)*100:0, cnt[0]>0?100.0*pos[0]/cnt[0]:0,
                    cnt[1],cnt[1]>0?(sum[1]/cnt[1]-base)*100:0, cnt[1]>0?100.0*pos[1]/cnt[1]:0,
                    cnt[2],cnt[2]>0?(sum[2]/cnt[2]-base)*100:0, cnt[2]>0?100.0*pos[2]/cnt[2]:0);
            }
        }
        System.out.println("Критерий A: премия вне 2022 (2023-24 И/ИЛИ 2025-26) остаётся положительной, иначе закрытие");

        // ---- Check C: tail + stop-loss ----
        System.out.println("\n===== ПРОВЕРКА C: распределение и хвост (§5, блокирующая) =====");
        for(var e:px.entrySet()){ double[] p=e.getValue(); List<double[]> ev=events(p,0.10,1,24);
            List<Double> rets=new ArrayList<>(); for(double[] x:ev) rets.add(x[1]); Collections.sort(rets);
            int n=rets.size(); int w10=Math.max(1,n/10); double worstMean=0; for(int i=0;i<w10;i++) worstMean+=rets.get(i); worstMean/=w10;
            // stop-loss simulation on hourly closes
            double slMean5=slMean(p,ev,0.05), slMean10=slMean(p,ev,0.10), noSl=mean(rets);
            System.out.printf("%s: n=%d медиана=%+.2f%% %%>0=%.0f%% худшая=%+.1f%% худшие10%%mean=%+.1f%% | стоп-5%%: mean=%+.2f%% стоп-10%%: mean=%+.2f%% (без стопа %+.2f%%)%n",
                e.getKey(),n, median(rets)*100,100.0*fracPos(rets),rets.get(0)*100,worstMean*100, slMean5*100, slMean10*100, noSl*100);
        }
        System.out.println("Критерий C: не профиль проданного опциона (убыток не в редких катастрофах при высокой доле прибыльных)");

        // ---- Check D: mechanism (fall speed proxy) ----
        System.out.println("\n===== ПРОВЕРКА D: механизм — скорость падения (§6, блокирующая) =====");
        System.out.println("(лента ликвидаций исторически недоступна — прокси: концентрация падения в худший час)");
        for(var e:px.entrySet()){ double[] p=e.getValue(); double base=uncond(p,24); List<double[]> ev=events(p,0.08,1,24);
            List<double[]> byConc=new ArrayList<>(ev); byConc.sort((x,y)->Double.compare(x[3],y[3]));
            int half=byConc.size()/2; double loSum=0,hiSum=0; int lo=0,hi=0;
            for(int i=0;i<byConc.size();i++){ if(i<half){loSum+=byConc.get(i)[1];lo++;} else {hiSum+=byConc.get(i)[1];hi++;} }
            System.out.printf("%s (X=8%%, n=%d): МЕДЛЕННОЕ падение (низк.конц, n=%d) prem=%+.2f%% | БЫСТРОЕ (высок.конц, n=%d) prem=%+.2f%%%n",
                e.getKey(),ev.size(), lo, lo>0?(loSum/lo-base)*100:0, hi, hi>0?(hiSum/hi-base)*100:0);
        }
        System.out.println("Критерий D: премия ВЫШЕ при быстром падении => вынужд. продажи (прочно); не зависит => реверсия (слабо)");

        // ---- Check B: other markets (daily, non-blocking) ----
        System.out.println("\n===== ПРОВЕРКА B: другие рынки (§4, не блокирует) =====");
        String ua="Mozilla/5.0 (Windows NT 10.0; Win64; x64)";
        String[][] mkts={{"S&P500","^GSPC","0.04"},{"Золото","GC=F","0.04"},{"Нефть WTI","CL=F","0.07"},{"TSLA(вол.акция)","TSLA","0.10"}};
        for(String[] mk:mkts){ try{
            double[] c=fetchYahooClose(mk[1],ua); Thread.sleep(400); double X=Double.parseDouble(mk[2]);
            // daily: trigger close[d]/close[d-1]-1 <= -X; entry close[d]; hold 1 day -> exit close[d+1]
            List<Double> tr=new ArrayList<>(); List<Double> u=new ArrayList<>();
            for(int d=1;d+1<c.length;d++){ if(c[d-1]>0){ if(c[d]/c[d-1]-1<=-X) tr.add(c[d+1]/c[d]-1); } if(c[d]>0) u.add(c[d+1]/c[d]-1); }
            System.out.printf("%-16s X=%.0f%%: событий=%d  премия над базой=%+.2f%% (%.0f%%+, база %+.3f%%)%n",
                mk[0],X*100,tr.size(),(mean(tr)-mean(u))*100,100.0*fracPos(tr),mean(u)*100);
        }catch(Exception ex){ System.out.printf("%-16s ошибка: %s%n",mk[0],ex.getMessage()); } }
        System.out.println("Чтение: премия положительна на др. рынках => эффект общерыночный (сильнее); только крипта => крипто-специфика");
    }

    static double slMean(double[] px,List<double[]> ev,double stop){ List<Double> r=new ArrayList<>();
        for(double[] x:ev){ int t=(int)x[0]+1; double entry=px[t]; if(entry<=0) continue; double res=px[t+24]/entry-1;
            for(int j=t+1;j<=t+24 && j<px.length;j++){ if(px[j]/entry-1<=-stop){ res=-stop; break; } } r.add(res); }
        return mean(r); }
    static double[] fetchYahooClose(String sym,String ua) throws Exception {
        String url="https://query1.finance.yahoo.com/v8/finance/chart/"+URLEncoder.encode(sym,StandardCharsets.UTF_8)+"?period1=1640995200&period2="+Instant.now().getEpochSecond()+"&interval=1d";
        JsonNode res=get(url,ua).path("chart").path("result").path(0); JsonNode cl=res.path("indicators").path("quote").path(0).path("close");
        List<Double> c=new ArrayList<>(); for(int i=0;i<cl.size();i++){ if(!cl.path(i).isNull()) c.add(cl.path(i).asDouble()); }
        double[] a=new double[c.size()]; for(int i=0;i<a.length;i++) a[i]=c.get(i); return a; }

    static double mean(List<Double> v){ if(v.isEmpty())return 0; double s=0; for(double x:v)s+=x; return s/v.size(); }
    static double median(List<Double> v){ if(v.isEmpty())return 0; List<Double> s=new ArrayList<>(v); Collections.sort(s); int n=s.size(); return n%2==1?s.get(n/2):(s.get(n/2-1)+s.get(n/2))/2; }
    static double fracPos(List<Double> v){ if(v.isEmpty())return 0; int c=0; for(double x:v) if(x>0)c++; return (double)c/v.size(); }
}
