import com.fasterxml.jackson.databind.*;
import java.net.http.*;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

/**
 * Doc 43 Step D: perps-only cross-sectional carry portfolio on Binance, 7yr, POINT-IN-TIME universe.
 * Role = MECHANISM VALIDATION only (not tradeable in EU; ranks don't transfer to Kraken).
 * Universe list: Binance Vision S3 archive (all symbols incl. delisted). Data: fapi REST (returns delisted).
 * Short top-funding decile / long bottom, weekly rebalance, L=7,h=7, 2x, liquidation model (intra-week high/low).
 * Answers: (A) liquidation freq PIT vs survivors, (B) NET by subperiod, (C) LUNA/delisted-share.
 */
public class S12PortfolioBinance {
    static final HttpClient HC = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(20)).build();
    static final ObjectMapper M = new ObjectMapper();
    static final long DAY = 86400000L;
    static final int L=7, H=7, STEP=7;
    static final double ANN = 365.0/7.0;
    static final double LIQ = 0.50;

    static JsonNode get(String url) throws Exception {
        for (int attempt=0; attempt<4; attempt++) {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url)).header("User-Agent","curl/8.0").timeout(Duration.ofSeconds(40)).GET().build();
            HttpResponse<String> r = HC.send(req, HttpResponse.BodyHandlers.ofString());
            if (r.statusCode()==200) return M.readTree(r.body());
            if (r.statusCode()==429 || r.statusCode()==418) { Thread.sleep(2000L*(attempt+1)); continue; }
            throw new RuntimeException("HTTP "+r.statusCode());
        }
        throw new RuntimeException("retries exhausted");
    }
    static String getRaw(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).header("User-Agent","curl/8.0").timeout(Duration.ofSeconds(40)).GET().build();
        return HC.send(req, HttpResponse.BodyHandlers.ofString()).body();
    }

    // per symbol
    static Map<String,TreeMap<Long,Double>> fund = new HashMap<>();      // day -> sum 8h funding
    static Map<String,TreeMap<Long,double[]>> px = new HashMap<>();       // day -> [close,high,low,quoteVol]
    static Map<String,Long> firstDay = new HashMap<>(), lastDay = new HashMap<>();
    static Set<String> liveToday = new HashSet<>();
    static long dNow;

    public static void main(String[] a) throws Exception {
        dNow = System.currentTimeMillis()/DAY;
        long fromMs = Instant.parse("2019-08-01T00:00:00Z").toEpochMilli();

        // universe list from S3 archive (incl delisted), USDT perps
        List<String> syms = listArchiveSymbols();
        System.out.println("archive USDT symbols: "+syms.size());
        // today's live set for naive/ survivorship comparison
        JsonNode info = get("https://fapi.binance.com/fapi/v1/exchangeInfo");
        for (JsonNode n : info.get("symbols"))
            if ("PERPETUAL".equals(n.path("contractType").asText()) && "USDT".equals(n.path("quoteAsset").asText()) && "TRADING".equals(n.path("status").asText()))
                liveToday.add(n.path("symbol").asText());
        System.out.println("live today: "+liveToday.size());

        int done=0, ok=0;
        for (String s : syms) {
            try {
                TreeMap<Long,Double> fm = new TreeMap<>();
                long st=fromMs;
                for (int pg=0; pg<10; pg++) {
                    JsonNode fr = get("https://fapi.binance.com/fapi/v1/fundingRate?symbol="+s+"&startTime="+st+"&limit=1000");
                    if (!fr.isArray()||fr.size()==0) break;
                    long last=st;
                    for (JsonNode row: fr){ long ts=row.path("fundingTime").asLong(); fm.merge(ts/DAY, row.path("fundingRate").asDouble(), Double::sum); last=ts; }
                    if (fr.size()<1000) break; st=last+1; Thread.sleep(40);
                }
                if (fm.isEmpty()) { done++; continue; }
                TreeMap<Long,double[]> pm = new TreeMap<>();
                st=fromMs;
                for (int pg=0; pg<6; pg++) {
                    JsonNode kl = get("https://fapi.binance.com/fapi/v1/klines?symbol="+s+"&interval=1d&startTime="+st+"&limit=1500");
                    if (!kl.isArray()||kl.size()==0) break;
                    long last=st;
                    for (JsonNode c: kl){ long ot=c.get(0).asLong();
                        pm.put(ot/DAY, new double[]{Double.parseDouble(c.get(4).asText()), Double.parseDouble(c.get(2).asText()), Double.parseDouble(c.get(3).asText()), Double.parseDouble(c.get(7).asText())});
                        last=ot; }
                    if (kl.size()<1500) break; st=last+DAY; Thread.sleep(40);
                }
                if (pm.isEmpty()) { done++; continue; }
                fund.put(s,fm); px.put(s,pm);
                firstDay.put(s, Math.min(fm.firstKey(), pm.firstKey()));
                lastDay.put(s, Math.max(fm.lastKey(), pm.lastKey()));
                ok++;
            } catch(Exception e){}
            if(++done%100==0) System.out.println("  fetched "+done+"/"+syms.size()+" (ok "+ok+")");
            Thread.sleep(30);
        }
        System.out.println("symbols with data: "+ok);

        // capital levels -> turnover thresholds (capital*10)
        System.out.println("\n===== STEP D: BINANCE PIT PORTFOLIO (mechanism validation) =====");
        for (double[] lvl : new double[][]{{1000,10_000},{200_000,2_000_000}}) {
            System.out.printf("%n########## капитал $%.0f, порог оборота $%.0f ##########%n", lvl[0], lvl[1]);
            sim(lvl[1], true,  "PIT (incl делистнутые)");
            sim(lvl[1], false, "наивная (только выжившие)");
        }
    }

    static void sim(double minTurn, boolean pit, String label) {
        long d0 = Instant.parse("2019-09-16T00:00:00Z").toEpochMilli()/DAY; // ~first weekly Monday of perp funding era
        // subperiod boundaries
        long b1 = LocalDate.parse("2021-01-01").toEpochDay(); // day index in epoch-days
        long b2 = LocalDate.parse("2023-01-01").toEpochDay();
        List<Double>[] sub = new List[]{new ArrayList<>(),new ArrayList<>(),new ArrayList<>()};
        List<Double> all=new ArrayList<>(); List<Integer> nNames=new ArrayList<>();
        int shortLiq=0, longLiq=0, legWeeks=0;
        int shortDelisted=0, shortTotal=0; int lunaTopWeeks=0;
        for (long t=d0; t+H<=dNow; t+=STEP) {
            List<double[]> rows=new ArrayList<>(); List<String> names=new ArrayList<>();
            for (String s : fund.keySet()) {
                if (!pit && !liveToday.contains(s)) continue;
                if (firstDay.get(s) > t-60) continue;
                TreeMap<Long,Double> fm=fund.get(s); TreeMap<Long,double[]> pm=px.get(s);
                double sSum=0; int sN=0; boolean full=true;
                for(long d=t-L; d<t; d++){ Double c=fm.get(d); if(c!=null){sSum+=c;sN++;} }
                if(sN<3) continue;
                double rSum=0; int rN=0;
                for(long d=t; d<t+H; d++){ Double c=fm.get(d); if(c!=null){rSum+=c;rN++;} }
                if(rN<3) continue;
                double[] pt=pm.get(t); double[] ph=nearest(pm,t+H);
                if(pt==null||ph==null||pt[0]<=0) continue;
                double maxHi=pt[0],minLo=pt[0];
                for(long d=t; d<t+H; d++){ double[] x=pm.get(d); if(x!=null){ maxHi=Math.max(maxHi,x[1]); minLo=Math.min(minLo,x[2]); } }
                // turnover median over [t-30,t)
                List<Double> tv=new ArrayList<>();
                for(long d=t-30; d<t; d++){ double[] x=pm.get(d); if(x!=null&&x[3]>0) tv.add(x[3]); }
                if(tv.size()<15 || median(tv)<minTurn) continue;
                double priceRet=(ph[0]-pt[0])/pt[0], shortAdv=(maxHi-pt[0])/pt[0], longAdv=(pt[0]-minLo)/pt[0];
                rows.add(new double[]{sSum/sN, rSum, priceRet, shortAdv, longAdv}); names.add(s);
            }
            if(rows.size()<8) continue;
            Integer[] ord=new Integer[rows.size()]; for(int i=0;i<ord.length;i++) ord[i]=i;
            Arrays.sort(ord,(x,y)->Double.compare(rows.get(y)[0],rows.get(x)[0]));
            int k=Math.max(1,(int)Math.ceil(rows.size()/10.0));
            double fTop=0,fBot=0,sPx=0,lPx=0;
            for(int i=0;i<k;i++){ double[] r=rows.get(ord[i]); String nm=names.get(ord[i]);
                fTop+=r[1]; boolean liq=r[3]>=LIQ; sPx += liq?-LIQ:-r[2]; if(liq) shortLiq++;
                shortTotal++; if(lastDay.get(nm) < dNow-45) shortDelisted++;
                if(nm.equals("LUNAUSDT")) lunaTopWeeks++;
                legWeeks++;
            }
            for(int i=0;i<k;i++){ double[] r=rows.get(ord[rows.size()-1-i]);
                fBot+=r[1]; boolean liq=r[4]>=LIQ; lPx += liq?-LIQ:r[2]; if(liq) longLiq++; }
            double net = (fTop/k-fBot/k) + sPx/k + lPx/k;
            all.add(net); nNames.add(rows.size());
            long ed = t; // epoch-day index
            if(ed<b1) sub[0].add(net); else if(ed<b2) sub[1].add(net); else sub[2].add(net);
        }
        double liqYr=(shortLiq+longLiq)*(52.0/Math.max(1,all.size()));
        System.out.printf("%n-- %s --%n", label);
        System.out.printf("  NET all = %+.1f%%/год   ребалансов=%d  имён/нед медиана=%d  %%нед>0=%.0f%%%n",
            all.isEmpty()?0:mean(all)*ANN*100, all.size(), all.isEmpty()?0:med(nNames), all.isEmpty()?0:100.0*fracPos(all));
        System.out.printf("  подпериоды NET: 2019-20=%+.0f%%  2021-22=%+.0f%%  2023-26=%+.0f%%%n",
            sub[0].isEmpty()?0:mean(sub[0])*ANN*100, sub[1].isEmpty()?0:mean(sub[1])*ANN*100, sub[2].isEmpty()?0:mean(sub[2])*ANN*100);
        System.out.printf("  ликвидаций ног: шорт=%d лонг=%d -> ~%.1f/год (%.1f%% нога-недель)%n",
            shortLiq, longLiq, liqYr, 100.0*(shortLiq+longLiq)/Math.max(1,2*legWeeks));
        System.out.printf("  шорт-ног верхнего дециля впоследствии делистнуто: %d/%d (%.1f%%)%n",
            shortDelisted, shortTotal, 100.0*shortDelisted/Math.max(1,shortTotal));
        System.out.printf("  LUNAUSDT в верхнем дециле по funding: %d недель%n", lunaTopWeeks);
    }

    static double[] nearest(TreeMap<Long,double[]> m, long d){ double[] x=m.get(d); if(x!=null) return x; Long f=m.ceilingKey(d); if(f!=null&&f<=d+3) return m.get(f); Long g=m.floorKey(d); if(g!=null&&g>=d-3) return m.get(g); return null; }

    static List<String> listArchiveSymbols() throws Exception {
        List<String> out=new ArrayList<>(); String marker="";
        for (int pg=0; pg<5; pg++) {
            String url="https://s3-ap-northeast-1.amazonaws.com/data.binance.vision?delimiter=/&prefix=data/futures/um/monthly/fundingRate/"+(marker.isEmpty()?"":"&marker="+URLEncoder.encode(marker,StandardCharsets.UTF_8));
            String xml=getRaw(url); String last=null;
            int i=0; while(true){ int a=xml.indexOf("<Prefix>",i); if(a<0) break; int b=xml.indexOf("</Prefix>",a); String p=xml.substring(a+8,b); i=b+9;
                if(p.endsWith("/") && p.contains("fundingRate/")){ String sym=p.substring(p.indexOf("fundingRate/")+12); sym=sym.replace("/",""); if(sym.endsWith("USDT")) out.add(sym); last=p; } }
            if(!xml.contains("<IsTruncated>true</IsTruncated>") || last==null) break; marker=last; Thread.sleep(100);
        }
        return out;
    }

    static double fracPos(List<Double> v){ int c=0; for(double x:v) if(x>0)c++; return (double)c/v.size(); }
    static int med(List<Integer> v){ List<Integer> s=new ArrayList<>(v); Collections.sort(s); return s.get(s.size()/2); }
    static double mean(List<Double> v){ double s=0; for(double x:v)s+=x; return s/v.size(); }
    static double median(List<Double> v){ if(v.isEmpty())return 0; List<Double> s=new ArrayList<>(v); Collections.sort(s); int n=s.size(); return n%2==1?s.get(n/2):(s.get(n/2-1)+s.get(n/2))/2; }
}
