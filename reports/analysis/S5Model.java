import com.fasterxml.jackson.databind.*;
import java.net.http.*;
import java.net.URI;
import java.time.*;
import java.util.*;

/**
 * Doc 52: full P&L model for S5 (short 5d before cliff unlock >=3% circ; stop -30%; skip expensive short).
 * Absolute short return incl costs+funding. Deployed vs whole-capital CAGR; clustering/concurrency sizing;
 * robustness grid; degradation monitor. Decision: excess over alternative >=4pp on deployed capital.
 */
public class S5Model {
    static final HttpClient HC=HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    static final ObjectMapper M=new ObjectMapper();
    static final long DAY=86400000L; static final double MIN_PCT=0.03; static final int LEAD=5;
    static final double SLIP=0.01, COST=0.0019, ALT=0.03; // costs ~0.19%/trade, alternative 3%/yr

    static JsonNode get(String url) throws Exception { for(int at=0;at<4;at++){ HttpRequest req=HttpRequest.newBuilder(URI.create(url)).header("User-Agent","curl/8.0").timeout(Duration.ofSeconds(50)).GET().build();
        HttpResponse<String> r=HC.send(req,HttpResponse.BodyHandlers.ofString()); if(r.statusCode()==200) return M.readTree(r.body()); if(r.statusCode()==429||r.statusCode()==418){ Thread.sleep(1500L*(at+1)); continue; } throw new RuntimeException("HTTP "+r.statusCode()); } throw new RuntimeException("retries"); }

    record Ev(String base, long day, double pct, double fund5){}
    static Map<String,TreeMap<Long,double[]>> px=new HashMap<>();  // close,high
    static Map<String,TreeMap<Long,Double>> fund=new HashMap<>();
    static Map<String,Double> uncond=new HashMap<>();

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
            for(JsonNode ev:evs){ if(!"cliff".equals(ev.path("unlockType").asText())) continue; JsonNode t=ev.path("noOfTokens"); if(!t.isArray()||t.size()==0) continue; double amt=t.get(0).asDouble(0); if(amt<=0) continue; long ts=ev.path("timestamp").asLong(); Long fk=total.floorKey(ts); if(fk==null) continue; double circ=total.get(fk); if(circ<=0) continue; double pct=amt/circ; if(pct<0.02) continue; raw.add(new long[]{ts/86400}); rbase.add(sym); rpct.add(Math.min(pct,1)); bases.add(sym); }
        }catch(Exception ex){} }
        System.out.println("raw events(>=2%): "+raw.size()+" tokens: "+bases.size());

        long from=Instant.parse("2020-01-01T00:00:00Z").toEpochMilli(); int fi=0;
        for(String b:bases){ try{ TreeMap<Long,double[]> m=new TreeMap<>(); long st=from;
            for(int pg=0;pg<4;pg++){ JsonNode kl=get("https://fapi.binance.com/fapi/v1/klines?symbol="+b+"USDT&interval=1d&startTime="+st+"&limit=1500"); if(!kl.isArray()||kl.size()==0) break; long last=st; for(JsonNode c:kl){ long ot=c.get(0).asLong(); m.put(ot/DAY,new double[]{Double.parseDouble(c.get(4).asText()),Double.parseDouble(c.get(2).asText())}); last=ot; } if(kl.size()<1500) break; st=last+DAY; } if(m.size()>50) px.put(b,m);
            TreeMap<Long,Double> fm=new TreeMap<>(); st=from; for(int pg=0;pg<8;pg++){ JsonNode fr=get("https://fapi.binance.com/fapi/v1/fundingRate?symbol="+b+"USDT&startTime="+st+"&limit=1000"); if(!fr.isArray()||fr.size()==0) break; long last=st; for(JsonNode row:fr){ long ts=row.path("fundingTime").asLong(); fm.merge(ts/DAY,row.path("fundingRate").asDouble(),Double::sum); last=ts; } if(fr.size()<1000) break; st=last+1; } fund.put(b,fm);
        }catch(Exception ex){} if(++fi%40==0) System.out.println("  prices "+fi+"/"+bases.size()); Thread.sleep(20); }
        for(var en:px.entrySet()){ List<Double> u=new ArrayList<>(); for(Long d:en.getValue().keySet()){ double[] p0=en.getValue().get(d),p5=en.getValue().get(d+5); if(p0!=null&&p5!=null&&p0[0]>0) u.add((p0[0]-p5[0])/p0[0]); } uncond.put(en.getKey(),mean(u)); }

        // build EVENTS with 5d funding
        for(int i=0;i<raw.size();i++){ String b=rbase.get(i); long day=raw.get(i)[0]; TreeMap<Long,Double> fm=fund.get(b); double f5=0; if(fm!=null){ for(long d=day-LEAD; d<day; d++){ Double f=fm.get(d); if(f!=null) f5+=f; } } EVENTS.add(new Ev(b, day, rpct.get(i), f5)); }
        System.out.println("EVENTS built: "+EVENTS.size());

        // ---- main config ----
        System.out.println("\n===== S5 ПОЛНАЯ МОДЕЛЬ (стоп −30%, пропуск дорогого шорта, ≥3%circ, окно 5д) =====");
        List<double[]> trades=simTrades(0.30, -0.015, 0.03, 5); // returns [day, absRet]
        report(trades);

        // ---- concurrency / sizing (§3.3) ----
        trades.sort((x,y)->Long.compare((long)x[0],(long)y[0]));
        int[] conc=new int[trades.size()]; int maxC=0; long span= trades.isEmpty()?1:(long)trades.get(trades.size()-1)[0]-(long)trades.get(0)[0]+1;
        long[] occ=new long[400]; // days with k concurrent (approx via window overlap)
        // count concurrent: for each trade window [day-5,day], overlap with others
        for(int i=0;i<trades.size();i++){ long di=(long)trades.get(i)[0]; int c=0; for(int j=0;j<trades.size();j++){ long dj=(long)trades.get(j)[0]; if(Math.abs(di-dj)<LEAD) c++; } conc[i]=c; maxC=Math.max(maxC,c); }
        double avgC=0; for(int c:conc) avgC+=c; avgC/=Math.max(1,conc.length);
        System.out.printf("%nКЛАСТЕРИЗАЦИЯ (§3.3): макс одновременных=%d, среднее=%.1f%n", maxC, avgC);
        // portfolio limit: all stopped at -31% simultaneously <= 25% DD
        double posMax=0.25/(maxC*0.31);
        System.out.printf("портфельный лимит: при макс %d одновременных и стопе −31%%, позиция ≤ %.1f%% капитала (общая эксп. ≤ %.0f%%)%n", maxC, posMax*100, posMax*maxC*100);

        // ---- degradation monitor (§6) ----
        List<Double> roll=new ArrayList<>(); double minRoll=99;
        for(int i=19;i<trades.size();i++){ double s=0; for(int j=i-19;j<=i;j++) s+=trades.get(j)[1]; double m=s/20; roll.add(m); minRoll=Math.min(minRoll,m); }
        System.out.printf("МОНИТОР ДЕГРАДАЦИИ (§6): скольз.20-событий премия последняя=%+.2f%%, минимум=%+.2f%%%n",
            roll.isEmpty()?0:roll.get(roll.size()-1)*100, roll.isEmpty()?0:minRoll*100);

        // ---- robustness grid (§3.4) ----
        System.out.println("\n===== УСТОЙЧИВОСТЬ (§3.4, вся сетка; mean absRet/сделка) =====");
        System.out.print("стоп:   "); for(double s:new double[]{0.20,0.30,0.40,0.50}) System.out.printf("−%.0f%%=%+.2f%%  ", s*100, mean(col(simTrades(s,-0.015,0.03,5),1))*100); System.out.println();
        System.out.print("funding порог: "); for(double f:new double[]{-0.005,-0.010,-0.015,-0.030}) System.out.printf("%.1f%%=%+.2f%%  ", f*100, mean(col(simTrades(0.30,f,0.03,5),1))*100); System.out.println();
        System.out.print("разлок ≥: "); for(double u:new double[]{0.02,0.03,0.05}) System.out.printf("%.0f%%=%+.2f%%  ", u*100, mean(col(simTrades(0.30,-0.015,u,5),1))*100); System.out.println();
        System.out.print("окно входа: "); for(int w:new int[]{3,5,7}) System.out.printf("%dд=%+.2f%%  ", w, mean(col(simTrades(0.30,-0.015,0.03,w),1))*100); System.out.println();
        System.out.println("\nКритерий §4: превосходство ≥4пп/год на задействованный капитал; устойчиво по сетке; лимит совместим с 25% DD");
        System.out.println("Оговорка §3.5: расписания НЕ point-in-time (архивных снимков нет) => премия ВЕРХНЯЯ ГРАНИЦА, применить консерв. дисконт.");
    }

    static List<double[]> simTrades(double stop, double fundThr, double minPct, int window){
        // rebuild events from cache-derived raw? re-read via static? simpler: iterate stored px/fund + a static events list
        List<double[]> out=new ArrayList<>();
        for(Ev ev:EVENTS){ if(ev.pct<minPct) continue; if(ev.fund5<fundThr) continue; // skip expensive
            TreeMap<Long,double[]> m=px.get(ev.base); if(m==null) continue; double[] e0=near(m,ev.day-window),e1=near(m,ev.day); if(e0==null||e1==null||e0[0]<=0) continue;
            double entry=e0[0]; boolean hit=false; for(long d=ev.day-window+1; d<=ev.day; d++){ double[] x=m.get(d); if(x!=null&&x[1]>=entry*(1+stop)){ hit=true; break; } }
            double priceRet=hit? -(stop+SLIP) : (entry-e1[0])/entry;
            double abs=priceRet + ev.fund5 - COST; out.add(new double[]{ev.day, abs}); }
        return out;
    }
    static List<Ev> EVENTS=new ArrayList<>();
    static { }

    static void report(List<double[]> trades){
        List<Double> r=col(trades,1); Collections.sort(new ArrayList<>(r));
        double m=mean(r); List<Double> sr=new ArrayList<>(r); Collections.sort(sr);
        // sequential equity (one-at-a-time sleeve) for MaxDD
        List<double[]> byDay=new ArrayList<>(trades); byDay.sort((x,y)->Long.compare((long)x[0],(long)y[0]));
        double eq=1,peak=1,mdd=0; for(double[] t:byDay){ eq*=(1+t[1]); peak=Math.max(peak,eq); mdd=Math.min(mdd,eq/peak-1); }
        int n=r.size(); double deployedCAGR=Math.pow(prod(r), 365.0/(5.0*Math.max(1,n)))-1;
        // year split
        int[] yc=new int[3]; double[] ys=new double[3]; for(double[] t:trades){ int yr=Instant.ofEpochMilli((long)t[0]*DAY).atZone(ZoneOffset.UTC).getYear(); int b=yr<=2022?0:(yr<=2024?1:2); yc[b]++; ys[b]+=t[1]; }
        System.out.printf("сделок=%d  сред.absRet=%+.2f%%  медиана=%+.2f%%  %%>0=%.0f%%  худшая=%+.1f%%  худшие10%%=%+.1f%%  MaxDD(sleeve)=%.0f%%%n",
            n, m*100, median(sr)*100, 100.0*fracPos(r), sr.isEmpty()?0:sr.get(0)*100, worst10(sr)*100, mdd*100);
        System.out.printf("по годам absRet: 2022=%+.2f%% (n%d) | 2023-24=%+.2f%% (n%d) | 2025-26=%+.2f%% (n%d)%n", yc[0]>0?ys[0]/yc[0]*100:0,yc[0], yc[1]>0?ys[1]/yc[1]*100:0,yc[1], yc[2]>0?ys[2]/yc[2]*100:0,yc[2]);
        System.out.printf("CAGR на ЗАДЕЙСТВОВАННЫЙ капитал=%+.0f%%/год  (превышение над альтернативой 3%%: %+.0f пп)%n", deployedCAGR*100, (deployedCAGR-ALT)*100);
        System.out.println("(whole-capital CAGR зависит от доли времени в позиции — см. кластеризацию; при малой доле ~= альтернатива + вклад)");
    }

    static double[] near(TreeMap<Long,double[]> m,long d){ double[] x=m.get(d); if(x!=null) return x; Long f=m.floorKey(d); if(f!=null&&f>=d-3) return m.get(f); Long g=m.ceilingKey(d); if(g!=null&&g<=d+3) return m.get(g); return null; }
    static List<Double> col(List<double[]> t,int i){ List<Double> o=new ArrayList<>(); for(double[] x:t) o.add(x[i]); return o; }
    static double prod(List<Double> v){ double p=1; for(double x:v) p*=(1+x); return p; }
    static double mean(List<Double> v){ if(v.isEmpty())return 0; double s=0; for(double x:v)s+=x; return s/v.size(); }
    static double median(List<Double> v){ if(v.isEmpty())return 0; List<Double> s=new ArrayList<>(v); Collections.sort(s); int n=s.size(); return n%2==1?s.get(n/2):(s.get(n/2-1)+s.get(n/2))/2; }
    static double fracPos(List<Double> v){ if(v.isEmpty())return 0; int c=0; for(double x:v) if(x>0)c++; return (double)c/v.size(); }
    static double worst10(List<Double> v){ if(v.isEmpty())return 0; List<Double> s=new ArrayList<>(v); Collections.sort(s); int k=Math.max(1,s.size()/10); double su=0; for(int i=0;i<k;i++) su+=s.get(i); return su/k; }
}
