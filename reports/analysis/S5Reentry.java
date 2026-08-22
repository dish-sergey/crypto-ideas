import com.fasterxml.jackson.databind.*;
import java.net.http.*;
import java.net.URI;
import java.time.*;
import java.util.*;

/**
 * Гипотеза оператора: если шорт S5 стопнуло (+30%), выгодно ли ПЕРЕЗАЙТИ в шорт на тех же условиях
 * (снова стоп −30% от новой цены, выход в день разлока)? Т.е. после резкого +30% цена откатывается к
 * разлоку (повторный шорт в плюс) или продолжает расти (повторный шорт тоже стопнет)?
 * Данные и конструкция — как в S5Model (fapi klines daily, стоп по внутридневному high).
 */
public class S5Reentry {
    static final HttpClient HC=HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    static final ObjectMapper M=new ObjectMapper();
    static final long DAY=86400000L; static final int LEAD=5;
    static final double STOP=0.30, SLIP=0.01, COST=0.0019, FUNDTHR=-0.015, MINPCT=0.03;

    static JsonNode get(String url) throws Exception { for(int at=0;at<4;at++){ HttpRequest req=HttpRequest.newBuilder(URI.create(url)).header("User-Agent","curl/8.0").timeout(Duration.ofSeconds(50)).GET().build();
        HttpResponse<String> r=HC.send(req,HttpResponse.BodyHandlers.ofString()); if(r.statusCode()==200) return M.readTree(r.body()); if(r.statusCode()==429||r.statusCode()==418){ Thread.sleep(1500L*(at+1)); continue; } throw new RuntimeException("HTTP "+r.statusCode()); } throw new RuntimeException("retries"); }

    record Ev(String base, long day, double pct, double fund5){}
    static Map<String,TreeMap<Long,double[]>> px=new HashMap<>();
    static Map<String,TreeMap<Long,Double>> fund=new HashMap<>();
    static List<Ev> EVENTS=new ArrayList<>();

    public static void main(String[] a) throws Exception {
        JsonNode binfo=get("https://fapi.binance.com/fapi/v1/exchangeInfo"); Set<String> perp=new HashSet<>();
        for(JsonNode n:binfo.get("symbols")) if("PERPETUAL".equals(n.path("contractType").asText())&&"USDT".equals(n.path("quoteAsset").asText())) perp.add(n.path("baseAsset").asText().toUpperCase());
        JsonNode cg=get("https://api.coingecko.com/api/v3/coins/list"); Map<String,String> gid=new HashMap<>(); for(JsonNode n:cg) gid.put(n.path("id").asText(),n.path("symbol").asText().toUpperCase());
        java.nio.file.Path cache=java.nio.file.Path.of(System.getenv("TEMP")==null?"/tmp":System.getenv("TEMP"),"s5cache");
        List<long[]> raw=new ArrayList<>(); List<String> rbase=new ArrayList<>(); List<Double> rpct=new ArrayList<>(); Set<String> bases=new TreeSet<>();
        for(java.nio.file.Path cf:(Iterable<java.nio.file.Path>)java.nio.file.Files.list(cache)::iterator){ try{
            JsonNode e=M.readTree(cf.toFile()); JsonNode meta=e.path("metadata"); String tok=meta.path("token").asText(""); String gecko=tok.startsWith("coingecko:")?tok.substring(10):e.path("gecko_id").asText(""); String sym=gid.getOrDefault(gecko,""); if(sym.isEmpty()||!perp.contains(sym)) continue;
            TreeMap<Long,Double> total=new TreeMap<>(); for(JsonNode c:e.path("documentedData").path("data")) for(JsonNode p:c.path("data")){ long ts=p.path("timestamp").asLong(); total.merge(ts,p.path("unlocked").asDouble(0),Double::sum);} if(total.isEmpty()) continue;
            JsonNode evs=meta.path("events"); if(evs.isMissingNode()||!evs.isArray()) evs=e.path("events");
            for(JsonNode ev:evs){ if(!"cliff".equals(ev.path("unlockType").asText())) continue; JsonNode t=ev.path("noOfTokens"); if(!t.isArray()||t.size()==0) continue; double amt=t.get(0).asDouble(0); if(amt<=0) continue; long ts=ev.path("timestamp").asLong(); Long fk=total.floorKey(ts); if(fk==null) continue; double circ=total.get(fk); if(circ<=0) continue; double pct=amt/circ; if(pct<MINPCT) continue; raw.add(new long[]{ts/86400}); rbase.add(sym); rpct.add(Math.min(pct,1)); bases.add(sym); }
        }catch(Exception ex){} }
        System.out.println("события(>=3%): "+raw.size()+" токенов: "+bases.size());

        long from=Instant.parse("2020-01-01T00:00:00Z").toEpochMilli(); int fi=0;
        for(String b:bases){ try{ TreeMap<Long,double[]> m=new TreeMap<>(); long st=from;
            for(int pg=0;pg<4;pg++){ JsonNode kl=get("https://fapi.binance.com/fapi/v1/klines?symbol="+b+"USDT&interval=1d&startTime="+st+"&limit=1500"); if(!kl.isArray()||kl.size()==0) break; long last=st; for(JsonNode c:kl){ long ot=c.get(0).asLong(); m.put(ot/DAY,new double[]{Double.parseDouble(c.get(4).asText()),Double.parseDouble(c.get(2).asText())}); last=ot; } if(kl.size()<1500) break; st=last+DAY; } if(m.size()>50) px.put(b,m);
            TreeMap<Long,Double> fm=new TreeMap<>(); st=from; for(int pg=0;pg<8;pg++){ JsonNode fr=get("https://fapi.binance.com/fapi/v1/fundingRate?symbol="+b+"USDT&startTime="+st+"&limit=1000"); if(!fr.isArray()||fr.size()==0) break; long last=st; for(JsonNode row:fr){ long ts=row.path("fundingTime").asLong(); fm.merge(ts/DAY,row.path("fundingRate").asDouble(),Double::sum); last=ts; } if(fr.size()<1000) break; st=last+1; } fund.put(b,fm);
        }catch(Exception ex){} if(++fi%40==0) System.out.println("  цены "+fi+"/"+bases.size()); Thread.sleep(20); }

        for(int i=0;i<raw.size();i++){ String b=rbase.get(i); long day=raw.get(i)[0]; TreeMap<Long,Double> fm=fund.get(b); double f5=0; if(fm!=null){ for(long d=day-LEAD; d<day; d++){ Double f=fm.get(d); if(f!=null) f5+=f; } } EVENTS.add(new Ev(b, day, rpct.get(i), f5)); }

        // ---- сколько всего сделок/стопов (для контекста) ----
        int nTrade=0, nStop=0;
        for(Ev ev:EVENTS){ if(ev.pct<MINPCT||ev.fund5<FUNDTHR) continue; TreeMap<Long,double[]> m=px.get(ev.base); if(m==null) continue;
            double[] e0=near(m,ev.day-LEAD),e1=near(m,ev.day); if(e0==null||e1==null||e0[0]<=0) continue; nTrade++;
            double entry=e0[0]; for(long d=ev.day-LEAD+1;d<=ev.day;d++){ double[] x=m.get(d); if(x!=null&&x[1]>=entry*(1+STOP)){ nStop++; break; } } }
        System.out.printf("%nвсего сделок=%d, из них стопнуто (+30%%)=%d (%.0f%%)%n", nTrade, nStop, 100.0*nStop/Math.max(1,nTrade));

        // ---- повторный вход по РАЗНЫМ уровням выброса (снова стоп −30% от новой цены, выход в разлок) ----
        System.out.println("\n===== ПОВТОРНЫЙ ШОРТ ПРИ ВЫБРОСЕ +X%% (новый стоп −30%, выход в день разлока) =====");
        System.out.printf("%-8s %6s %8s %8s %6s %8s %8s %8s%n","выброс","событ","сред","медиана","%>0","снова-стоп","худшая","лучшая");
        for(double trig:new double[]{0.30,0.40,0.50,0.70,1.00}) reentryAt(trig);

        System.out.println("\n«сред» — доходность повторного шорта на его размер ($10 → сред×$10).");
        System.out.println("Положительная => после выброса цена откатывается к разлоку; растёт с уровнем выброса => глубже откат.");
        System.out.println("ВНИМАНИЕ: события с большим выбросом всё малочисленнее — статзначимость падает.");
    }

    /** Повторный шорт при достижении ценой уровня entry×(1+trig); новый стоп −30%, выход в день разлока. */
    static void reentryAt(double trig){
        List<Double> re=new ArrayList<>(); int reStop=0;
        for(Ev ev:EVENTS){ if(ev.pct<MINPCT||ev.fund5<FUNDTHR) continue; TreeMap<Long,double[]> m=px.get(ev.base); if(m==null) continue;
            double[] e0=near(m,ev.day-LEAD),e1=near(m,ev.day); if(e0==null||e1==null||e0[0]<=0) continue;
            double entry=e0[0], lvl=entry*(1+trig); long tDay=-1;
            for(long d=ev.day-LEAD+1; d<=ev.day; d++){ double[] x=m.get(d); if(x!=null&&x[1]>=lvl){ tDay=d; break; } }
            if(tDay<0) continue;                                   // цена не дошла до этого выброса
            double reEntry=lvl, newStop=reEntry*(1+STOP); boolean reHit=false;
            for(long d=tDay+1; d<=ev.day; d++){ double[] x=m.get(d); if(x!=null&&x[1]>=newStop){ reHit=true; break; } }
            double pr=reHit? -(STOP+SLIP) : (reEntry-e1[0])/reEntry;
            double fundRe=0; TreeMap<Long,Double> fm=fund.get(ev.base); if(fm!=null){ for(long d=tDay; d<ev.day; d++){ Double f=fm.get(d); if(f!=null) fundRe+=f; } }
            re.add(pr+fundRe-COST); if(reHit) reStop++;
        }
        if(re.isEmpty()){ System.out.printf("+%-6.0f %6d%n", trig*100, 0); return; }
        List<Double> s=new ArrayList<>(re); Collections.sort(s);
        System.out.printf("+%-6.0f %6d %+7.2f%% %+7.2f%% %5.0f%% %7d %+7.1f%% %+7.1f%%%n",
            trig*100, re.size(), mean(re)*100, s.get(s.size()/2)*100, 100.0*fracPos(re), reStop, s.get(0)*100, s.get(s.size()-1)*100);
    }

    static void stat(String label, List<Double> v){ if(v.isEmpty()){ System.out.println("  "+label+": нет данных"); return; }
        List<Double> s=new ArrayList<>(v); Collections.sort(s);
        System.out.printf("  %s: сред=%+.2f%%  медиана=%+.2f%%  %%>0=%.0f%%  худшая=%+.1f%%  лучшая=%+.1f%%%n",
            label, mean(v)*100, s.get(s.size()/2)*100, 100.0*fracPos(v), s.get(0)*100, s.get(s.size()-1)*100);
    }
    static double[] near(TreeMap<Long,double[]> m,long d){ double[] x=m.get(d); if(x!=null) return x; Long f=m.floorKey(d); if(f!=null&&f>=d-3) return m.get(f); Long g=m.ceilingKey(d); if(g!=null&&g<=d+3) return m.get(g); return null; }
    static double mean(List<Double> v){ if(v.isEmpty())return 0; double s=0; for(double x:v)s+=x; return s/v.size(); }
    static double fracPos(List<Double> v){ int c=0; for(double x:v) if(x>0)c++; return (double)c/v.size(); }
}
