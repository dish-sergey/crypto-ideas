import com.fasterxml.jackson.databind.*;
import java.net.http.*;
import java.net.URI;
import java.time.*;
import java.util.*;

/**
 * Doc 50 §6.2: S5 trivial benchmark — short perp 5 days before a cliff unlock >=3% circulating, exit at unlock.
 * Data (unblocked): DefiLlama datasets CDN (free) emissions/{slug} -> cliff events; CoinGecko coins/list -> symbol.
 * Prices: Binance daily klines (returns delisted too). Benchmark = unconditional 5-day short return.
 * Checks: year split (2022/23-24/25-26), recipient category (insider/team/ecosystem), tail. Params fixed.
 * NB: point-in-time schedules unavailable -> today's schedule used -> event study OPTIMISTIC (flagged).
 */
public class S5Unlocks {
    static final HttpClient HC = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    static final ObjectMapper M = new ObjectMapper();
    static final long DAY=86400000L;
    static final double MIN_PCT=0.03; static final int LEAD=5;

    static JsonNode get(String url) throws Exception {
        for(int at=0;at<4;at++){ HttpRequest req=HttpRequest.newBuilder(URI.create(url)).header("User-Agent","curl/8.0").timeout(Duration.ofSeconds(50)).GET().build();
            HttpResponse<String> r=HC.send(req,HttpResponse.BodyHandlers.ofString());
            if(r.statusCode()==200) return M.readTree(r.body());
            if(r.statusCode()==429||r.statusCode()==418){ Thread.sleep(1500L*(at+1)); continue; }
            throw new RuntimeException("HTTP "+r.statusCode()); }
        throw new RuntimeException("retries"); }
    static String getRaw(String url) throws Exception {
        for(int at=0;at<4;at++){ HttpRequest req=HttpRequest.newBuilder(URI.create(url)).header("User-Agent","curl/8.0").timeout(Duration.ofSeconds(50)).GET().build();
            HttpResponse<String> r=HC.send(req,HttpResponse.BodyHandlers.ofString());
            if(r.statusCode()==200) return r.body();
            if(r.statusCode()==429||r.statusCode()==418){ Thread.sleep(1500L*(at+1)); continue; }
            throw new RuntimeException("HTTP "+r.statusCode()); }
        throw new RuntimeException("retries"); }

    static String catClass(String label){ String s=label.toLowerCase();
        if(s.contains("investor")||s.contains("insider")||s.contains("private")||s.contains("seed")||s.contains("vc")||s.contains("backer")) return "insider";
        if(s.contains("team")||s.contains("core")||s.contains("contributor")||s.contains("founder")||s.contains("advisor")) return "team";
        return "ecosystem"; }

    // event: base symbol, unlock epoch-day, %circ, catClass
    record Ev(String base, long day, double pct, String cat){}

    public static void main(String[] a) throws Exception {
        // Binance perp bases
        JsonNode binfo=get("https://fapi.binance.com/fapi/v1/exchangeInfo"); Set<String> perp=new HashSet<>();
        for(JsonNode n:binfo.get("symbols")) if("PERPETUAL".equals(n.path("contractType").asText())&&"USDT".equals(n.path("quoteAsset").asText())) perp.add(n.path("baseAsset").asText().toUpperCase());
        System.out.println("Binance perp bases: "+perp.size());
        // CoinGecko id->symbol
        JsonNode cg=get("https://api.coingecko.com/api/v3/coins/list"); Map<String,String> gid=new HashMap<>();
        for(JsonNode n:cg) gid.put(n.path("id").asText(), n.path("symbol").asText().toUpperCase());
        System.out.println("CoinGecko coins: "+gid.size());
        // protocols
        JsonNode plist=get("https://defillama-datasets.llama.fi/emissionsProtocolsList");
        List<String> slugs=new ArrayList<>(); for(JsonNode n:plist) slugs.add(n.asText());
        System.out.println("emission protocols: "+slugs.size());

        java.nio.file.Path cache=java.nio.file.Path.of(System.getenv("TEMP")==null?"/tmp":System.getenv("TEMP"),"s5cache");
        java.nio.file.Files.createDirectories(cache);
        List<Ev> events=new ArrayList<>(); Set<String> bases=new TreeSet<>(); int matched=0,done=0;
        for(String slug:slugs){ try{
            java.nio.file.Path cf=cache.resolve(slug.replaceAll("[^a-zA-Z0-9_-]","_")+".json");
            JsonNode e; if(java.nio.file.Files.exists(cf)&&java.nio.file.Files.size(cf)>10){ e=M.readTree(cf.toFile()); }
            else { String body=getRaw("https://defillama-datasets.llama.fi/emissions/"+slug); java.nio.file.Files.writeString(cf,body); e=M.readTree(body); Thread.sleep(20); }
            JsonNode meta=e.path("metadata"); String tok=meta.path("token").asText("");
            String gecko = tok.startsWith("coingecko:")?tok.substring(10):e.path("gecko_id").asText("");
            String sym=gid.getOrDefault(gecko,""); if(sym.isEmpty()||!perp.contains(sym)){ done++; continue; }
            // total unlocked timeline from documentedData
            TreeMap<Long,Double> total=new TreeMap<>();
            for(JsonNode catNode:e.path("documentedData").path("data")){
                for(JsonNode p:catNode.path("data")){ long ts=p.path("timestamp").asLong(); double u=p.path("unlocked").asDouble(0); total.merge(ts,u,Double::sum); } }
            if(total.isEmpty()){ done++; continue; }
            JsonNode evs=meta.path("events"); if(evs.isMissingNode()||!evs.isArray()) evs=e.path("events");
            int kept=0;
            for(JsonNode ev:evs){ if(!"cliff".equals(ev.path("unlockType").asText())) continue;
                JsonNode toks=ev.path("noOfTokens"); if(!toks.isArray()||toks.size()==0) continue; double amt=toks.get(0).asDouble(0); if(amt<=0) continue;
                long ts=ev.path("timestamp").asLong(); Long fk=total.floorKey(ts); if(fk==null) continue; double circ=total.get(fk); if(circ<=0) continue;
                double pct=amt/circ; if(pct<MIN_PCT) continue;
                String desc=ev.path("description").asText(""); String label=ev.path("category").asText("");
                int fi=desc.indexOf("from "); if(fi>=0){ int oi=desc.indexOf(" on ",fi); label=oi>fi?desc.substring(fi+5,oi):label; }
                events.add(new Ev(sym, ts/86400, Math.min(pct,1.0), catClass(label))); bases.add(sym); kept++; }
            if(kept>0){ matched++; }
        }catch(Exception ex){} if(++done%50==0) System.out.println("  "+done+"/"+slugs.size()+" (matched "+matched+", events "+events.size()+")"); Thread.sleep(20); }
        System.out.println("matched perp-tokens: "+matched+" | cliff events >=3% circ: "+events.size()+" | unique tokens: "+bases.size());

        // Binance daily closes per base
        Map<String,TreeMap<Long,Double>> px=new HashMap<>(); long from=Instant.parse("2020-01-01T00:00:00Z").toEpochMilli();
        for(String b:bases){ try{ TreeMap<Long,Double> m=new TreeMap<>(); long st=from;
            for(int pg=0;pg<4;pg++){ JsonNode kl=get("https://fapi.binance.com/fapi/v1/klines?symbol="+b+"USDT&interval=1d&startTime="+st+"&limit=1500");
                if(!kl.isArray()||kl.size()==0) break; long last=st; for(JsonNode c:kl){ long ot=c.get(0).asLong(); m.put(ot/DAY, Double.parseDouble(c.get(4).asText())); last=ot; } if(kl.size()<1500) break; st=last+DAY; }
            if(m.size()>50) px.put(b,m); }catch(Exception ex){} Thread.sleep(25); }

        // unconditional 5d short return per token
        Map<String,Double> uncond=new HashMap<>();
        for(var en:px.entrySet()){ TreeMap<Long,Double> m=en.getValue(); List<Double> u=new ArrayList<>();
            for(Long d:m.keySet()){ Double p0=m.get(d),p5=m.get(d+5); if(p0!=null&&p5!=null&&p0>0) u.add((p0-p5)/p0); } uncond.put(en.getKey(), mean(u)); }

        // event study
        List<Double> prem=new ArrayList<>(); int[] yc=new int[3]; double[] ys=new double[3];
        Map<String,double[]> byCat=new HashMap<>(); // cat -> [sumPrem, n]
        for(Ev ev:events){ TreeMap<Long,Double> m=px.get(ev.base); if(m==null) continue;
            Double entry=near(m,ev.day-LEAD), exit=near(m,ev.day); if(entry==null||exit==null||entry<=0) continue;
            double shortRet=(entry-exit)/entry; double p=shortRet-uncond.getOrDefault(ev.base,0.0);
            prem.add(p); int yr=(int)Instant.ofEpochMilli(ev.day*DAY).atZone(ZoneOffset.UTC).getYear(); int b=yr<=2022?0:(yr<=2024?1:2); yc[b]++; ys[b]+=p;
            double[] c=byCat.computeIfAbsent(ev.cat,x->new double[2]); c[0]+=p; c[1]++; }

        System.out.println("\n===== S5 ТРИВИАЛЬНЫЙ БЕНЧМАРК (шорт 5д до разлока ≥3% circ) =====");
        Collections.sort(prem); int n=prem.size();
        System.out.printf("сделок=%d  премия(шорт над безусл.)=%+.2f%%  медиана=%+.2f%%  %%>0=%.0f%%  худшие10%%=%+.1f%%  худшая=%+.1f%%%n",
            n, mean(prem)*100, median(prem)*100, 100.0*fracPos(prem), worst10(prem)*100, n>0?prem.get(0)*100:0);
        System.out.printf("по годам: 2022 n=%d prem=%+.2f%% | 2023-24 n=%d prem=%+.2f%% | 2025-26 n=%d prem=%+.2f%%%n",
            yc[0], yc[0]>0?ys[0]/yc[0]*100:0, yc[1], yc[1]>0?ys[1]/yc[1]*100:0, yc[2], yc[2]>0?ys[2]/yc[2]*100:0);
        System.out.println("по категории получателя (проверка механизма §6.3):");
        for(var c:byCat.entrySet()) System.out.printf("   %-10s n=%.0f prem=%+.2f%%%n", c.getKey(), c.getValue()[1], c.getValue()[0]/c.getValue()[1]*100);
        System.out.println("\nКритерий §6.2: премия шорта > 0 => есть эффект; §6.3: премия у insider/team > ecosystem => механизм вынужд. продаж");
        System.out.println("Оговорка: расписания НЕ point-in-time (сегодняшние) => оценка ОПТИМИСТИЧНА (пересмотры локов не учтены).");
    }

    static Double near(TreeMap<Long,Double> m,long d){ Double x=m.get(d); if(x!=null) return x; Long f=m.floorKey(d); if(f!=null&&f>=d-3) return m.get(f); Long g=m.ceilingKey(d); if(g!=null&&g<=d+3) return m.get(g); return null; }
    static double mean(List<Double> v){ if(v.isEmpty())return 0; double s=0; for(double x:v)s+=x; return s/v.size(); }
    static double median(List<Double> v){ if(v.isEmpty())return 0; List<Double> s=new ArrayList<>(v); Collections.sort(s); int n=s.size(); return n%2==1?s.get(n/2):(s.get(n/2-1)+s.get(n/2))/2; }
    static double fracPos(List<Double> v){ if(v.isEmpty())return 0; int c=0; for(double x:v) if(x>0)c++; return (double)c/v.size(); }
    static double worst10(List<Double> v){ if(v.isEmpty())return 0; List<Double> s=new ArrayList<>(v); Collections.sort(s); int k=Math.max(1,s.size()/10); double sum=0; for(int i=0;i<k;i++) sum+=s.get(i); return sum/k; }
}
