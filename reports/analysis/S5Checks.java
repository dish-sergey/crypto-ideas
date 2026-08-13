import com.fasterxml.jackson.databind.*;
import java.net.http.*;
import java.net.URI;
import java.time.*;
import java.util.*;

/**
 * Doc 51: three checks before the S5 P&L model. Reuses cached emissions (TEMP/s5cache).
 * A (§4, blocking): stop-loss grid (-10/-20/-30/-50/none) on intra-hold HIGH, squeeze slippage 1%.
 * C (§6): short cost = funding over 5d hold; premium cheap vs expensive (short pays >1.5%).
 * B (§5): refined category (investors/team/ecosystem/staking) on top-100 largest unlocks.
 */
public class S5Checks {
    static final HttpClient HC = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    static final ObjectMapper M = new ObjectMapper();
    static final long DAY=86400000L; static final double MIN_PCT=0.03; static final int LEAD=5; static final double SLIP=0.01;

    static JsonNode get(String url) throws Exception {
        for(int at=0;at<4;at++){ HttpRequest req=HttpRequest.newBuilder(URI.create(url)).header("User-Agent","curl/8.0").timeout(Duration.ofSeconds(50)).GET().build();
            HttpResponse<String> r=HC.send(req,HttpResponse.BodyHandlers.ofString());
            if(r.statusCode()==200) return M.readTree(r.body());
            if(r.statusCode()==429||r.statusCode()==418){ Thread.sleep(1500L*(at+1)); continue; }
            throw new RuntimeException("HTTP "+r.statusCode()); }
        throw new RuntimeException("retries"); }

    static String catClass(String label){ String s=label.toLowerCase();
        if(s.contains("investor")||s.contains("private")||s.contains("seed")||s.contains("vc")||s.contains("backer")||s.contains("presale")) return "investors";
        if(s.contains("team")||s.contains("core")||s.contains("contributor")||s.contains("founder")||s.contains("advisor")||s.contains("insider")) return "team";
        if(s.contains("stak")||s.contains("mining")||s.contains("reward")||s.contains("airdrop")||s.contains("incentive")||s.contains("liquidity")) return "staking";
        return "ecosystem"; }

    record Ev(String base, long day, double pct, String cat){}

    public static void main(String[] a) throws Exception {
        JsonNode binfo=get("https://fapi.binance.com/fapi/v1/exchangeInfo"); Set<String> perp=new HashSet<>();
        for(JsonNode n:binfo.get("symbols")) if("PERPETUAL".equals(n.path("contractType").asText())&&"USDT".equals(n.path("quoteAsset").asText())) perp.add(n.path("baseAsset").asText().toUpperCase());
        JsonNode cg=get("https://api.coingecko.com/api/v3/coins/list"); Map<String,String> gid=new HashMap<>();
        for(JsonNode n:cg) gid.put(n.path("id").asText(), n.path("symbol").asText().toUpperCase());

        // read cached emissions
        java.nio.file.Path cache=java.nio.file.Path.of(System.getenv("TEMP")==null?"/tmp":System.getenv("TEMP"),"s5cache");
        List<Ev> events=new ArrayList<>(); Set<String> bases=new TreeSet<>();
        for(java.nio.file.Path cf:(Iterable<java.nio.file.Path>)java.nio.file.Files.list(cache)::iterator){
            try{ JsonNode e=M.readTree(cf.toFile()); JsonNode meta=e.path("metadata"); String tok=meta.path("token").asText("");
                String gecko=tok.startsWith("coingecko:")?tok.substring(10):e.path("gecko_id").asText(""); String sym=gid.getOrDefault(gecko,"");
                if(sym.isEmpty()||!perp.contains(sym)) continue;
                TreeMap<Long,Double> total=new TreeMap<>();
                for(JsonNode c:e.path("documentedData").path("data")) for(JsonNode p:c.path("data")){ long ts=p.path("timestamp").asLong(); total.merge(ts,p.path("unlocked").asDouble(0),Double::sum); }
                if(total.isEmpty()) continue;
                JsonNode evs=meta.path("events"); if(evs.isMissingNode()||!evs.isArray()) evs=e.path("events");
                for(JsonNode ev:evs){ if(!"cliff".equals(ev.path("unlockType").asText())) continue; JsonNode t=ev.path("noOfTokens"); if(!t.isArray()||t.size()==0) continue; double amt=t.get(0).asDouble(0); if(amt<=0) continue;
                    long ts=ev.path("timestamp").asLong(); Long fk=total.floorKey(ts); if(fk==null) continue; double circ=total.get(fk); if(circ<=0) continue; double pct=amt/circ; if(pct<MIN_PCT) continue;
                    String desc=ev.path("description").asText(""); String label=ev.path("category").asText(""); int fi=desc.indexOf("from "); if(fi>=0){ int oi=desc.indexOf(" on ",fi); if(oi>fi) label=desc.substring(fi+5,oi); }
                    events.add(new Ev(sym,ts/86400,Math.min(pct,1),catClass(label))); bases.add(sym); }
            }catch(Exception ex){} }
        System.out.println("events from cache: "+events.size()+" | tokens: "+bases.size());

        // prices (close+high) + funding
        Map<String,TreeMap<Long,double[]>> px=new HashMap<>(); Map<String,TreeMap<Long,Double>> fund=new HashMap<>();
        long from=Instant.parse("2020-01-01T00:00:00Z").toEpochMilli(); int fi=0;
        for(String b:bases){ try{ TreeMap<Long,double[]> m=new TreeMap<>(); long st=from;
            for(int pg=0;pg<4;pg++){ JsonNode kl=get("https://fapi.binance.com/fapi/v1/klines?symbol="+b+"USDT&interval=1d&startTime="+st+"&limit=1500"); if(!kl.isArray()||kl.size()==0) break; long last=st; for(JsonNode c:kl){ long ot=c.get(0).asLong(); m.put(ot/DAY,new double[]{Double.parseDouble(c.get(4).asText()),Double.parseDouble(c.get(2).asText())}); last=ot; } if(kl.size()<1500) break; st=last+DAY; }
            if(m.size()>50) px.put(b,m);
            TreeMap<Long,Double> fm=new TreeMap<>(); st=from;
            for(int pg=0;pg<8;pg++){ JsonNode fr=get("https://fapi.binance.com/fapi/v1/fundingRate?symbol="+b+"USDT&startTime="+st+"&limit=1000"); if(!fr.isArray()||fr.size()==0) break; long last=st; for(JsonNode row:fr){ long ts=row.path("fundingTime").asLong(); fm.merge(ts/DAY,row.path("fundingRate").asDouble(),Double::sum); last=ts; } if(fr.size()<1000) break; st=last+1; }
            fund.put(b,fm);
        }catch(Exception ex){} if(++fi%40==0) System.out.println("  prices "+fi+"/"+bases.size()); Thread.sleep(20); }

        Map<String,Double> uncond=new HashMap<>();
        for(var en:px.entrySet()){ List<Double> u=new ArrayList<>(); for(Long d:en.getValue().keySet()){ double[] p0=en.getValue().get(d),p5=en.getValue().get(d+5); if(p0!=null&&p5!=null&&p0[0]>0) u.add((p0[0]-p5[0])/p0[0]); } uncond.put(en.getKey(),mean(u)); }

        // ---- A: stop-loss grid ----
        System.out.println("\n===== ПРОВЕРКА A: управляемость хвоста стопом (§4) =====");
        double[] stops={0.10,0.20,0.30,0.50,99}; // 99 = no stop
        System.out.printf("%-8s %8s %8s %6s %10s %10s %8s %10s%n","стоп","прем%","медиана","%>0","худшая","худш10%","%стоп","%восстан");
        for(double stop:stops){ List<Double> prem=new ArrayList<>(); int stopped=0,recov=0,n=0;
            for(Ev ev:events){ TreeMap<Long,double[]> m=px.get(ev.base); if(m==null) continue; double[] e0=near(m,ev.day-LEAD),e1=near(m,ev.day); if(e0==null||e1==null||e0[0]<=0) continue;
                double entry=e0[0], noStopRet=(entry-e1[0])/entry; boolean hit=false;
                if(stop<1){ for(long d=ev.day-LEAD+1; d<=ev.day; d++){ double[] x=m.get(d); if(x!=null && x[1]>=entry*(1+stop)){ hit=true; break; } } }
                double ret = hit? -(stop+SLIP) : noStopRet; double p=ret-uncond.getOrDefault(ev.base,0.0); prem.add(p); n++;
                if(hit){ stopped++; if(noStopRet>-stop) recov++; } }
            Collections.sort(prem); String lbl=stop>1?"без":String.format("-%.0f%%",stop*100);
            System.out.printf("%-8s %+8.2f %+8.2f %6.0f %+10.1f %+10.1f %8.0f %10s%n", lbl, mean(prem)*100, median(prem)*100, 100.0*fracPos(prem), prem.isEmpty()?0:prem.get(0)*100, worst10(prem)*100, 100.0*stopped/Math.max(1,n), stop>1?"-":String.format("%.0f%%",100.0*recov/Math.max(1,stopped))); }
        System.out.println("Критерий §9.1: стоп срезает хвост, сохраняя >½ премии => жив; убивает премию => S3; не помогает => нежизнеспособно");

        // ---- C: short cost (funding) ----
        System.out.println("\n===== ПРОВЕРКА C: стоимость шорта (§6) =====");
        List<Double> cheapP=new ArrayList<>(), expP=new ArrayList<>(); int exp=0,tot=0;
        for(Ev ev:events){ TreeMap<Long,double[]> m=px.get(ev.base); TreeMap<Long,Double> fm=fund.get(ev.base); if(m==null||fm==null) continue; double[] e0=near(m,ev.day-LEAD),e1=near(m,ev.day); if(e0==null||e1==null||e0[0]<=0) continue;
            double shortFund=0; for(long d=ev.day-LEAD; d<ev.day; d++){ Double f=fm.get(d); if(f!=null) shortFund+=f; } // short receives +funding
            double p=(e0[0]-e1[0])/e0[0]-uncond.getOrDefault(ev.base,0.0); tot++;
            boolean expensive = shortFund < -0.015; // short PAYS >1.5% over 5d
            if(expensive){ expP.add(p); exp++; } else cheapP.add(p); }
        System.out.printf("событий с funding: %d | дорогой шорт (платит >1.5%%/5д): %d (%.0f%%)%n", tot, exp, 100.0*exp/Math.max(1,tot));
        System.out.printf("премия ДЕШЁВЫЕ шорты: %+.2f%% (n=%d) | ДОРОГИЕ: %+.2f%% (n=%d)%n", mean(cheapP)*100, cheapP.size(), mean(expP)*100, expP.size());
        System.out.println("Критерий §9.2-3: доля дорогих <½ И премия на дешёвых >0 => не арбитрирована ценой шорта");

        // ---- B: refined category on top-100 by pct ----
        System.out.println("\n===== ПРОВЕРКА B: механизм на top-100 крупнейших (§5) =====");
        List<Ev> top=new ArrayList<>(events); top.sort((x,y)->Double.compare(y.pct,x.pct)); top=top.subList(0,Math.min(100,top.size()));
        Map<String,double[]> byCat=new HashMap<>();
        for(Ev ev:top){ TreeMap<Long,double[]> m=px.get(ev.base); if(m==null) continue; double[] e0=near(m,ev.day-LEAD),e1=near(m,ev.day); if(e0==null||e1==null||e0[0]<=0) continue;
            double p=(e0[0]-e1[0])/e0[0]-uncond.getOrDefault(ev.base,0.0); double[] c=byCat.computeIfAbsent(ev.cat,x->new double[2]); c[0]+=p; c[1]++; }
        for(String cat:new String[]{"investors","team","ecosystem","staking"}){ double[] c=byCat.get(cat); if(c!=null) System.out.printf("   %-10s n=%.0f прем=%+.2f%%%n",cat,c[1],c[0]/c[1]*100); }
        System.out.println("Критерий §5: порядок investors > team > ecosystem подтверждает механизм вынужд. продаж");
    }

    static double[] near(TreeMap<Long,double[]> m,long d){ double[] x=m.get(d); if(x!=null) return x; Long f=m.floorKey(d); if(f!=null&&f>=d-3) return m.get(f); Long g=m.ceilingKey(d); if(g!=null&&g<=d+3) return m.get(g); return null; }
    static double mean(List<Double> v){ if(v.isEmpty())return 0; double s=0; for(double x:v)s+=x; return s/v.size(); }
    static double median(List<Double> v){ if(v.isEmpty())return 0; List<Double> s=new ArrayList<>(v); Collections.sort(s); int n=s.size(); return n%2==1?s.get(n/2):(s.get(n/2-1)+s.get(n/2))/2; }
    static double fracPos(List<Double> v){ if(v.isEmpty())return 0; int c=0; for(double x:v) if(x>0)c++; return (double)c/v.size(); }
    static double worst10(List<Double> v){ if(v.isEmpty())return 0; List<Double> s=new ArrayList<>(v); Collections.sort(s); int k=Math.max(1,s.size()/10); double sum=0; for(int i=0;i<k;i++) sum+=s.get(i); return sum/k; }
}
